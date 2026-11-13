//! Portable learning bundle: validated metadata + owned binary resources, no credentials or grants.
use crate::{
    artifacts::{self, Artifact, Submission},
    assets::{self, Asset, AssetReference},
    ApiError, ApiState,
};
use axum::{
    extract::{DefaultBodyLimit, State},
    routing::{get, post},
    Json, Router,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use messenger_llm::image_generation::{content_hash, validate_image, MAX_IMAGE_BYTES};
use messenger_store::learning::{LearningContent, LearningPackage};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};

const MAX_JSON: usize = 96 * 1024 * 1024;
const MAX_ASSETS: usize = 64 * 1024 * 1024;
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct BundledAsset {
    metadata: Asset,
    data_base64: String,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct LearningBundle {
    format_version: u32,
    learning: LearningPackage,
    assets: Vec<BundledAsset>,
    artifacts: Vec<Artifact>,
    #[serde(default)]
    generation_provenance: BTreeMap<String, Vec<assets::ImageProvenance>>,
}
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct BundlePreview {
    format_version: u32,
    records: usize,
    revisions: usize,
    attempts: usize,
    asset_count: usize,
    asset_bytes: usize,
    artifact_count: usize,
    external_resources_included: bool,
    warnings: Vec<String>,
}
struct Prepared {
    entries: Vec<(String, String)>,
    bytes: Vec<(String, Vec<u8>)>,
    preview: BundlePreview,
}
fn bad(message: impl Into<String>) -> ApiError {
    assets::bad(message)
}
fn learning_error(error: impl std::fmt::Display) -> ApiError {
    bad(error.to_string())
}

pub(crate) fn routes() -> Router<ApiState> {
    Router::new()
        .route("/learning/package/export", get(export))
        .route("/learning/package/preview", post(preview))
        .route("/learning/package/import", post(import))
        .layer(DefaultBodyLimit::max(MAX_JSON))
}
async fn export(State(state): State<ApiState>) -> Result<Json<LearningBundle>, ApiError> {
    let _domain_guard = state.agent_mutations.lock().await;
    let _asset_guard = state.assets.gate.lock().await;
    let learning = state.store.export_learning().map_err(learning_error)?;
    let mut artifacts = Vec::new();
    for (_, encoded) in state
        .store
        .kv_list_prefix("artifact:")
        .map_err(ApiError::store)?
    {
        let mut artifact: Artifact = serde_json::from_str(&encoded).map_err(ApiError::internal)?;
        artifact.approved_domains.clear(); // Execution/network grants are always device-local.
        artifact.trusted_on_device = false;
        artifacts.push(artifact);
    }
    let mut asset_bytes = 0usize;
    let mut assets = Vec::new();
    let mut generation_provenance = BTreeMap::new();
    for metadata in state.assets.manifest()? {
        let provenance = state.assets.provenance(&metadata.id)?;
        if !provenance.is_empty() {
            generation_provenance.insert(metadata.id.clone(), provenance);
        }
        asset_bytes = asset_bytes
            .checked_add(metadata.size as usize)
            .ok_or_else(|| bad("bundle byte limit exceeded"))?;
        if asset_bytes > MAX_ASSETS {
            return Err(bad("learning package exceeds 64 MiB of asset bytes"));
        }
        let bytes = state.assets.read_bytes(&metadata.id)?;
        assets.push(BundledAsset {
            metadata,
            data_base64: STANDARD.encode(bytes),
        });
    }
    let bundle = LearningBundle {
        format_version: 1,
        learning,
        assets,
        artifacts,
        generation_provenance,
    };
    if serde_json::to_vec(&bundle)
        .map_err(ApiError::internal)?
        .len()
        > MAX_JSON
    {
        return Err(bad("learning package exceeds 96 MiB JSON limit"));
    }
    // Export verifies resource completeness, independently from destination-ID conflict checks.
    validate_references(&bundle)?;
    Ok(Json(bundle))
}
fn validate_references(bundle: &LearningBundle) -> Result<(), ApiError> {
    let asset_ids: BTreeSet<&str> = bundle
        .assets
        .iter()
        .map(|a| a.metadata.id.as_str())
        .collect();
    let artifact_ids: BTreeSet<&str> = bundle.artifacts.iter().map(|a| a.id.as_str()).collect();
    for record in bundle
        .learning
        .records
        .iter()
        .chain(&bundle.learning.revisions)
    {
        if record.source.as_ref().is_some_and(|source| {
            source
                .asset_ids
                .iter()
                .any(|id| !asset_ids.contains(id.as_str()))
        }) {
            return Err(bad(format!(
                "learning source {} references an asset missing from this package",
                record.id
            )));
        }
        if let LearningContent::Material {
            asset_id,
            artifact_id,
            ..
        } = &record.content
        {
            if asset_id
                .as_ref()
                .is_some_and(|id| !asset_ids.contains(id.as_str()))
            {
                return Err(bad(format!(
                    "material {} references an asset missing from this package",
                    record.id
                )));
            }
            if artifact_id
                .as_ref()
                .is_some_and(|id| !artifact_ids.contains(id.as_str()))
            {
                return Err(bad(format!(
                    "material {} references an artifact missing from this package",
                    record.id
                )));
            }
        }
    }
    for artifact in &bundle.artifacts {
        for revision in &artifact.revisions {
            if revision
                .assets
                .values()
                .any(|id| !asset_ids.contains(id.as_str()))
            {
                return Err(bad(
                    "artifact revision references an asset missing from this package",
                ));
            }
        }
    }
    Ok(())
}
fn validate_artifact(artifact: &Artifact) -> Result<(), ApiError> {
    if uuid::Uuid::parse_str(&artifact.id).is_err()
        || artifact.revisions.is_empty()
        || artifact.revisions.len() > 32
    {
        return Err(bad("invalid artifact ID or revision count"));
    }
    if !artifact.approved_domains.is_empty() {
        return Err(bad("imported artifacts must not contain network grants"));
    }
    if serde_json::to_vec(&artifact.state)
        .map_err(ApiError::internal)?
        .len()
        > 64 * 1024
    {
        return Err(bad("artifact state exceeds 64 KiB"));
    }
    for (index, revision) in artifact.revisions.iter().enumerate() {
        if revision.revision != index as u32 + 1 {
            return Err(bad("artifact revisions must be complete and ordered"));
        }
        artifacts::validate(&Submission {
            id: Some(artifact.id.clone()),
            title: artifact.title.clone(),
            description: artifact.description.clone(),
            files: revision.files.clone(),
            assets: revision.assets.clone(),
            entry: revision.entry.clone(),
            expected_revision: index as u32,
            state_version: revision.state_version,
            requested_domains: artifact.requested_domains.clone(),
        })?;
    }
    if artifact.revisions.last().unwrap().state_version != artifact.state_version {
        return Err(bad("artifact state format does not match latest revision"));
    }
    Ok(())
}
/// Must run with both domain and asset gates held, including dry-run transaction.
fn prepare(state: &ApiState, bundle: &LearningBundle) -> Result<Prepared, ApiError> {
    if bundle.format_version != 1 || bundle.assets.len() > 10000 || bundle.artifacts.len() > 1000 {
        return Err(bad("unsupported or oversized learning package"));
    }
    if serde_json::to_vec(bundle)
        .map_err(ApiError::internal)?
        .len()
        > MAX_JSON
    {
        return Err(bad("learning package exceeds JSON limit"));
    }
    validate_references(bundle)?;
    let current: BTreeMap<String, Asset> = state
        .assets
        .manifest()?
        .into_iter()
        .map(|a| (a.id.clone(), a))
        .collect();
    let mut incoming_ids = BTreeSet::new();
    let mut artifact_ids = BTreeSet::new();
    let mut entries = Vec::new();
    let mut bytes = Vec::new();
    let mut asset_bytes = 0usize;
    let mut new_asset_bytes = 0u64;
    let mut new_asset_count = 0usize;
    for bundled in &bundle.assets {
        let metadata = &bundled.metadata;
        if !incoming_ids.insert(metadata.id.clone())
            || metadata.id != metadata.sha256
            || metadata.size == 0
            || metadata.size > MAX_IMAGE_BYTES as u64
        {
            return Err(bad("invalid or duplicate asset manifest entry"));
        }
        if bundled.data_base64.len() > MAX_IMAGE_BYTES.div_ceil(3) * 4 {
            return Err(bad("package base64 asset exceeds limit"));
        }
        let decoded = STANDARD
            .decode(&bundled.data_base64)
            .map_err(|_| bad("invalid package asset base64"))?;
        asset_bytes = asset_bytes
            .checked_add(decoded.len())
            .ok_or_else(|| bad("asset byte limit exceeded"))?;
        if asset_bytes > MAX_ASSETS
            || decoded.len() as u64 != metadata.size
            || content_hash(&decoded) != metadata.id
        {
            return Err(bad(
                "package asset hash/size mismatch or total byte limit exceeded",
            ));
        }
        if !assets::valid_mime(&metadata.mime_type) {
            return Err(bad("invalid package MIME"));
        }
        if metadata.mime_type.starts_with("image/") {
            let (mime, width, height) = validate_image(&decoded).map_err(|e| bad(e.to_string()))?;
            if metadata.mime_type != mime
                || metadata.width != Some(width)
                || metadata.height != Some(height)
            {
                return Err(bad("package image MIME/dimensions mismatch"));
            }
        } else if metadata.width.is_some() || metadata.height.is_some() {
            return Err(bad("non-image asset cannot carry raster dimensions"));
        }
        if metadata.references.len() > 10000
            || metadata.references.iter().any(|r| {
                r.owner_type.is_empty()
                    || r.owner_id.is_empty()
                    || r.owner_type.len() > 256
                    || r.owner_id.len() > 256
                    || r.owner_type.chars().any(char::is_control)
                    || r.owner_id.chars().any(char::is_control)
            })
        {
            return Err(bad("invalid package asset owner reference"));
        }
        let mut merged = if let Some(existing) = current.get(&metadata.id) {
            if existing.sha256 != metadata.sha256
                || existing.mime_type != metadata.mime_type
                || existing.size != metadata.size
                || existing.width != metadata.width
                || existing.height != metadata.height
            {
                return Err(bad(
                    "existing asset metadata conflicts with imported content",
                ));
            }
            state.assets.read_bytes(&metadata.id)?;
            existing.clone()
        } else {
            new_asset_count += 1;
            new_asset_bytes += metadata.size;
            let mut imported = metadata.clone();
            imported.references.clear();
            imported
        };
        // Source-device owner IDs are descriptive only. Preserve valid portable owners and
        // add a package owner, never overwrite local owners or import runtime grants.
        for owner in &metadata.references {
            let portable = (owner.owner_type == "artifact"
                && bundle.artifacts.iter().any(|a| a.id == owner.owner_id))
                || (owner.owner_type == "learning"
                    && bundle
                        .learning
                        .records
                        .iter()
                        .any(|r| r.id == owner.owner_id));
            if portable && !merged.references.contains(owner) {
                merged.references.push(owner.clone());
            }
        }
        let package_owner = AssetReference {
            owner_type: "learningPackage".into(),
            owner_id: metadata.id.clone(),
        };
        if !merged.references.contains(&package_owner) {
            merged.references.push(package_owner);
        }
        for artifact in &bundle.artifacts {
            if artifact
                .revisions
                .iter()
                .any(|r| r.assets.values().any(|id| id == &metadata.id))
            {
                let owner = AssetReference {
                    owner_type: "artifact".into(),
                    owner_id: artifact.id.clone(),
                };
                if !merged.references.contains(&owner) {
                    merged.references.push(owner);
                }
            }
        }
        for record in &bundle.learning.records {
            if matches!(&record.content, LearningContent::Material { asset_id: Some(id), .. } if id == &metadata.id)
                || record
                    .source
                    .as_ref()
                    .is_some_and(|s| s.asset_ids.contains(&metadata.id))
            {
                let owner = AssetReference {
                    owner_type: "learning".into(),
                    owner_id: record.id.clone(),
                };
                if !merged.references.contains(&owner) {
                    merged.references.push(owner);
                }
            }
        }
        if merged.references.len() > 10000 {
            return Err(bad("merged asset references exceed limit"));
        }
        merged.unreferenced_at = None;
        entries.push((
            format!("local_asset:{}", metadata.id),
            serde_json::to_string(&merged).map_err(ApiError::internal)?,
        ));
        bytes.push((metadata.id.clone(), decoded));
    }
    if current.len() + new_asset_count > 10000
        || current.values().map(|a| a.size).sum::<u64>() + new_asset_bytes > 512 * 1024 * 1024
    {
        return Err(bad("import would exceed asset catalog quota"));
    }
    for (asset_id, imported) in &bundle.generation_provenance {
        if !incoming_ids.contains(asset_id) || imported.len() > 128 {
            return Err(bad("invalid provenance asset reference or record limit"));
        }
        let mut merged: Vec<assets::ImageProvenance> =
            assets::load(&state.store, &format!("provenance:{asset_id}"))?.unwrap_or_default();
        for record in imported {
            assets::validate_provenance(record)?;
            if !merged.contains(record) {
                merged.push(record.clone());
            }
        }
        if merged.len() > 128 {
            return Err(bad("merged asset provenance exceeds limit"));
        }
        entries.push((
            format!("provenance:{asset_id}"),
            serde_json::to_string(&merged).map_err(ApiError::internal)?,
        ));
    }
    for artifact in &bundle.artifacts {
        if !artifact_ids.insert(&artifact.id) {
            return Err(bad("duplicate artifact ID"));
        }
        validate_artifact(artifact)?;
        let mut imported = artifact.clone();
        imported.trusted_on_device = false;
        entries.push((
            format!("artifact:{}", artifact.id),
            serde_json::to_string(&imported).map_err(ApiError::internal)?,
        ));
    }
    let learning_preview = state
        .store
        .import_learning_with_local_kv(&bundle.learning, false, &entries)
        .map_err(learning_error)?;
    Ok(Prepared { entries, bytes, preview: BundlePreview {
        format_version: 1, records: learning_preview.records, revisions: learning_preview.revisions, attempts: learning_preview.attempts,
        asset_count: bundle.assets.len(), asset_bytes, artifact_count: bundle.artifacts.len(), external_resources_included: true,
        warnings: vec!["Local IDs are preserved; conflicting learning or artifact IDs are rejected. Imported artifacts require fresh execution/network permission. Provider credentials and device grants are excluded.".into()],
    } })
}
async fn preview(
    State(state): State<ApiState>,
    Json(bundle): Json<LearningBundle>,
) -> Result<Json<BundlePreview>, ApiError> {
    let _domain_guard = state.agent_mutations.lock().await;
    let _asset_guard = state.assets.gate.lock().await;
    Ok(Json(prepare(&state, &bundle)?.preview))
}
async fn import(
    State(state): State<ApiState>,
    Json(bundle): Json<LearningBundle>,
) -> Result<Json<Value>, ApiError> {
    let _domain_guard = state.agent_mutations.lock().await;
    let _asset_guard = state.assets.gate.lock().await;
    let prepared = prepare(&state, &bundle)?;
    let mut staged = Vec::new();
    for (id, bytes) in &prepared.bytes {
        match state.assets.stage_package_bytes(id, bytes) {
            Ok(true) => staged.push(id.clone()),
            Ok(false) => {}
            Err(error) => {
                for id in &staged {
                    state.assets.rollback_package_file(id);
                }
                return Err(error);
            }
        }
    }
    if let Err(error) =
        state
            .store
            .import_learning_with_local_kv(&bundle.learning, true, &prepared.entries)
    {
        for id in &staged {
            state.assets.rollback_package_file(id);
        }
        return Err(learning_error(error));
    }
    Ok(Json(json!({"imported":true,"preview":prepared.preview})))
}

#[cfg(test)]
mod tests {
    use super::*;
    use messenger_store::{learning::LearningRecord, Store};
    use std::sync::Arc;
    fn state() -> ApiState {
        ApiState::new(Arc::new(Store::open_memory().unwrap()), "test")
    }
    async fn fixture(source: &ApiState) -> (String, String) {
        let artifact_id = uuid::Uuid::new_v4().to_string();
        let asset = source
            .assets
            .import_bytes(
                b"binary resource",
                "application/octet-stream",
                AssetReference {
                    owner_type: "artifact".into(),
                    owner_id: artifact_id.clone(),
                },
            )
            .await
            .unwrap();
        source
            .assets
            .record_provenance(
                &asset.id,
                assets::ImageProvenance {
                    prompt: "A teaching illustration".into(),
                    model_id: "image-model".into(),
                    parameters: serde_json::Map::new(),
                    revised_prompt: Some("A revised illustration".into()),
                    created_at: 1,
                    completed_at: 2,
                    usage: Some(json!({"images":1})),
                },
            )
            .await
            .unwrap();
        let record: LearningRecord = serde_json::from_value(json!({"id":"material-one","title":"Binary reference","source":{"kind":"manual","snapshot":"Source snapshot","assetIds":[asset.id]},"content":{"kind":"material","materialKind":"file","assetId":asset.id,"artifactId":artifact_id}})).unwrap();
        source.store.upsert_learning(record, 100).unwrap();
        let artifact = json!({"id":artifact_id,"title":"Experiment","description":"Portable state","revisions":[{"revision":1,"files":{"index.html":"<h1>Experiment</h1>"},"assets":{"data.bin":asset.id},"entry":"index.html","createdAt":1,"stateVersion":1}],"requestedDomains":["example.org"],"approvedDomains":["example.org"],"state":{"answer":42},"stateVersion":1,"trustedOnDevice":true,"createdAt":1,"updatedAt":1});
        source
            .store
            .kv_set(&format!("artifact:{artifact_id}"), &artifact.to_string())
            .unwrap();
        (asset.id, artifact_id)
    }
    #[tokio::test]
    async fn full_bundle_roundtrip_preserves_resources_revisions_state_without_grants() {
        let source = state();
        let (asset_id, artifact_id) = fixture(&source).await;
        let Json(mut bundle) = export(State(source)).await.unwrap();
        assert!(bundle.artifacts[0].approved_domains.is_empty());
        assert!(!bundle.artifacts[0].trusted_on_device);
        bundle.artifacts[0].trusted_on_device = true; // Untrusted input cannot import execution permission.
        let destination = state();
        let existing = destination
            .assets
            .import_bytes(
                b"binary resource",
                "application/octet-stream",
                AssetReference {
                    owner_type: "local".into(),
                    owner_id: "keep-me".into(),
                },
            )
            .await
            .unwrap();
        assert_eq!(existing.id, asset_id);
        let Json(preview_result) = preview(State(destination.clone()), Json(bundle.clone()))
            .await
            .unwrap();
        assert_eq!(preview_result.asset_count, 1);
        assert_eq!(preview_result.artifact_count, 1);
        assert!(destination
            .store
            .get_learning("material-one")
            .unwrap()
            .is_none());
        let _ = import(State(destination.clone()), Json(bundle))
            .await
            .unwrap();
        assert!(destination
            .store
            .get_learning("material-one")
            .unwrap()
            .is_some());
        let asset = destination.assets.get(&asset_id).unwrap();
        assert!(asset.references.iter().any(|r| r.owner_id == "keep-me"));
        assert!(asset
            .references
            .iter()
            .any(|r| r.owner_type == "artifact" && r.owner_id == artifact_id));
        assert_eq!(
            destination.assets.read_bytes(&asset_id).unwrap(),
            b"binary resource"
        );
        let restored = artifacts::load(&destination.store, &artifact_id).unwrap();
        assert_eq!(restored.state["answer"], 42);
        assert!(restored.approved_domains.is_empty());
        assert!(!restored.trusted_on_device);
        let provenance = destination.assets.provenance(&asset_id).unwrap();
        assert_eq!(provenance[0].model_id, "image-model");
        assert_eq!(provenance[0].prompt, "A teaching illustration");
        assert_eq!(provenance[0].usage.as_ref().unwrap()["images"], 1);
        assert_eq!(restored.revisions[0].assets["data.bin"], asset_id);
    }
    #[tokio::test]
    async fn rejects_hash_missing_reference_and_id_collision_before_writes() {
        let source = state();
        fixture(&source).await;
        let Json(bundle) = export(State(source)).await.unwrap();
        let destination = state();
        let mut corrupt = bundle.clone();
        corrupt.assets[0].data_base64 = STANDARD.encode(b"corrupted");
        assert!(import(State(destination.clone()), Json(corrupt))
            .await
            .is_err());
        assert!(destination.assets.manifest().unwrap().is_empty());
        assert!(destination
            .store
            .export_learning()
            .unwrap()
            .records
            .is_empty());
        let mut missing = bundle.clone();
        missing.assets.clear();
        assert!(preview(State(destination.clone()), Json(missing))
            .await
            .is_err());
        let _ = import(State(destination.clone()), Json(bundle.clone()))
            .await
            .unwrap();
        let before = destination.store.export_learning().unwrap();
        assert!(import(State(destination.clone()), Json(bundle))
            .await
            .is_err());
        assert_eq!(
            destination.store.export_learning().unwrap().records,
            before.records
        );
    }
    #[tokio::test]
    async fn rejects_imported_network_grants_and_state_mismatch() {
        let source = state();
        fixture(&source).await;
        let Json(mut bundle) = export(State(source)).await.unwrap();
        let destination = state();
        bundle.artifacts[0]
            .approved_domains
            .push("example.org".into());
        assert!(preview(State(destination.clone()), Json(bundle.clone()))
            .await
            .is_err());
        bundle.artifacts[0].approved_domains.clear();
        bundle.artifacts[0].state_version = 2;
        assert!(preview(State(destination), Json(bundle)).await.is_err());
    }
}
