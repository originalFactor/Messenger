//! Avatar caching and upload routines.
//!
//! Handles:
//! - Conditional GET using ETag sidecars (`cloud_avatars/{identity}-{urlKey}.{ext}` + `.etag`)
//! - HTTP 304 re-use of local files without re-downloading
//! - Uploading and deleting avatars for users and agents via multipart forms

use std::fs;
use std::path::{Path, PathBuf};
use sha2::{Digest, Sha256};

use crate::api::{CloudApiClient, CloudError, CloudResult};
use crate::models::CloudAvatarResponse;

pub const MAX_AVATAR_BYTES: usize = 5 * 1024 * 1024; // 5 MiB
pub const ETAG_SIDECAR_SUFFIX: &str = ".etag";

pub struct AvatarManager<'a> {
    client: &'a CloudApiClient,
    avatars_dir: PathBuf,
}

impl<'a> AvatarManager<'a> {
    pub fn new(client: &'a CloudApiClient, avatars_dir: PathBuf) -> Self {
        Self {
            client,
            avatars_dir,
        }
    }

    /// Cache a remote avatar locally using conditional GET with ETag.
    /// Returns the local absolute file path on success.
    pub async fn cache_remote_avatar(
        &self,
        scope: &str,
        account_id: &str,
        id: &str,
        url: &str,
        version: Option<&str>,
        base_url: &str,
    ) -> CloudResult<String> {
        fs::create_dir_all(&self.avatars_dir).map_err(|e| CloudError::Network(e.to_string()))?;

        let identity = digest(&format!("{scope}|{account_id}|{id}"));
        let request_url = resolve_avatar_url(url, base_url);
        let url_key = digest(&format!("{request_url}|{}", version.unwrap_or_default()));

        let prefix = format!("{identity}-{url_key}.");
        let existing = self.find_existing_avatar(&prefix);
        let stored_etag = existing.as_ref().and_then(|p| read_etag_sidecar(p));

        let download = self
            .client
            .download_avatar_conditional(&request_url, stored_etag.as_deref())
            .await?;

        match download {
            None => {
                // HTTP 304 Not Modified: reuse existing local file
                if let Some(path) = existing {
                    return Ok(path.to_string_lossy().to_string());
                }
                // Fallback: if somehow 304 without local file, redownload without ETag
                let full = self
                    .client
                    .download_avatar_conditional(&request_url, None)
                    .await?
                    .ok_or_else(|| CloudError::Network("Avatar download returned 304 with no local file".into()))?;
                self.save_avatar_and_cleanup(scope, account_id, id, &identity, &url_key, &request_url, full)
            }
            Some(data) => {
                self.save_avatar_and_cleanup(scope, account_id, id, &identity, &url_key, &request_url, data)
            }
        }
    }

    fn save_avatar_and_cleanup(
        &self,
        scope: &str,
        account_id: &str,
        id: &str,
        identity: &str,
        url_key: &str,
        request_url: &str,
        (bytes, etag, content_type): (Vec<u8>, Option<String>, Option<String>),
    ) -> CloudResult<String> {
        if bytes.len() > MAX_AVATAR_BYTES {
            return Err(CloudError::Network("Avatar must not exceed 5 MiB".into()));
        }
        if bytes.is_empty() {
            return Err(CloudError::Network("Avatar response was empty".into()));
        }

        let extension = avatar_extension(content_type.as_deref(), request_url);
        let filename = format!("{identity}-{url_key}.{extension}");
        let target = self.avatars_dir.join(&filename);

        fs::write(&target, &bytes).map_err(|e| CloudError::Network(e.to_string()))?;
        write_etag_sidecar(&target, etag.as_deref());

        self.delete_cached_avatars_except(scope, account_id, id, &target);

        Ok(target.to_string_lossy().to_string())
    }

