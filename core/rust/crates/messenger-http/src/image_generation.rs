//! Durable BYOK image jobs. Explicit capability, idempotency and no automatic resubmission.
use crate::{
    assets::{self, bad, load, now, save, AssetReference, AssetService},
    ApiError, ApiState,
};
use axum::{
    extract::{Path, State},
    routing::{get, post, put},
    Json, Router,
};
use base64::{engine::general_purpose::STANDARD, Engine};
use messenger_llm::image_generation::{self as adapter, ImageError};
use messenger_store::Store;
use messenger_tools::{BuiltinTool, ToolExecutionResult};
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use std::{
    collections::{BTreeSet, HashMap},
    sync::Arc,
    time::Duration,
};
use tokio::sync::Mutex;
use tokio_util::sync::CancellationToken;

pub(crate) const TOOL_NAME: &str = "messenger_generate_image";
const ENABLED: &str = "image_generation_enabled";
const PROFILE: &str = "local_image_profile:";
const JOB: &str = "local_image_job:";
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct ImageGenerationProfile {
    pub id: String,
    pub name: String,
    pub provider_id: String,
    /// Generation model identifier independent from chat model rows/protocol.
    pub model_id: String,
    #[serde(default = "adapter_name")]
    pub adapter: String,
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub supported_parameters: BTreeSet<String>,
    #[serde(default)]
    pub defaults: Map<String, Value>,
}
fn adapter_name() -> String {
    "openai_images".into()
}
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct GenerateRequest {
    pub request_id: String,
    pub profile_id: String,
    pub prompt: String,
    #[serde(default)]
    pub parameters: Map<String, Value>,
}
#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub(crate) enum JobStatus {
    Queued,
    Running,
    Succeeded,
    Failed,
    Cancelled,
    Unknown,
}
impl JobStatus {
    fn terminal(self) -> bool {
        !matches!(self, Self::Queued | Self::Running)
    }
}
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ImageGenerationJob {
    pub id: String,
    pub request: GenerateRequest,
    pub profile: ImageGenerationProfile,
    pub parameters: Map<String, Value>,
    pub status: JobStatus,
    pub created_at: i64,
    pub updated_at: i64,
    pub asset_ids: Vec<String>,
    pub error: Option<String>,
    pub usage: Option<Value>,
    #[serde(default)]
    pub revised_prompts: Vec<Option<String>>,
}
pub(crate) struct ImageGenerationService {
    store: Arc<Store>,
    assets: Arc<AssetService>,
    gate: Mutex<()>,
    active: Mutex<HashMap<String, CancellationToken>>,
}
impl ImageGenerationService {
    pub(crate) fn new(store: Arc<Store>, assets: Arc<AssetService>) -> Result<Self, ApiError> {
        let service = Self {
            store,
            assets,
            gate: Mutex::new(()),
            active: Mutex::new(HashMap::new()),
        };
        for mut job in service.jobs()? {
            if !job.status.terminal() {
                job.status = JobStatus::Unknown;
                job.updated_at = now();
                job.error = Some("Core stopped before the result was recorded. Remote generation/charges may have occurred; no automatic retry.".into());
                service.save_job(&job)?;
            }
        }
        Ok(service)
    }
    pub(crate) async fn clear(&self) -> Result<(), ApiError> {
        let _guard = self.gate.lock().await;
        for token in self.active.lock().await.values() {
            token.cancel();
        }
        for prefix in [PROFILE, JOB] {
            for (key, _) in self.store.kv_list_prefix(prefix).map_err(ApiError::store)? {
                self.store.kv_delete(&key).map_err(ApiError::store)?;
            }
        }
        self.store.kv_delete(ENABLED).map_err(ApiError::store)?;
        Ok(())
    }
    pub(crate) fn profiles(&self) -> Result<Vec<ImageGenerationProfile>, ApiError> {
        catalog(&self.store, PROFILE)
    }
    pub(crate) fn jobs(&self) -> Result<Vec<ImageGenerationJob>, ApiError> {
        catalog(&self.store, JOB)
    }
    pub(crate) fn get(&self, id: &str) -> Result<ImageGenerationJob, ApiError> {
        validate_identifier(id)?;
        load(&self.store, &format!("{JOB}{id}"))?
            .ok_or_else(|| ApiError::not_found("image generation job"))
    }
    fn save_job(&self, job: &ImageGenerationJob) -> Result<(), ApiError> {
        save(&self.store, &format!("{JOB}{}", job.id), job)
    }
    pub(crate) async fn put_profile(
        &self,
        profile: ImageGenerationProfile,
    ) -> Result<ImageGenerationProfile, ApiError> {
        let _guard = self.gate.lock().await;
        validate_profile(&self.store, &profile)?;
        if self.profiles()?.len() >= 256
            && load::<ImageGenerationProfile>(&self.store, &format!("{PROFILE}{}", profile.id))?
                .is_none()
        {
            return Err(bad("profile limit exceeded"));
        }
        save(&self.store, &format!("{PROFILE}{}", profile.id), &profile)?;
        Ok(profile)
    }
    /// Dedup uses requestId plus original payload, before re-reading mutable profile/provider.
    pub(crate) async fn submit(
        self: &Arc<Self>,
        request: GenerateRequest,
        cancel: CancellationToken,
    ) -> Result<ImageGenerationJob, ApiError> {
        let _guard = self.gate.lock().await;
        validate_identifier(&request.request_id)?;
        let id = adapter::content_hash(request.request_id.as_bytes());
        if let Some(existing) = load::<ImageGenerationJob>(&self.store, &format!("{JOB}{id}"))? {
            if existing.request != request {
                return Err(ApiError::Conflict {
                    code: "request_id_conflict".into(),
                    message: "requestId already identifies a different image request".into(),
                });
            }
            return Ok(existing);
        }
        if !enabled(&self.store)? {
            return Err(bad("image generation is disabled; enable it explicitly before submitting a paid request"));
        }
        if cancel.is_cancelled() {
            return Err(bad("image generation request cancelled before submission"));
        }
        validate_identifier(&request.profile_id)?;
        if request.prompt.trim().is_empty() || request.prompt.len() > 32000 {
            return Err(bad("prompt required, maximum 32000 UTF-8 bytes"));
        }
        let profile: ImageGenerationProfile =
            load(&self.store, &format!("{PROFILE}{}", request.profile_id))?
                .ok_or_else(|| bad("select an image generation profile first"))?;
        validate_profile(&self.store, &profile)?;
        if !profile.enabled {
            return Err(bad("image generation profile is disabled"));
        }
        let mut parameters = profile.defaults.clone();
        parameters.extend(request.parameters.clone());
        validate_parameters(&parameters, &profile.supported_parameters)?;
        let provider = self
            .store
            .get_provider(&profile.provider_id)
            .map_err(ApiError::store)?
            .ok_or_else(|| bad("generation provider missing; select another profile"))?;
        if self.jobs()?.len() >= 10000 {
            return Err(bad("generation job catalog limit exceeded"));
        }
        let timestamp = now();
        let job = ImageGenerationJob {
            id: id.clone(),
            request,
            profile,
            parameters,
            status: JobStatus::Queued,
            created_at: timestamp,
            updated_at: timestamp,
            asset_ids: vec![],
            error: None,
            usage: None,
            revised_prompts: vec![],
        };
        self.save_job(&job)?;
        self.active.lock().await.insert(id, cancel.clone());
        let service = self.clone();
        let worker_job = job.clone();
        tokio::spawn(async move {
            service
                .run(worker_job, provider.base_url, provider.api_key, cancel)
                .await;
        });
        Ok(job)
    }
    async fn run(
        self: Arc<Self>,
        mut job: ImageGenerationJob,
        base_url: String,
        key: String,
        cancel: CancellationToken,
    ) {
        {
            let _guard = self.gate.lock().await;
            if self
                .get(&job.id)
                .map(|j| j.status == JobStatus::Cancelled)
                .unwrap_or(true)
            {
                self.active.lock().await.remove(&job.id);
                return;
            }
            job.status = JobStatus::Running;
            job.updated_at = now();
            if self.save_job(&job).is_err() {
                self.active.lock().await.remove(&job.id);
                return;
            }
        }
        let result = tokio::select! {
            biased;
            _ = cancel.cancelled() => Err((JobStatus::Cancelled, "Cancelled locally; the provider may have generated or charged for this request.".to_string())),
            result = self.perform(&job, &base_url, &key) => result,
        };
        let _guard = self.gate.lock().await;
        if self
            .get(&job.id)
            .map(|j| j.status != JobStatus::Cancelled)
            .unwrap_or(false)
        {
            match result {
                Ok((asset_ids, usage, revised_prompts)) => {
                    job.status = JobStatus::Succeeded;
                    job.asset_ids = asset_ids;
                    job.usage = usage;
                    job.revised_prompts = revised_prompts;
                }
                Err((status, error)) => {
                    job.status = status;
                    job.error = Some(error);
                }
            }
            job.updated_at = now();
            // If final persistence fails, startup changes the durable Running record to Unknown.
            let _ = self.save_job(&job);
        }
        self.active.lock().await.remove(&job.id);
    }
    async fn perform(
        &self,
        job: &ImageGenerationJob,
        base_url: &str,
        key: &str,
    ) -> Result<(Vec<String>, Option<Value>, Vec<Option<String>>), (JobStatus, String)> {
        let response = adapter::generate(
            base_url,
            key,
            &job.profile.model_id,
            &job.request.prompt,
            &job.parameters,
            &job.profile.supported_parameters,
        )
        .await
        .map_err(|e| {
            let status = if matches!(e, ImageError::Unknown(_)) {
                JobStatus::Unknown
            } else {
                JobStatus::Failed
            };
            (status, e.to_string())
        })?;
        let mut imported = Vec::new();
        let mut revised_prompts = Vec::new();
        for image in response.data {
            let bytes = if let Some(encoded) = image.b64_json {
                if encoded.len() > adapter::MAX_IMAGE_BYTES.div_ceil(3) * 4 {
                    return Err((
                        JobStatus::Failed,
                        "provider base64 image exceeds limit".into(),
                    ));
                }
                STANDARD
                    .decode(encoded)
                    .map_err(|e| (JobStatus::Failed, format!("invalid image base64: {e}")))?
            } else {
                let (bytes, mime) = assets::download(image.url.as_deref().unwrap_or_default())
                    .await
                    .map_err(|e| (JobStatus::Failed, format!("image download failed: {e:?}")))?;
                let (detected, _, _) = adapter::validate_image(&bytes)
                    .map_err(|e| (JobStatus::Failed, e.to_string()))?;
                if mime != detected {
                    return Err((
                        JobStatus::Failed,
                        "download MIME does not match image bytes".into(),
                    ));
                }
                bytes
            };
            let (mime, _, _) =
                adapter::validate_image(&bytes).map_err(|e| (JobStatus::Failed, e.to_string()))?;
            let asset = self
                .assets
                .import_bytes(
                    &bytes,
                    &mime,
                    AssetReference {
                        owner_type: "imageGenerationJob".into(),
                        owner_id: job.id.clone(),
                    },
                )
                .await
                .map_err(|e| (JobStatus::Failed, format!("image storage failed: {e:?}")))?;
            self.assets
                .record_provenance(
                    &asset.id,
                    assets::ImageProvenance {
                        prompt: job.request.prompt.clone(),
                        model_id: job.profile.model_id.clone(),
                        parameters: job.parameters.clone(),
                        revised_prompt: image.revised_prompt.clone(),
                        created_at: job.created_at,
                        completed_at: now().max(job.created_at),
                        usage: response.usage.as_ref().and_then(assets::numeric_usage),
                    },
                )
                .await
                .map_err(|e| {
                    (
                        JobStatus::Failed,
                        format!("image provenance storage failed: {e:?}"),
                    )
                })?;
            imported.push(asset.id);
            revised_prompts.push(image.revised_prompt);
        }
        Ok((imported, response.usage, revised_prompts))
    }
    pub(crate) async fn cancel(&self, id: &str) -> Result<ImageGenerationJob, ApiError> {
        let _guard = self.gate.lock().await;
        let mut job = self.get(id)?;
        if !job.status.terminal() {
            job.status = JobStatus::Cancelled;
            job.updated_at = now();
            job.error = Some(
                "Cancelled locally; remote generation and charges may already have occurred."
                    .into(),
            );
            self.save_job(&job)?;
            if let Some(token) = self.active.lock().await.get(id) {
                token.cancel();
            }
        }
        Ok(job)
    }
}
fn catalog<T: serde::de::DeserializeOwned>(
    store: &Store,
    prefix: &str,
) -> Result<Vec<T>, ApiError> {
    store
        .kv_list_prefix(prefix)
        .map_err(ApiError::store)?
        .into_iter()
        .map(|(_, s)| serde_json::from_str(&s).map_err(ApiError::internal))
        .collect()
}
fn validate_identifier(value: &str) -> Result<(), ApiError> {
    if value.is_empty()
        || value.len() > 256
        || !value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_:.".contains(&b))
    {
        return Err(bad("invalid identifier"));
    }
    Ok(())
}
pub(crate) fn validate_parameters(
    parameters: &Map<String, Value>,
    supported: &BTreeSet<String>,
) -> Result<(), ApiError> {
    let allowed = [
        "n",
        "size",
        "quality",
        "style",
        "response_format",
        "background",
        "output_format",
        "output_compression",
        "moderation",
    ];
    if supported.iter().any(|p| !allowed.contains(&p.as_str())) {
        return Err(bad("unsupported Images API parameter capability"));
    }
    for (key, value) in parameters {
        if !supported.contains(key) || !allowed.contains(&key.as_str()) {
            return Err(bad(format!("unsupported generation parameter {key}")));
        }
        if key == "n" {
            if !matches!(value.as_u64(), Some(1..=4)) {
                return Err(bad("n must be 1..4"));
            }
        } else if key == "output_compression" {
            if !matches!(value.as_u64(), Some(0..=100)) {
                return Err(bad("output_compression must be 0..100"));
            }
        } else if value.as_str().is_none_or(|v| v.is_empty() || v.len() > 128) {
            return Err(bad(format!("parameter {key} requires a bounded string")));
        }
    }
    Ok(())
}
fn validate_profile(store: &Store, profile: &ImageGenerationProfile) -> Result<(), ApiError> {
    validate_identifier(&profile.id)?;
    if profile.name.trim().is_empty()
        || profile.name.len() > 256
        || profile.model_id.trim().is_empty()
        || profile.model_id.len() > 256
        || profile.adapter != "openai_images"
    {
        return Err(bad(
            "profile requires name, generation model and explicit openai_images adapter",
        ));
    }
    if profile.provider_id == messenger_sync::models::BUILTIN_PROVIDER_ID {
        return Err(bad(
            "built-in Cloud provider has no declared Images API capability",
        ));
    }
    let provider = store
        .get_provider(&profile.provider_id)
        .map_err(ApiError::store)?
        .ok_or_else(|| bad("generation provider does not exist"))?;
    if provider.api_key.trim().is_empty() {
        return Err(bad("generation provider API key is missing"));
    }
    let url = reqwest::Url::parse(&provider.base_url).map_err(|_| bad("provider URL invalid"))?;
    if !matches!(url.scheme(), "https" | "http")
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(bad(
            "provider URL must be explicit HTTP(S) endpoint without credentials, query or fragment",
        ));
    }
    validate_parameters(&profile.defaults, &profile.supported_parameters)
}
pub(crate) fn enabled(store: &Store) -> Result<bool, ApiError> {
    Ok(store.kv_get(ENABLED).map_err(ApiError::store)?.as_deref() == Some("true"))
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Capability {
    enabled: bool,
}
async fn capability_get(State(state): State<ApiState>) -> Result<Json<Capability>, ApiError> {
    Ok(Json(Capability {
        enabled: enabled(&state.store)?,
    }))
}
async fn capability_put(
    State(state): State<ApiState>,
    Json(body): Json<Capability>,
) -> Result<Json<Capability>, ApiError> {
    state
        .store
        .kv_set(ENABLED, if body.enabled { "true" } else { "false" })
        .map_err(ApiError::store)?;
    Ok(Json(body))
}
async fn profiles(
    State(state): State<ApiState>,
) -> Result<Json<Vec<ImageGenerationProfile>>, ApiError> {
    Ok(Json(state.images.profiles()?))
}
async fn profile_put(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(body): Json<ImageGenerationProfile>,
) -> Result<Json<ImageGenerationProfile>, ApiError> {
    if id != body.id {
        return Err(bad("profile path/payload ID mismatch"));
    }
    Ok(Json(state.images.put_profile(body).await?))
}
async fn profile_delete(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<Value>, ApiError> {
    validate_identifier(&id)?;
    let _guard = state.images.gate.lock().await;
    state
        .store
        .kv_delete(&format!("{PROFILE}{id}"))
        .map_err(ApiError::store)?;
    Ok(Json(json!({"deleted":true})))
}
async fn submit(
    State(state): State<ApiState>,
    Json(body): Json<GenerateRequest>,
) -> Result<Json<ImageGenerationJob>, ApiError> {
    Ok(Json(
        state
            .images
            .submit(body, state.shutdown.child_token())
            .await?,
    ))
}
async fn jobs(State(state): State<ApiState>) -> Result<Json<Vec<ImageGenerationJob>>, ApiError> {
    Ok(Json(state.images.jobs()?))
}
async fn job(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<ImageGenerationJob>, ApiError> {
    Ok(Json(state.images.get(&id)?))
}
async fn cancel(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<ImageGenerationJob>, ApiError> {
    Ok(Json(state.images.cancel(&id).await?))
}
pub(crate) fn routes() -> Router<ApiState> {
    Router::new()
        .route(
            "/image-generation/capability",
            get(capability_get).put(capability_put),
        )
        .route("/image-generation/profiles", get(profiles))
        .route(
            "/image-generation/profiles/:id",
            put(profile_put).delete(profile_delete),
        )
        .route("/image-generation/jobs", get(jobs).post(submit))
        .route("/image-generation/jobs/:id", get(job))
        .route("/image-generation/jobs/:id/cancel", post(cancel))
}
pub(crate) fn tool() -> BuiltinTool {
    BuiltinTool { name: TOOL_NAME.into(), description: "Discover enabled BYOK image profiles or generate paid images with an explicitly selected profile. First call action=listProfiles if you do not know the configured profile IDs; discovery is free and returns only IDs, names and model identifiers. An empty list means the user must configure or enable a profile. To generate, use action=generate (or omit action), an exact discovered profileId and a stable requestId for idempotency. Cancellation may still incur provider charges. Returns persistent job and asset references; never automatically retry unknown outcomes or select an implicit provider fallback.".into(),
        parameters_json: json!({"type":"object","properties":{"action":{"type":"string","enum":["listProfiles","generate"]},"requestId":{"type":"string"},"profileId":{"type":"string"},"prompt":{"type":"string"},"parameters":{"type":"object"}},"anyOf":[{"properties":{"action":{"enum":["listProfiles"]}},"required":["action"],"maxProperties":1},{"properties":{"action":{"enum":["generate"]}},"required":["requestId","profileId","prompt"]}],"additionalProperties":false}).to_string(),
        write_access: false, read_only: false, workspace_required: false }
}
pub(crate) async fn execute(
    state: &ApiState,
    permitted: bool,
    arguments: &str,
    cancel: CancellationToken,
) -> ToolExecutionResult {
    let result = async {
        if !permitted || !enabled(&state.store)? { return Err(bad("image generation tool disabled for this turn")); }
        if cancel.is_cancelled() || state.shutdown.is_cancelled() { return Err(bad("image generation tool cancelled")); }
        let mut arguments: Value = serde_json::from_str(arguments).map_err(|e| bad(e.to_string()))?;
        let object = arguments.as_object_mut().ok_or_else(|| bad("image tool arguments must be an object"))?;
        let action = object.remove("action").unwrap_or_else(|| Value::String("generate".into()));
        match action.as_str() {
            Some("listProfiles") => {
                if !object.is_empty() { return Err(bad("listProfiles accepts only action")); }
                let profiles: Vec<Value> = state.images.profiles()?.into_iter().filter(|profile| profile.enabled)
                    .map(|profile| json!({"id":profile.id,"name":profile.name,"modelId":profile.model_id})).collect();
                return Ok(ToolExecutionResult { output: json!({"profiles":profiles}).to_string(), is_error: false, ..Default::default() });
            },
            Some("generate") => {},
            _ => return Err(bad("unknown image tool action")),
        }
        let request: GenerateRequest = serde_json::from_value(arguments).map_err(|e| bad(e.to_string()))?;
        let mut job = state.images.submit(request, cancel.child_token()).await?;
        while !job.status.terminal() {
            tokio::select! {
                _ = cancel.cancelled() => { job = state.images.cancel(&job.id).await?; },
                _ = state.shutdown.cancelled() => { job = state.images.cancel(&job.id).await?; },
                _ = tokio::time::sleep(Duration::from_millis(100)) => { job = state.images.get(&job.id)?; },
            }
        }
        Ok::<_, ApiError>(ToolExecutionResult {
            output: serde_json::to_string(&job).unwrap_or_default(),
            is_error: job.status != JobStatus::Succeeded,
            structured_content: Some(
                json!({"version":1,"type":"imageGenerationJob","jobId":job.id,"assetIds":job.asset_ids,"status":job.status}),
            ),
            ..Default::default()
        })
    }.await;
    match result {
        Ok(result) => result,
        Err(error) => ToolExecutionResult {
            output: format!("{error:?}"),
            is_error: true,
            ..Default::default()
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{
        body::Body,
        http::{Request, StatusCode},
    };
    use tower::ServiceExt;
    use wiremock::{matchers::method, Mock, MockServer, ResponseTemplate};
    async fn setup(server: &MockServer) -> (tempfile::TempDir, Arc<ImageGenerationService>) {
        let dir = tempfile::tempdir().unwrap();
        let store = Arc::new(Store::open(&dir.path().join("store.db")).unwrap());
        store
            .upsert_provider(&messenger_store::StoredProvider {
                id: "provider".into(),
                name: "Test".into(),
                base_url: server.uri(),
                api_key: "mock-key".into(),
                api_format: "messages".into(),
                created_at: 0,
                updated_at: 0,
            })
            .unwrap();
        let assets = Arc::new(AssetService::new(store.clone(), dir.path().join("assets")).unwrap());
        let service = Arc::new(ImageGenerationService::new(store, assets).unwrap());
        service
            .put_profile(ImageGenerationProfile {
                id: "images".into(),
                name: "Images".into(),
                provider_id: "provider".into(),
                model_id: "independent-image-model".into(),
                adapter: adapter_name(),
                enabled: true,
                supported_parameters: BTreeSet::new(),
                defaults: Map::new(),
            })
            .await
            .unwrap();
        (dir, service)
    }
    fn request() -> GenerateRequest {
        GenerateRequest {
            request_id: "stable-request".into(),
            profile_id: "images".into(),
            prompt: "A tree".into(),
            parameters: Map::new(),
        }
    }
    async fn terminal(service: &ImageGenerationService, id: &str) -> ImageGenerationJob {
        tokio::time::timeout(Duration::from_secs(5), async {
            loop {
                let job = service.get(id).unwrap();
                if job.status.terminal() {
                    return job;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
        })
        .await
        .unwrap()
    }
    #[tokio::test]
    async fn successful_base64_outputs_become_owned_decoded_assets() {
        let server = MockServer::start().await;
        Mock::given(method("POST")).respond_with(ResponseTemplate::new(200).set_body_json(json!({"data":[{"b64_json":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC","revised_prompt":"A red pixel"}],"usage":{"images":1}}))).expect(1).mount(&server).await;
        let (_dir, service) = setup(&server).await;
        service.store.kv_set(ENABLED, "true").unwrap();
        let job = service
            .submit(request(), CancellationToken::new())
            .await
            .unwrap();
        let finished = terminal(&service, &job.id).await;
        assert_eq!(
            finished.status,
            JobStatus::Succeeded,
            "{:?}",
            finished.error
        );
        assert_eq!(finished.asset_ids.len(), 1);
        assert_eq!(finished.usage.unwrap()["images"], 1);
        let asset = service.assets.get(&finished.asset_ids[0]).unwrap();
        assert_eq!(asset.mime_type, "image/png");
        assert_eq!((asset.width, asset.height), (Some(1), Some(1)));
        assert_eq!(asset.references[0].owner_id, job.id);
        assert!(!service.assets.read_bytes(&asset.id).unwrap().is_empty());
    }
    #[tokio::test]
    async fn capability_admission_dedup_conflict_and_provider_failure() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(429).set_body_string("quota exhausted"))
            .expect(1)
            .mount(&server)
            .await;
        let (_dir, service) = setup(&server).await;
        assert!(service
            .submit(request(), CancellationToken::new())
            .await
            .is_err());
        assert!(service.jobs().unwrap().is_empty());
        service.store.kv_set(ENABLED, "true").unwrap();
        let first = service
            .submit(request(), CancellationToken::new())
            .await
            .unwrap();
        let second = service
            .submit(request(), CancellationToken::new())
            .await
            .unwrap();
        assert_eq!(first.id, second.id);
        let mut conflicting = request();
        conflicting.prompt = "Other prompt".into();
        assert!(matches!(
            service.submit(conflicting, CancellationToken::new()).await,
            Err(ApiError::Conflict { .. })
        ));
        let failed = terminal(&service, &first.id).await;
        assert_eq!(failed.status, JobStatus::Failed);
        assert!(failed.error.unwrap().contains("429"));
        service.store.kv_set(ENABLED, "false").unwrap();
        assert_eq!(
            service
                .submit(request(), CancellationToken::new())
                .await
                .unwrap()
                .id,
            first.id
        );
    }
    #[tokio::test]
    async fn cancellation_is_durable_and_never_resubmits() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_delay(Duration::from_secs(2))
                    .set_body_json(json!({"data":[]})),
            )
            .mount(&server)
            .await;
        let (_dir, service) = setup(&server).await;
        service.store.kv_set(ENABLED, "true").unwrap();
        let job = service
            .submit(request(), CancellationToken::new())
            .await
            .unwrap();
        service.cancel(&job.id).await.unwrap();
        assert_eq!(
            terminal(&service, &job.id).await.status,
            JobStatus::Cancelled
        );
        assert_eq!(
            service
                .submit(request(), CancellationToken::new())
                .await
                .unwrap()
                .status,
            JobStatus::Cancelled
        );
    }
    #[tokio::test]
    async fn interruption_recovers_unknown_and_bad_output_fails() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(json!({"data":[{"b64_json":"not-base64"}]})),
            )
            .expect(1)
            .mount(&server)
            .await;
        let (_dir, service) = setup(&server).await;
        service.store.kv_set(ENABLED, "true").unwrap();
        let job = service
            .submit(request(), CancellationToken::new())
            .await
            .unwrap();
        let mut failed = terminal(&service, &job.id).await;
        assert_eq!(failed.status, JobStatus::Failed);
        assert!(failed.asset_ids.is_empty());
        failed.status = JobStatus::Running;
        service.save_job(&failed).unwrap();
        let reopened =
            ImageGenerationService::new(service.store.clone(), service.assets.clone()).unwrap();
        assert_eq!(reopened.get(&job.id).unwrap().status, JobStatus::Unknown);
    }
    #[tokio::test]
    async fn tool_discovers_only_enabled_profiles_without_credentials_jobs_or_charges() {
        let server = MockServer::start().await;
        let (_dir, service) = setup(&server).await;
        let state = ApiState::new(service.store.clone(), "token");
        let arguments = r#"{"action":"listProfiles"}"#;
        assert!(
            execute(&state, true, arguments, CancellationToken::new())
                .await
                .is_error
        );
        state.store.kv_set(ENABLED, "true").unwrap();
        assert!(
            execute(&state, false, arguments, CancellationToken::new())
                .await
                .is_error
        );
        let mut disabled = service.profiles().unwrap().remove(0);
        disabled.id = "disabled-images".into();
        disabled.enabled = false;
        service.put_profile(disabled).await.unwrap();
        let result = execute(&state, true, arguments, CancellationToken::new()).await;
        assert!(!result.is_error, "{}", result.output);
        assert_eq!(
            serde_json::from_str::<Value>(&result.output).unwrap(),
            json!({"profiles":[{"id":"images","name":"Images","modelId":"independent-image-model"}]})
        );
        assert!(state.images.jobs().unwrap().is_empty());
        assert!(server.received_requests().await.unwrap().is_empty());
        let mut profile = service
            .profiles()
            .unwrap()
            .into_iter()
            .find(|p| p.id == "images")
            .unwrap();
        profile.enabled = false;
        service.put_profile(profile).await.unwrap();
        let result = execute(&state, true, arguments, CancellationToken::new()).await;
        assert!(!result.is_error);
        assert_eq!(
            serde_json::from_str::<Value>(&result.output).unwrap(),
            json!({"profiles":[]})
        );
        assert!(
            execute(
                &state,
                true,
                r#"{"action":"listProfiles","prompt":"extra"}"#,
                CancellationToken::new()
            )
            .await
            .is_error
        );
    }
    #[tokio::test]
    async fn tool_generate_action_preserves_legacy_payload_and_explicit_profile_requirement() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(429).set_body_string("quota exhausted"))
            .expect(1)
            .mount(&server)
            .await;
        let (_dir, service) = setup(&server).await;
        let state = ApiState::new(service.store.clone(), "token");
        state.store.kv_set(ENABLED, "true").unwrap();
        let mut arguments = serde_json::to_value(request()).unwrap();
        arguments["action"] = json!("generate");
        let result = execute(
            &state,
            true,
            &arguments.to_string(),
            CancellationToken::new(),
        )
        .await;
        assert!(result.is_error);
        let explicit_job: ImageGenerationJob = serde_json::from_str(&result.output).unwrap();
        arguments.as_object_mut().unwrap().remove("action");
        let result = execute(
            &state,
            true,
            &arguments.to_string(),
            CancellationToken::new(),
        )
        .await;
        let legacy_job: ImageGenerationJob = serde_json::from_str(&result.output).unwrap();
        assert_eq!(explicit_job.id, legacy_job.id);
        assert_eq!(state.images.jobs().unwrap().len(), 1);
        assert!(
            execute(
                &state,
                true,
                r#"{"action":"generate","requestId":"other","prompt":"A tree"}"#,
                CancellationToken::new()
            )
            .await
            .is_error
        );
        assert_eq!(state.images.jobs().unwrap().len(), 1);
    }
    #[tokio::test]
    async fn image_routes_are_authenticated_and_default_disabled() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "token");
        let response = crate::router(state.clone())
            .oneshot(
                Request::builder()
                    .uri("/api/v1/image-generation/capability")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
        let response = crate::router(state)
            .oneshot(
                Request::builder()
                    .uri("/api/v1/image-generation/capability")
                    .header("authorization", "Bearer token")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let bytes = http_body_util::BodyExt::collect(response.into_body())
            .await
            .unwrap()
            .to_bytes();
        assert_eq!(
            serde_json::from_slice::<Value>(&bytes).unwrap(),
            json!({"enabled":false})
        );
    }
}
