//! Pull-side image rehydration (port of `rehydrateChatImages` /
//! `persistDataUri`): image parts embedded in `partsJson` carry the source
//! device's private file path, which does not exist on this device. Before
//! writing pulled messages, missing files are decoded from the base64
//! `dataUri` into `chat_images/` and the `localPath` rewritten —
//! deterministic filenames make repeated syncs idempotent.

use std::path::{Path, PathBuf};

use base64::Engine;
use sha2::{Digest, Sha256};

/// Rewrite `partsJson` for one pulled message, rehydrating any image part
/// whose `localPath` is missing on this device. Returns `None` when nothing
/// changed (pure text, files still valid, or unparsable JSON).
pub fn rehydrate_parts_json(
    message_id: &str,
    parts_json: Option<&str>,
    chat_images_dir: &Path,
) -> Option<String> {
    let parts_json = parts_json.filter(|s| !s.trim().is_empty())?;
    let Ok(array) = serde_json::from_str::<Vec<serde_json::Value>>(parts_json) else {
        return None;
    };
    let mut changed = false;
    let rewritten: Vec<serde_json::Value> = array
        .into_iter()
        .enumerate()
        .map(|(index, mut part)| {
            let Some(obj) = part.as_object_mut() else {
                return part;
            };
            if obj.get("type").and_then(|v| v.as_str()) != Some("image") {
                return part;
            }
            if let Some(local_path) = obj.get("localPath").and_then(|v| v.as_str()) {
                if usable_file(local_path) {
                    return part;
                }
            }
            let Some(data_uri) = obj.get("dataUri").and_then(|v| v.as_str()) else {
                return part;
            };
            match persist_data_uri(message_id, index, data_uri, chat_images_dir) {
                Some(restored) => {
                    changed = true;
                    obj.insert("localPath".into(), serde_json::Value::String(restored));
                    part
                }
                None => part,
            }
        })
        .collect();
    if changed {
        Some(serde_json::to_string(&rewritten).unwrap_or_else(|_| parts_json.to_string()))
    } else {
        None
    }
}

/// Decode one `data:` URI into `chat_images/sync_{digest}.{ext}`; the name
/// derives from messageId|partIndex|dataUri so repeated syncs hit the same
/// file and per-message files preserve the delete-reaping convention.
pub fn persist_data_uri(
    message_id: &str,
    part_index: usize,
    data_uri: &str,
    chat_images_dir: &Path,
) -> Option<String> {
    let comma = data_uri.find(',')?;
    if !data_uri.starts_with("data:") || comma <= 5 {
        return None;
    }
    let header = data_uri[5..comma].to_lowercase();
    if !header.contains(";base64") {
        return None;
    }
    let extension = match header.split(';').next()?.trim() {
        "image/jpeg" | "image/jpg" => "jpg",
        "image/webp" => "webp",
        "image/gif" => "gif",
        _ => "png",
    };
    std::fs::create_dir_all(chat_images_dir).ok()?;
    let target = chat_images_dir.join(format!(
        "sync_{}.{}",
        digest(&format!("{message_id}|{part_index}|{data_uri}")),
        extension
    ));
    if usable_file(&target.to_string_lossy()) {
        return Some(target.to_string_lossy().into_owned());
    }
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(&data_uri[comma + 1..])
        .ok()?;
    if bytes.is_empty() {
        return None;
    }
    std::fs::write(&target, &bytes).ok()?;
    Some(target.to_string_lossy().into_owned())
}

fn usable_file(path: &str) -> bool {
    match std::fs::metadata(path) {
        Ok(metadata) => metadata.is_file() && metadata.len() > 0,
        Err(_) => false,
    }
}

fn digest(input: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(input.as_bytes());
    let output = hasher.finalize();
    output.iter().map(|b| format!("{b:02x}")).collect::<String>()
}

/// Directory convention shared with the platform ChatImageStore.
pub fn chat_images_dir(files_dir: &Path) -> PathBuf {
    files_dir.join("chat_images")
}

#[cfg(test)]
mod tests {
    use super::*;

    // 1×1 red PNG, base64-encoded.
    const TINY_PNG_BASE64: &str = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    #[test]
    fn rewrites_missing_local_paths_and_is_idempotent() {
        let dir = tempfile::tempdir().unwrap();
        let parts = format!(
            r#"[{{"type":"image","dataUri":"data:image/png;base64,{TINY_PNG_BASE64}","localPath":"/other/device/a.png"}}]"#
        );
        let rewritten =
            rehydrate_parts_json("msg1", Some(&parts), &chat_images_dir(dir.path())).expect("must rewrite");
        let parsed: Vec<serde_json::Value> = serde_json::from_str(&rewritten).unwrap();
        let restored = parsed[0]["localPath"].as_str().unwrap();
        assert!(restored.contains("chat_images"));
        assert!(restored.contains("sync_"));
        assert!(Path::new(restored).exists());

        // Second run over the ORIGINAL payload lands on the same file.
        let again = rehydrate_parts_json("msg1", Some(&parts), &chat_images_dir(dir.path())).unwrap();
        let parsed_again: Vec<serde_json::Value> = serde_json::from_str(&again).unwrap();
        assert_eq!(parsed_again[0]["localPath"], parsed[0]["localPath"]);

        // Third run over the REWRITTEN payload (file now valid) → no change.
        assert!(rehydrate_parts_json("msg1", Some(&rewritten), &chat_images_dir(dir.path())).is_none());
    }

    #[test]
    fn text_parts_and_bad_payloads_are_untouched() {
        let dir = tempfile::tempdir().unwrap();
        let text = r#"[{"type":"text","text":"hello"}]"#;
        assert!(rehydrate_parts_json("m", Some(text), &chat_images_dir(dir.path())).is_none());
        assert!(rehydrate_parts_json("m", None, &chat_images_dir(dir.path())).is_none());
        assert!(rehydrate_parts_json("m", Some("not json"), &chat_images_dir(dir.path())).is_none());
        // Non-base64 data URI → part left as-is, no rewrite.
        let bad = r#"[{"type":"image","dataUri":"data:image/png;base64,%%%","localPath":"/gone.png"}]"#;
        assert!(rehydrate_parts_json("m", Some(bad), &chat_images_dir(dir.path())).is_none());
    }

    #[test]
    fn extension_follows_mime_type() {
        let dir = tempfile::tempdir().unwrap();
        let jpeg = format!(
            r#"[{{"type":"image","dataUri":"data:image/jpeg;base64,{TINY_PNG_BASE64}","localPath":""}}]"#
        );
        let rewritten = rehydrate_parts_json("m2", Some(&jpeg), &chat_images_dir(dir.path())).unwrap();
        assert!(rewritten.contains("sync_") && rewritten.contains(".jpg"));
    }
}