    fn find_existing_avatar(&self, prefix: &str) -> Option<PathBuf> {
        let entries = fs::read_dir(&self.avatars_dir).ok()?;
        for entry in entries.flatten() {
            let path = entry.path();
            if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                if name.starts_with(prefix) && !name.ends_with(ETAG_SIDECAR_SUFFIX) {
                    if let Ok(meta) = fs::metadata(&path) {
                        if meta.is_file() && meta.len() > 0 {
                            return Some(path);
                        }
                    }
                }
            }
        }
        None
    }

    pub fn delete_cached_avatars(&self, scope: &str, account_id: &str, id: &str) {
        let identity = digest(&format!("{scope}|{account_id}|{id}"));
        let prefix = format!("{identity}-");
        if let Ok(entries) = fs::read_dir(&self.avatars_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    if name.starts_with(&prefix) {
                        fs::remove_file(&path).ok();
                    }
                }
            }
        }
    }

    fn delete_cached_avatars_except(&self, scope: &str, account_id: &str, id: &str, keep: &Path) {
        let identity = digest(&format!("{scope}|{account_id}|{id}"));
        let prefix = format!("{identity}-");
        if let Ok(entries) = fs::read_dir(&self.avatars_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path != keep && path != etag_sidecar_for(keep) {
                    if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                        if name.starts_with(&prefix) {
                            fs::remove_file(&path).ok();
                        }
                    }
                }
            }
        }
    }

    // -- uploads --

    pub async fn upload_user_avatar(&self, endpoint: &str, local_path: &str) -> CloudResult<CloudAvatarResponse> {
        let bytes = fs::read(local_path).map_err(|e| CloudError::Network(format!("Avatar file not found: {e}")))?;
        if bytes.len() > MAX_AVATAR_BYTES {
            return Err(CloudError::Network("Avatar must not exceed 5 MiB".into()));
        }
        let filename = Path::new(local_path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("avatar.jpg");
        let mime = mime_for_path(local_path);
        self.client.upload_avatar(endpoint, filename, bytes, mime).await
    }

    pub async fn delete_user_avatar(&self, endpoint: &str) -> CloudResult<CloudAvatarResponse> {
        self.client.delete_avatar(endpoint).await
    }

    pub async fn upload_agent_avatar(&self, endpoint: &str, local_path: &str) -> CloudResult<CloudAvatarResponse> {
        let bytes = fs::read(local_path).map_err(|e| CloudError::Network(format!("Avatar file not found: {e}")))?;
        if bytes.len() > MAX_AVATAR_BYTES {
            return Err(CloudError::Network("Avatar must not exceed 5 MiB".into()));
        }
        let filename = Path::new(local_path)
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("avatar.jpg");
        let mime = mime_for_path(local_path);
        self.client.upload_avatar(endpoint, filename, bytes, mime).await
    }

    pub async fn delete_agent_avatar(&self, endpoint: &str) -> CloudResult<CloudAvatarResponse> {
        self.client.delete_avatar(endpoint).await
    }
}

pub fn etag_sidecar_for(avatar_path: &Path) -> PathBuf {
    let mut sidecar_name = avatar_path
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_default();
    sidecar_name.push_str(ETAG_SIDECAR_SUFFIX);
    avatar_path.with_file_name(sidecar_name)
}

pub fn read_etag_sidecar(avatar_path: &Path) -> Option<String> {
    let sidecar = etag_sidecar_for(avatar_path);
    if sidecar.exists() {
        fs::read_to_string(sidecar).ok().map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
    } else {
        None
    }
}

pub fn write_etag_sidecar(avatar_path: &Path, etag: Option<&str>) {
    let sidecar = etag_sidecar_for(avatar_path);
    match etag {
        Some(e) if !e.trim().is_empty() => {
            fs::write(sidecar, e.trim()).ok();
        }
        _ => {
            fs::remove_file(sidecar).ok();
        }
    }
}

pub fn avatar_extension(content_type: Option<&str>, url: &str) -> String {
    let mime = content_type.map(|c| c.split(';').next().unwrap_or(c).trim().to_lowercase());
    match mime.as_deref() {
        Some("image/png") => "png".into(),
        Some("image/webp") => "webp".into(),
        Some("image/gif") => "gif".into(),
        Some("image/jpeg") | Some("image/jpg") => "jpg".into(),
        _ => {
            let cleaned = url.split('?').next().unwrap_or(url);
            let ext = cleaned.rsplit('.').next().unwrap_or("img").to_lowercase();
            if ext.len() <= 5 && ext.chars().all(|c| c.is_ascii_alphanumeric()) {
                ext
            } else {
                "img".into()
            }
        }
    }
}

