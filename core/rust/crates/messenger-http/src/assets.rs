//! Device-local owned assets. KV stores metadata only; bytes never share chat cache lifetime.
use crate::{ApiError, ApiState};
use axum::{
    extract::{DefaultBodyLimit, Path, State},
    http::{header, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use futures_util::StreamExt;
use messenger_llm::image_generation::{content_hash, validate_image, MAX_IMAGE_BYTES};
use messenger_store::Store;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use std::{
    io::Write,
    net::{IpAddr, SocketAddr},
    path::PathBuf,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::sync::Mutex;

const PREFIX: &str = "local_asset:";
const MAX_TOTAL: u64 = 512 * 1024 * 1024;
pub(crate) fn bad(message: impl Into<String>) -> ApiError {
    ApiError::BadRequest(message.into())
}
pub(crate) fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}
pub(crate) fn load<T: DeserializeOwned>(store: &Store, key: &str) -> Result<Option<T>, ApiError> {
    store
        .kv_get(key)
        .map_err(ApiError::store)?
        .map(|s| serde_json::from_str(&s).map_err(ApiError::internal))
        .transpose()
}
pub(crate) fn save<T: Serialize>(store: &Store, key: &str, value: &T) -> Result<(), ApiError> {
    store
        .kv_set(
            key,
            &serde_json::to_string(value).map_err(ApiError::internal)?,
        )
        .map_err(ApiError::store)
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct AssetReference {
    pub owner_type: String,
    pub owner_id: String,
}
#[derive(Clone, Debug, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ImageProvenance {
    pub prompt: String,
    pub model_id: String,
    pub parameters: serde_json::Map<String, serde_json::Value>,
    pub revised_prompt: Option<String>,
    pub created_at: i64,
    pub completed_at: i64,
    pub usage: Option<serde_json::Value>,
}
/// Usage provenance contains counters only, never provider-returned strings or credentials.
pub(crate) fn numeric_usage(value: &serde_json::Value) -> Option<serde_json::Value> {
    fn bounded(value: &serde_json::Value, depth: usize) -> Option<serde_json::Value> {
        if depth > 4 {
            return None;
        }
        match value {
            serde_json::Value::Number(n) => Some(serde_json::Value::Number(n.clone())),
            serde_json::Value::Object(map) if map.len() <= 64 => Some(serde_json::Value::Object(
                map.iter()
                    .filter_map(|(key, value)| {
                        if key.len() > 64
                            || !key.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
                        {
                            return None;
                        }
                        bounded(value, depth + 1).map(|value| (key.clone(), value))
                    })
                    .collect(),
            )),
            _ => None,
        }
    }
    bounded(value, 0)
}
pub(crate) fn validate_provenance(value: &ImageProvenance) -> Result<(), ApiError> {
    if value.prompt.is_empty()
        || value.prompt.len() > 32000
        || value.model_id.is_empty()
        || value.model_id.len() > 256
        || value
            .revised_prompt
            .as_ref()
            .is_some_and(|p| p.len() > 32000)
        || value.created_at < 0
        || value.completed_at < value.created_at
    {
        return Err(bad("invalid generated image provenance"));
    }
    if value.usage.as_ref().is_some_and(|usage| {
        numeric_usage(usage).as_ref() != Some(usage) || usage.to_string().len() > 8192
    }) {
        return Err(bad(
            "image usage provenance must contain bounded numeric counters only",
        ));
    }
    let supported = value.parameters.keys().cloned().collect();
    crate::image_generation::validate_parameters(&value.parameters, &supported)
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Asset {
    pub id: String,
    pub sha256: String,
    pub mime_type: String,
    pub size: u64,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub created_at: i64,
    pub references: Vec<AssetReference>,
    #[serde(default)]
    pub unreferenced_at: Option<i64>,
}
pub(crate) struct AssetService {
    store: Arc<Store>,
    root: PathBuf,
    ephemeral: bool,
    pub(crate) gate: Mutex<()>,
}
impl Drop for AssetService {
    fn drop(&mut self) {
        if self.ephemeral {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }
}
impl AssetService {
    pub(crate) fn new(store: Arc<Store>, root: PathBuf) -> Result<Self, ApiError> {
        std::fs::create_dir_all(&root).map_err(ApiError::internal)?;
        let root = root.canonicalize().map_err(ApiError::internal)?;
        // NamedTempFile removes failed staged writes; clean remnants after process death.
        for entry in std::fs::read_dir(&root).map_err(ApiError::internal)? {
            let entry = entry.map_err(ApiError::internal)?;
            if entry.file_name().to_string_lossy().starts_with(".stage-")
                && entry.file_type().map_err(ApiError::internal)?.is_file()
            {
                std::fs::remove_file(entry.path()).map_err(ApiError::internal)?;
            }
        }
        let ephemeral = store.data_dir().is_none();
        Ok(Self {
            store,
            root,
            ephemeral,
            gate: Mutex::new(()),
        })
    }
    pub(crate) async fn clear(&self) -> Result<(), ApiError> {
        let _guard = self.gate.lock().await;
        for prefix in [PREFIX, "provenance:"] {
            for (key, _) in self.store.kv_list_prefix(prefix).map_err(ApiError::store)? {
                self.store.kv_delete(&key).map_err(ApiError::store)?;
            }
        }
        for entry in std::fs::read_dir(&self.root).map_err(ApiError::internal)? {
            let entry = entry.map_err(ApiError::internal)?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if valid_id(&name).is_ok() || name.starts_with(".stage-") {
                std::fs::remove_file(entry.path()).map_err(ApiError::internal)?;
            }
        }
        Ok(())
    }
    pub(crate) fn provenance(&self, id: &str) -> Result<Vec<ImageProvenance>, ApiError> {
        self.get(id)?;
        Ok(load(&self.store, &format!("provenance:{id}"))?.unwrap_or_default())
    }
    pub(crate) async fn record_provenance(
        &self,
        id: &str,
        provenance: ImageProvenance,
    ) -> Result<(), ApiError> {
        let _guard = self.gate.lock().await;
        validate_provenance(&provenance)?;
        let mut entries = self.provenance(id)?;
        if !entries.contains(&provenance) {
            if entries.len() >= 128 {
                return Err(bad("asset provenance limit exceeded"));
            }
            entries.push(provenance);
            save(&self.store, &format!("provenance:{id}"), &entries)?;
        }
        Ok(())
    }
    pub(crate) fn get(&self, id: &str) -> Result<Asset, ApiError> {
        valid_id(id)?;
        load(&self.store, &format!("{PREFIX}{id}"))?.ok_or_else(|| ApiError::not_found("asset"))
    }
    pub(crate) fn manifest(&self) -> Result<Vec<Asset>, ApiError> {
        self.store
            .kv_list_prefix(PREFIX)
            .map_err(ApiError::store)?
            .into_iter()
            .map(|(_, s)| serde_json::from_str(&s).map_err(ApiError::internal))
            .collect()
    }
    /// Parent artifact service may ingest UTF-8/binary resources without exposing local paths.
    pub(crate) async fn import_bytes(
        &self,
        bytes: &[u8],
        mime: &str,
        owner: AssetReference,
    ) -> Result<Asset, ApiError> {
        let _guard = self.gate.lock().await;
        validate_owner(&owner)?;
        if bytes.is_empty() || bytes.len() > MAX_IMAGE_BYTES {
            return Err(bad("asset must be 1 byte..20 MiB"));
        }
        if !valid_mime(mime) {
            return Err(bad("invalid MIME type"));
        }
        let (mime, width, height) = if mime.starts_with("image/") {
            let (detected, w, h) = validate_image(bytes).map_err(|e| bad(e.to_string()))?;
            if detected != mime {
                return Err(bad("MIME does not match decoded image"));
            }
            (detected, Some(w), Some(h))
        } else {
            (mime.to_string(), None, None)
        };
        let id = content_hash(bytes);
        let key = format!("{PREFIX}{id}");
        if let Some(mut asset) = load::<Asset>(&self.store, &key)? {
            // Repair missing bytes from an interrupted metadata/file lifecycle, verify existing files.
            if self.root.join(&id).exists() {
                self.read_bytes(&id)?;
            } else {
                self.write_atomic(&id, bytes)?;
            }
            if asset.mime_type != mime {
                return Err(bad("existing content has a different MIME declaration"));
            }
            if !asset.references.contains(&owner) {
                if asset.references.len() >= 10000 {
                    return Err(bad("asset reference limit exceeded"));
                }
                asset.references.push(owner);
                asset.unreferenced_at = None;
                save(&self.store, &key, &asset)?;
            }
            return Ok(asset);
        }
        let manifest = self.manifest()?;
        if manifest.len() >= 10000
            || manifest.iter().map(|a| a.size).sum::<u64>() + bytes.len() as u64 > MAX_TOTAL
        {
            return Err(bad("asset storage quota exceeded"));
        }
        self.write_atomic(&id, bytes)?;
        let asset = Asset {
            id: id.clone(),
            sha256: id,
            mime_type: mime,
            size: bytes.len() as u64,
            width,
            height,
            created_at: now(),
            references: vec![owner],
            unreferenced_at: None,
        };
        save(&self.store, &key, &asset)?;
        Ok(asset)
    }
    /// Caller holds gate. Stage package bytes without publishing any KV metadata.
    pub(crate) fn stage_package_bytes(&self, id: &str, bytes: &[u8]) -> Result<bool, ApiError> {
        valid_id(id)?;
        if content_hash(bytes) != id || bytes.len() > MAX_IMAGE_BYTES {
            return Err(bad("package asset hash/size invalid"));
        }
        let path = self.root.join(id);
        if path.exists() {
            let meta = std::fs::symlink_metadata(&path).map_err(ApiError::internal)?;
            if !meta.is_file()
                || meta.file_type().is_symlink()
                || meta.len() != bytes.len() as u64
                || std::fs::read(&path).map_err(ApiError::internal)? != bytes
            {
                return Err(bad("existing asset file integrity failure"));
            }
            return Ok(false);
        }
        self.write_atomic(id, bytes)?;
        Ok(true)
    }
    pub(crate) fn rollback_package_file(&self, id: &str) {
        if valid_id(id).is_ok() {
            let _ = std::fs::remove_file(self.root.join(id));
        }
    }
    fn write_atomic(&self, id: &str, bytes: &[u8]) -> Result<(), ApiError> {
        let mut disk_bytes = 0u64;
        for entry in std::fs::read_dir(&self.root).map_err(ApiError::internal)? {
            let entry = entry.map_err(ApiError::internal)?;
            if valid_id(&entry.file_name().to_string_lossy()).is_ok() {
                let metadata = entry.metadata().map_err(ApiError::internal)?;
                if metadata.is_file() {
                    disk_bytes = disk_bytes.saturating_add(metadata.len());
                }
            }
        }
        let additional = if self.root.join(id).exists() {
            0
        } else {
            bytes.len() as u64
        };
        if disk_bytes.saturating_add(additional) > MAX_TOTAL {
            return Err(bad(
                "asset file storage quota exceeded; collect old orphan files before importing",
            ));
        }
        let mut stage = tempfile::Builder::new()
            .prefix(".stage-")
            .tempfile_in(&self.root)
            .map_err(ApiError::internal)?;
        stage.write_all(bytes).map_err(ApiError::internal)?;
        stage.as_file().sync_all().map_err(ApiError::internal)?;
        stage
            .persist(self.root.join(id))
            .map_err(ApiError::internal)?;
        Ok(())
    }
    pub(crate) fn read_bytes(&self, id: &str) -> Result<Vec<u8>, ApiError> {
        let asset = self.get(id)?;
        let path = self.root.join(id);
        let meta = std::fs::symlink_metadata(&path).map_err(ApiError::internal)?;
        if !meta.is_file()
            || meta.file_type().is_symlink()
            || meta.len() != asset.size
            || meta.len() > MAX_IMAGE_BYTES as u64
        {
            return Err(bad("asset file integrity failure"));
        }
        let bytes = std::fs::read(path).map_err(ApiError::internal)?;
        if content_hash(&bytes) != asset.sha256 {
            return Err(bad("asset hash mismatch"));
        }
        Ok(bytes)
    }
    pub(crate) async fn unreference(
        &self,
        id: &str,
        owner: &AssetReference,
    ) -> Result<Asset, ApiError> {
        let _guard = self.gate.lock().await;
        validate_owner(owner)?;
        let mut asset = self.get(id)?;
        let previous = asset.references.len();
        asset.references.retain(|r| r != owner);
        if asset.references.len() != previous {
            if asset.references.is_empty() {
                asset.unreferenced_at = Some(now());
            }
            save(&self.store, &format!("{PREFIX}{id}"), &asset)?;
        }
        Ok(asset)
    }
    /// Explicit GC only; never delete referenced assets. A full day protects in-flight work.
    pub(crate) async fn gc(&self, timestamp: i64) -> Result<usize, ApiError> {
        let _guard = self.gate.lock().await;
        let mut protected = std::collections::BTreeSet::new();
        let learning = self.store.export_learning().map_err(ApiError::store)?;
        for record in learning.records.iter().chain(&learning.revisions) {
            if let Some(source) = &record.source {
                protected.extend(source.asset_ids.iter().cloned());
            }
            if let messenger_store::learning::LearningContent::Material {
                asset_id: Some(id), ..
            } = &record.content
            {
                protected.insert(id.clone());
            }
        }
        for (_, value) in self
            .store
            .kv_list_prefix("artifact:")
            .map_err(ApiError::store)?
        {
            let artifact: crate::artifacts::Artifact =
                serde_json::from_str(&value).map_err(ApiError::internal)?;
            for revision in artifact.revisions {
                protected.extend(revision.assets.into_values());
            }
        }
        for (_, value) in self
            .store
            .kv_list_prefix("local_image_job:")
            .map_err(ApiError::store)?
        {
            let job: crate::image_generation::ImageGenerationJob =
                serde_json::from_str(&value).map_err(ApiError::internal)?;
            protected.extend(job.asset_ids);
        }
        let mut removed = 0;
        for asset in self.manifest()? {
            if asset.references.is_empty()
                && !protected.contains(&asset.id)
                && asset
                    .unreferenced_at
                    .is_some_and(|t| timestamp.saturating_sub(t) >= 86_400_000)
            {
                let path = self.root.join(&asset.id);
                if path.exists() {
                    std::fs::remove_file(path).map_err(ApiError::internal)?;
                }
                self.store
                    .kv_delete(&format!("{PREFIX}{}", asset.id))
                    .map_err(ApiError::store)?;
                self.store
                    .kv_delete(&format!("provenance:{}", asset.id))
                    .map_err(ApiError::store)?;
                removed += 1;
            }
        }
        let known: std::collections::BTreeSet<_> =
            self.manifest()?.into_iter().map(|a| a.id).collect();
        for entry in std::fs::read_dir(&self.root).map_err(ApiError::internal)? {
            let entry = entry.map_err(ApiError::internal)?;
            let name = entry.file_name().to_string_lossy().into_owned();
            if valid_id(&name).is_ok() && !known.contains(&name) {
                let meta = entry.metadata().map_err(ApiError::internal)?;
                if meta.is_file()
                    && meta
                        .modified()
                        .ok()
                        .and_then(|t| t.elapsed().ok())
                        .is_some_and(|age| age >= Duration::from_secs(86400))
                {
                    std::fs::remove_file(entry.path()).map_err(ApiError::internal)?;
                    removed += 1;
                }
            }
        }
        Ok(removed)
    }
    pub(crate) async fn reference(
        &self,
        id: &str,
        owner: AssetReference,
    ) -> Result<Asset, ApiError> {
        let _guard = self.gate.lock().await;
        validate_owner(&owner)?;
        let mut asset = self.get(id)?;
        if !asset.references.contains(&owner) {
            if asset.references.len() >= 10000 {
                return Err(bad("asset reference limit exceeded"));
            }
            asset.references.push(owner);
            asset.unreferenced_at = None;
            save(&self.store, &format!("{PREFIX}{id}"), &asset)?;
        }
        Ok(asset)
    }
}
pub(crate) fn valid_mime(mime: &str) -> bool {
    let mut parts = mime.split('/');
    mime.len() <= 128
        && mime == mime.to_ascii_lowercase()
        && parts.next().is_some_and(|s| !s.is_empty())
        && parts.next().is_some_and(|s| !s.is_empty())
        && parts.next().is_none()
        && mime
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b"/!#$&^_.+-".contains(&c))
}
fn valid_id(id: &str) -> Result<(), ApiError> {
    if id.len() != 64
        || !id
            .bytes()
            .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
    {
        return Err(bad("invalid asset ID"));
    }
    Ok(())
}
fn validate_owner(owner: &AssetReference) -> Result<(), ApiError> {
    for value in [&owner.owner_type, &owner.owner_id] {
        if value.is_empty() || value.len() > 256 || value.chars().any(char::is_control) {
            return Err(bad("bounded owner type and ID required"));
        }
    }
    Ok(())
}
/// Public HTTPS only. Resolve once per redirect and pin reqwest to that validated address.
pub(crate) async fn download(url: &str) -> Result<(Vec<u8>, String), ApiError> {
    tokio::time::timeout(Duration::from_secs(60), download_inner(url))
        .await
        .map_err(|_| bad("asset download timeout"))?
}
async fn download_inner(source: &str) -> Result<(Vec<u8>, String), ApiError> {
    if source.len() > 8192 {
        return Err(bad("asset URL exceeds limit"));
    }
    let mut url = reqwest::Url::parse(source).map_err(|e| bad(e.to_string()))?;
    for hop in 0..=3 {
        if url.scheme() != "https"
            || !url.username().is_empty()
            || url.password().is_some()
            || url.port_or_known_default() != Some(443)
        {
            return Err(bad("asset download requires public HTTPS on port 443"));
        }
        let host = url
            .host_str()
            .ok_or_else(|| bad("missing download host"))?
            .to_owned();
        let addresses: Vec<SocketAddr> = tokio::net::lookup_host((host.as_str(), 443))
            .await
            .map_err(|e| bad(e.to_string()))?
            .collect();
        if addresses.is_empty() || addresses.iter().any(|a| !public_ip(a.ip())) {
            return Err(bad("asset URL resolves to a non-public address"));
        }
        let client = reqwest::Client::builder()
            .no_proxy()
            .https_only(true)
            .redirect(reqwest::redirect::Policy::none())
            .resolve_to_addrs(&host, &addresses)
            .timeout(Duration::from_secs(30))
            .build()
            .map_err(ApiError::internal)?;
        let response = client
            .get(url.clone())
            .send()
            .await
            .map_err(|e| bad(e.to_string()))?;
        if response.status().is_redirection() {
            if hop == 3 {
                return Err(bad("asset redirect limit exceeded"));
            }
            let location = response
                .headers()
                .get(header::LOCATION)
                .and_then(|v| v.to_str().ok())
                .ok_or_else(|| bad("invalid redirect"))?;
            url = url.join(location).map_err(|e| bad(e.to_string()))?;
            continue;
        }
        if !response.status().is_success() {
            return Err(bad(format!("asset download HTTP {}", response.status())));
        }
        if response
            .content_length()
            .is_some_and(|n| n > MAX_IMAGE_BYTES as u64)
        {
            return Err(bad("asset download exceeds byte limit"));
        }
        let mime = response
            .headers()
            .get(header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("application/octet-stream")
            .split(';')
            .next()
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase();
        let mut stream = response.bytes_stream();
        let mut bytes = Vec::new();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|e| bad(e.to_string()))?;
            if bytes.len() + chunk.len() > MAX_IMAGE_BYTES {
                return Err(bad("asset download exceeds byte limit"));
            }
            bytes.extend_from_slice(&chunk);
        }
        return Ok((bytes, mime));
    }
    Err(bad("asset redirect limit exceeded"))
}
fn public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => {
            let [a, b, c, _] = ip.octets();
            !(ip.is_private()
                || ip.is_loopback()
                || ip.is_link_local()
                || ip.is_broadcast()
                || ip.is_documentation()
                || ip.is_unspecified()
                || ip.is_multicast()
                || a == 0
                || a >= 240
                || (a == 100 && (64..=127).contains(&b))
                || (a == 198 && (b == 18 || b == 19))
                || (a == 192 && b == 0 && c == 0))
        }
        IpAddr::V6(ip) => {
            let s = ip.segments();
            s[0] & 0xe000 == 0x2000
                && s[0] != 0x2001
                && s[0] != 0x2002
                && !(s[0] == 0x3fff && s[1] < 0x1000)
        }
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ImportRequest {
    data_base64: String,
    mime_type: String,
    owner: AssetReference,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct DownloadRequest {
    url: String,
    owner: AssetReference,
}
async fn import(
    State(state): State<ApiState>,
    Json(body): Json<ImportRequest>,
) -> Result<Json<Asset>, ApiError> {
    if body.data_base64.len() > MAX_IMAGE_BYTES.div_ceil(3) * 4 {
        return Err(bad("base64 exceeds limit"));
    }
    let bytes = STANDARD
        .decode(body.data_base64)
        .map_err(|e| bad(e.to_string()))?;
    Ok(Json(
        state
            .assets
            .import_bytes(&bytes, &body.mime_type, body.owner)
            .await?,
    ))
}
async fn download_asset(
    State(state): State<ApiState>,
    Json(body): Json<DownloadRequest>,
) -> Result<Json<Asset>, ApiError> {
    validate_owner(&body.owner)?;
    let (bytes, mime) = download(&body.url).await?;
    Ok(Json(
        state.assets.import_bytes(&bytes, &mime, body.owner).await?,
    ))
}
async fn provenance(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<Vec<ImageProvenance>>, ApiError> {
    Ok(Json(state.assets.provenance(&id)?))
}
async fn get_asset(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<Asset>, ApiError> {
    Ok(Json(state.assets.get(&id)?))
}
async fn manifest(State(state): State<ApiState>) -> Result<Json<Vec<Asset>>, ApiError> {
    Ok(Json(state.assets.manifest()?))
}
async fn reference(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(owner): Json<AssetReference>,
) -> Result<Json<Asset>, ApiError> {
    Ok(Json(state.assets.reference(&id, owner).await?))
}
async fn unreference(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(owner): Json<AssetReference>,
) -> Result<Json<Asset>, ApiError> {
    let _guard = state.agent_mutations.lock().await;
    let still_owned = match owner.owner_type.as_str() {
        "artifact" => crate::artifacts::load(&state.store, &owner.owner_id)
            .map(|a| {
                a.revisions
                    .iter()
                    .any(|r| r.assets.values().any(|value| value == &id))
            })
            .unwrap_or(false),
        "learning" => {
            let package = state.store.export_learning().map_err(ApiError::store)?;
            package.records.iter().chain(&package.revisions).any(|record| record.id == owner.owner_id && (matches!(&record.content, messenger_store::learning::LearningContent::Material { asset_id: Some(value), .. } if value == &id) || record.source.as_ref().is_some_and(|source| source.asset_ids.contains(&id))))
        }
        "imageGenerationJob" => state
            .images
            .get(&owner.owner_id)
            .map(|j| {
                matches!(
                    j.status,
                    crate::image_generation::JobStatus::Queued
                        | crate::image_generation::JobStatus::Running
                ) || j.asset_ids.contains(&id)
            })
            .unwrap_or(false),
        _ => false,
    };
    if still_owned {
        return Err(bad(
            "remove the owning resource's asset binding before releasing its reference",
        ));
    }
    Ok(Json(state.assets.unreference(&id, &owner).await?))
}
async fn gc(State(state): State<ApiState>) -> Result<Json<serde_json::Value>, ApiError> {
    let _guard = state.agent_mutations.lock().await;
    Ok(Json(
        serde_json::json!({"removed":state.assets.gc(now()).await?}),
    ))
}
async fn content(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Response, ApiError> {
    let asset = state.assets.get(&id)?;
    let bytes = state.assets.read_bytes(&id)?;
    Ok((
        StatusCode::OK,
        [
            (header::CONTENT_TYPE, asset.mime_type),
            (header::CONTENT_DISPOSITION, "attachment".into()),
            (header::X_CONTENT_TYPE_OPTIONS, "nosniff".into()),
            (header::CACHE_CONTROL, "private, no-store".into()),
        ],
        bytes,
    )
        .into_response())
}
pub(crate) fn routes() -> Router<ApiState> {
    Router::new()
        .route("/assets", get(manifest))
        .route("/assets/import", post(import))
        .route("/assets/download", post(download_asset))
        .route("/assets/gc", post(gc))
        .route("/assets/:id", get(get_asset))
        .route("/assets/:id/content", get(content))
        .route("/assets/:id/provenance", get(provenance))
        .route("/assets/:id/references", post(reference))
        .route("/assets/:id/unreference", post(unreference))
        .layer(DefaultBodyLimit::max(29 * 1024 * 1024))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn provenance_rejects_provider_fields_and_keeps_numeric_usage_only() {
        let mut source = serde_json::json!({"prompt":"A tree","modelId":"images","parameters":{},"revisedPrompt":null,"createdAt":1,"completedAt":2,"usage":{"images":1}});
        let provenance: ImageProvenance = serde_json::from_value(source.clone()).unwrap();
        assert!(validate_provenance(&provenance).is_ok());
        source["providerId"] = serde_json::json!("must-not-import");
        assert!(serde_json::from_value::<ImageProvenance>(source).is_err());
        let usage = serde_json::json!({"images":1,"api_key":"secret","details":{"tokens":2,"url":"https://provider.example"}});
        assert_eq!(
            numeric_usage(&usage).unwrap(),
            serde_json::json!({"images":1,"details":{"tokens":2}})
        );
    }
    #[test]
    fn blocks_private_special_and_mapped_addresses() {
        assert!(valid_mime("image/png"));
        for mime in [
            "IMAGE/PNG",
            "image/svg+xml\r\n",
            "/png",
            "image/",
            "image/png/extra",
        ] {
            assert!(!valid_mime(mime));
        }
        for address in [
            "127.0.0.1",
            "10.2.3.4",
            "169.254.169.254",
            "100.64.0.1",
            "198.19.0.1",
            "::1",
            "::ffff:8.8.8.8",
            "2001:db8::1",
            "fc00::1",
        ] {
            assert!(!public_ip(address.parse().unwrap()), "{address}");
        }
        assert!(public_ip("8.8.8.8".parse().unwrap()));
        assert!(public_ip("2606:4700:4700::1111".parse().unwrap()));
    }
    #[tokio::test]
    async fn gc_keeps_owned_bytes_and_observes_release_grace_period() {
        let dir = tempfile::tempdir().unwrap();
        let store = Arc::new(Store::open(&dir.path().join("store.db")).unwrap());
        let assets = AssetService::new(store, dir.path().join("assets")).unwrap();
        let owner = AssetReference {
            owner_type: "material".into(),
            owner_id: "one".into(),
        };
        let asset = assets
            .import_bytes(
                b"keep until released",
                "application/octet-stream",
                owner.clone(),
            )
            .await
            .unwrap();
        assert_eq!(assets.gc(now() + 172_800_000).await.unwrap(), 0);
        let released = assets.unreference(&asset.id, &owner).await.unwrap();
        let released_at = released.unreferenced_at.unwrap();
        assert_eq!(assets.gc(released_at + 86_399_999).await.unwrap(), 0);
        assert_eq!(assets.gc(released_at + 86_400_000).await.unwrap(), 1);
        assert!(assets.get(&asset.id).is_err());
    }
    #[tokio::test]
    async fn owns_deduplicated_bytes_and_reopens_without_chat() {
        let dir = tempfile::tempdir().unwrap();
        let store = Arc::new(Store::open(&dir.path().join("store.db")).unwrap());
        let assets = AssetService::new(store.clone(), dir.path().join("assets")).unwrap();
        let owner = AssetReference {
            owner_type: "artifact".into(),
            owner_id: "one".into(),
        };
        let asset = assets
            .import_bytes(b"const x = 1;", "application/javascript", owner.clone())
            .await
            .unwrap();
        let same = assets
            .import_bytes(b"const x = 1;", "application/javascript", owner)
            .await
            .unwrap();
        assert_eq!(asset.id, same.id);
        assert_eq!(same.references.len(), 1);
        drop(assets);
        let assets = AssetService::new(store, dir.path().join("assets")).unwrap();
        assert_eq!(assets.read_bytes(&asset.id).unwrap(), b"const x = 1;");
        assert!(assets.get("../store.db").is_err());
        std::fs::write(dir.path().join("assets").join(&asset.id), b"tampered").unwrap();
        assert!(assets.read_bytes(&asset.id).is_err());
    }
}