pub fn mime_for_path(path: &str) -> &'static str {
    let ext = Path::new(path)
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_lowercase();
    match ext.as_str() {
        "png" => "image/png",
        "webp" => "image/webp",
        "gif" => "image/gif",
        _ => "image/jpeg",
    }
}

fn digest(input: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(input.as_bytes());
    let output = hasher.finalize();
    output.iter().map(|b| format!("{b:02x}")).collect::<String>()
}

/// The API may build absolute URLs from its internal origin; replace with configured base.
pub fn resolve_avatar_url(url: &str, base_url: &str) -> String {
    if !url.starts_with("http://") && !url.starts_with("https://") {
        return format!("{}/{}", base_url.trim_end_matches('/'), url.trim_start_matches('/'));
    }
    // If it's already an absolute URL, check if host needs substitution
    if let (Ok(parsed_url), Ok(parsed_base)) = (reqwest::Url::parse(url), reqwest::Url::parse(base_url)) {
        let mut new_url = parsed_url.clone();
        if let Some(host) = parsed_base.host_str() {
            let _ = new_url.set_host(Some(host));
        }
        let _ = new_url.set_scheme(parsed_base.scheme());
        let _ = new_url.set_port(parsed_base.port());
        new_url.to_string()
    } else {
        url.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extension_detection() {
        assert_eq!(avatar_extension(Some("image/png; charset=utf-8"), "http://x/a"), "png");
        assert_eq!(avatar_extension(Some("image/jpeg"), "http://x/a"), "jpg");
        assert_eq!(avatar_extension(None, "http://x/photo.webp?v=1"), "webp");
        assert_eq!(avatar_extension(None, "http://x/noext"), "img");
    }

    #[test]
    fn resolve_url_maps_relative_and_absolute() {
        assert_eq!(
            resolve_avatar_url("/avatars/1.jpg", "https://messenger.ptoe.cc"),
            "https://messenger.ptoe.cc/avatars/1.jpg"
        );
        assert_eq!(
            resolve_avatar_url("http://localhost:3000/avatars/1.jpg", "https://messenger.ptoe.cc"),
            "https://messenger.ptoe.cc/avatars/1.jpg"
        );
    }

    #[tokio::test]
    async fn conditional_download_304_reuses_local_file() {
        let server = wiremock::MockServer::start().await;
        let dir = tempfile::tempdir().unwrap();

        // 1. Initial 200 response with ETag
        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .and(wiremock::matchers::path("/avatars/user.png"))
            .respond_with(
                wiremock::ResponseTemplate::new(200)
                    .append_header("ETag", "\"tag-123\"")
                    .append_header("Content-Type", "image/png")
                    .set_body_bytes(vec![1, 2, 3, 4]),
            )
            .mount(&server)
            .await;

        let client = CloudApiClient::new(Default::default());
        let manager = AvatarManager::new(&client, dir.path().to_path_buf());
        let path1 = manager
            .cache_remote_avatar("user", "u1", "u1", "/avatars/user.png", None, &server.uri())
            .await
            .unwrap();
        assert!(Path::new(&path1).exists());
        assert_eq!(fs::read(&path1).unwrap(), vec![1, 2, 3, 4]);

        // 2. Clear previous mocks and mount 304 response when If-None-Match matches
        server.reset().await;
        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .and(wiremock::matchers::path("/avatars/user.png"))
            .and(wiremock::matchers::header("If-None-Match", "\"tag-123\""))
            .respond_with(wiremock::ResponseTemplate::new(304))
            .mount(&server)
            .await;

        let path2 = manager
            .cache_remote_avatar("user", "u1", "u1", "/avatars/user.png", None, &server.uri())
            .await
            .unwrap();
        assert_eq!(path1, path2);
    }
}
