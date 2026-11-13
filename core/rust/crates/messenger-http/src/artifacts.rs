//! Device-local, versioned browser applications. Generated pages never receive API credentials.
use crate::{ApiError, ApiState};
use axum::{
    extract::{Path, State},
    routing::{get, post},
    Json, Router,
};
use futures_util::StreamExt;
use messenger_store::Store;
use messenger_tools::{BuiltinTool, ToolExecutionResult};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    net::{IpAddr, SocketAddr},
    time::Duration,
};
use tokio_util::sync::CancellationToken;

pub(crate) const TOOL_NAME: &str = "messenger_artifacts";
const PREFIX: &str = "artifact:";
const LIMIT: usize = 4 * 1024 * 1024;
fn bad(message: impl Into<String>) -> ApiError {
    ApiError::BadRequest(message.into())
}
fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Revision {
    pub revision: u32,
    pub files: BTreeMap<String, String>,
    #[serde(default)]
    pub assets: BTreeMap<String, String>,
    pub entry: String,
    pub created_at: i64,
    #[serde(default)]
    pub state_version: u32,
}
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Artifact {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub description: String,
    pub revisions: Vec<Revision>,
    #[serde(default)]
    pub requested_domains: Vec<String>,
    #[serde(default)]
    pub approved_domains: Vec<String>,
    #[serde(default)]
    pub state: Value,
    #[serde(default)]
    pub state_version: u32,
    #[serde(default)]
    pub trusted_on_device: bool,
    pub created_at: i64,
    pub updated_at: i64,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct Submission {
    pub id: Option<String>,
    pub title: String,
    #[serde(default)]
    pub description: String,
    pub files: BTreeMap<String, String>,
    #[serde(default)]
    pub assets: BTreeMap<String, String>,
    #[serde(default = "entry")]
    pub entry: String,
    #[serde(default)]
    pub expected_revision: u32,
    #[serde(default)]
    pub state_version: u32,
    #[serde(default)]
    pub requested_domains: Vec<String>,
}
fn entry() -> String {
    "index.html".into()
}
fn valid_path(path: &str) -> bool {
    !path.is_empty()
        && path.len() < 256
        && path
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"_./-".contains(&byte))
        && path
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}
fn domain(host: &str) -> bool {
    !host.is_empty()
        && host.len() <= 253
        && !host.contains(['/', ':', '*', '@'])
        && host.contains('.')
        && host.parse::<IpAddr>().is_err()
        && host.split('.').all(|p| {
            !p.is_empty()
                && !p.starts_with('-')
                && !p.ends_with('-')
                && p.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'-')
        })
        && !host.ends_with(".localhost")
        && !host.ends_with(".local")
        && !host.ends_with(".internal")
}
pub(crate) fn validate(input: &Submission) -> Result<(), ApiError> {
    if input.title.trim().is_empty() || input.title.len() > 240 || input.description.len() > 4000 {
        return Err(bad("Invalid artifact title or description"));
    }
    if input.files.is_empty()
        || input.files.len() > 128
        || input
            .files
            .iter()
            .any(|(p, s)| !valid_path(p) || s.len() > 1024 * 1024)
        || input.files.values().map(String::len).sum::<usize>() > LIMIT
    {
        return Err(bad("Invalid artifact paths or file quota exceeded"));
    }
    if input.assets.len() > 64
        || input.assets.iter().any(|(p, id)| {
            !valid_path(p)
                || input.files.contains_key(p)
                || id.len() != 64
                || !id
                    .bytes()
                    .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        })
    {
        return Err(bad("Invalid artifact asset references"));
    }
    if !input.files.contains_key(&input.entry) || !input.entry.ends_with(".html") {
        return Err(bad("An HTML entry file is required"));
    }
    if input.requested_domains.len() > 16
        || input
            .requested_domains
            .iter()
            .any(|s| !domain(s) || s != &s.to_ascii_lowercase())
    {
        return Err(bad(
            "Only explicit lower-case public HTTPS domain names are accepted",
        ));
    }
    Ok(())
}
pub(crate) fn load(store: &Store, id: &str) -> Result<Artifact, ApiError> {
    if uuid::Uuid::parse_str(id).is_err() {
        return Err(bad("Invalid artifact ID"));
    }
    let value = store
        .kv_get(&format!("{PREFIX}{id}"))
        .map_err(ApiError::store)?
        .ok_or(ApiError::not_found("artifact"))?;
    serde_json::from_str(&value).map_err(ApiError::internal)
}
fn save(store: &Store, artifact: &Artifact) -> Result<(), ApiError> {
    store
        .kv_set(
            &format!("{PREFIX}{}", artifact.id),
            &serde_json::to_string(artifact).map_err(ApiError::internal)?,
        )
        .map_err(ApiError::store)
}
pub(crate) fn clear(store: &Store) -> Result<(), ApiError> {
    for (key, _) in store.kv_list_prefix(PREFIX).map_err(ApiError::store)? {
        store.kv_delete(&key).map_err(ApiError::store)?;
    }
    store
        .kv_delete("artifact_tools_enabled")
        .map_err(ApiError::store)
}
pub(crate) fn enabled(store: &Store) -> bool {
    store
        .kv_get("artifact_tools_enabled")
        .ok()
        .flatten()
        .as_deref()
        == Some("true")
}
pub(crate) fn tool() -> BuiltinTool {
    BuiltinTool { name: TOOL_NAME.into(), description: "Create or update an immediately rendered interactive browser application. Submit complete HTML/CSS/JavaScript files with portable ASCII resource paths (letters, digits, _./- only), use messenger.getState(), messenger.setState(value), messenger.fetch(url, options) for persistent state and authorized external HTTPS requests. Third party libraries must be supplied as fixed local files. No provider keys or native API access. expectedRevision must match when updating. Use get to read an existing application before updating.".into(), parameters_json: json!({"type":"object","properties":{"action":{"enum":["create","update","get"]},"id":{"type":"string"},"title":{"type":"string"},"description":{"type":"string"},"files":{"type":"object","additionalProperties":{"type":"string"}},"assets":{"type":"object","description":"Map local resource paths to existing asset SHA256 IDs","additionalProperties":{"type":"string"}},"entry":{"type":"string"},"expectedRevision":{"type":"integer"},"stateVersion":{"type":"integer"},"requestedDomains":{"type":"array","items":{"type":"string"}}},"required":["action"],"additionalProperties":false}).to_string(), write_access:false, read_only:false, workspace_required:false }
}
async fn submit(state: &ApiState, input: Submission) -> Result<Artifact, ApiError> {
    validate(&input)?;
    let _guard = state.agent_mutations.lock().await;
    let time = now();
    let mut artifact = if let Some(id) = &input.id {
        load(&state.store, id)?
    } else {
        Artifact {
            id: uuid::Uuid::new_v4().to_string(),
            title: input.title.clone(),
            description: String::new(),
            revisions: Vec::new(),
            requested_domains: Vec::new(),
            approved_domains: Vec::new(),
            state: Value::Null,
            state_version: input.state_version,
            trusted_on_device: true,
            created_at: time,
            updated_at: time,
        }
    };
    let current = artifact.revisions.last().map(|r| r.revision).unwrap_or(0);
    if current != input.expected_revision {
        return Err(ApiError::Conflict {
            code: "artifact_revision_conflict".into(),
            message: "Artifact changed; reload before submitting".into(),
        });
    }
    if artifact.revisions.len() >= 32 {
        return Err(bad("Artifact revision quota reached (32 versions)"));
    }
    artifact.title = input.title;
    artifact.description = input.description;
    artifact.requested_domains = input.requested_domains;
    artifact
        .approved_domains
        .retain(|s| artifact.requested_domains.contains(s));
    if artifact.state_version != input.state_version {
        artifact.state = Value::Null;
        artifact.state_version = input.state_version;
    }
    for id in input.assets.values() {
        state
            .assets
            .reference(
                id,
                crate::assets::AssetReference {
                    owner_type: "artifact".into(),
                    owner_id: artifact.id.clone(),
                },
            )
            .await?;
    }
    artifact.revisions.push(Revision {
        revision: current + 1,
        files: input.files,
        assets: input.assets,
        entry: input.entry,
        created_at: time,
        state_version: input.state_version,
    });
    artifact.updated_at = time;
    save(&state.store, &artifact)?;
    Ok(artifact)
}
pub(crate) async fn execute_tool(
    state: &ApiState,
    arguments: &str,
    cancel: CancellationToken,
) -> ToolExecutionResult {
    let result = async {
        if !enabled(&state.store) || cancel.is_cancelled() { return Err(bad("Interactive applications are disabled or cancelled")); }
        let mut value: Value = serde_json::from_str(arguments).map_err(ApiError::internal)?;
        let action = value.as_object_mut().and_then(|v|v.remove("action")).and_then(|v|v.as_str().map(str::to_owned)).ok_or(bad("action required"))?;
        let artifact = if action == "get" { load(&state.store,value["id"].as_str().ok_or(bad("id required"))?)? } else if action == "create" || action == "update" {
            let input:Submission = serde_json::from_value(value).map_err(|e|bad(e.to_string()))?;
            if (action == "update") != input.id.is_some() { return Err(bad("update requires an id; create must omit id")); }
            submit(state,input).await?
        } else { return Err(bad("Unknown artifact action")); };
        let reference = json!({"version":1,"type":"artifact","id":artifact.id,"title":artifact.title,"revision":artifact.revisions.last().map(|r|r.revision),"description":artifact.description});
        let output = if action == "get" { serde_json::to_string(&artifact).map_err(ApiError::internal)? } else { format!("Interactive application saved: {} ({}). The client can render it immediately.",artifact.title,artifact.id) };
        Ok((output,reference))
    }.await;
    match result {
        Ok((output, reference)) => ToolExecutionResult {
            output,
            is_error: false,
            structured_content: Some(reference),
        },
        Err(error) => ToolExecutionResult {
            output: format!("{error:?}"),
            is_error: true,
            ..Default::default()
        },
    }
}
pub(crate) fn routes() -> Router<ApiState> {
    Router::new()
        .route("/artifacts", get(list).post(create))
        .route("/artifacts/settings", get(settings).put(set_settings))
        .route("/artifacts/:id", get(get_one).put(update))
        .route("/artifacts/:id/state", post(set_state))
        .route("/artifacts/:id/authorize-run", post(authorize_run))
        .route("/artifacts/:id/grants", post(set_grants))
        .route("/artifacts/:id/fetch", post(fetch))
        .layer(axum::extract::DefaultBodyLimit::max(LIMIT + 1024 * 1024))
}
async fn list(State(state): State<ApiState>) -> Result<Json<Value>, ApiError> {
    let mut items = Vec::new();
    for (_, value) in state
        .store
        .kv_list_prefix(PREFIX)
        .map_err(ApiError::store)?
    {
        let a: Artifact = serde_json::from_str(&value).map_err(ApiError::internal)?;
        items.push(json!({"id":a.id,"title":a.title,"description":a.description,"revision":a.revisions.last().map(|r|r.revision),"updatedAt":a.updated_at}));
    }
    Ok(Json(json!({"items":items})))
}
async fn create(
    State(state): State<ApiState>,
    Json(input): Json<Submission>,
) -> Result<Json<Artifact>, ApiError> {
    if input.id.is_some() {
        return Err(bad("New artifact must omit id"));
    }
    Ok(Json(submit(&state, input).await?))
}
async fn update(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(mut input): Json<Submission>,
) -> Result<Json<Artifact>, ApiError> {
    if input.id.as_ref().is_some_and(|v| v != &id) {
        return Err(bad("Artifact ID mismatch"));
    }
    input.id = Some(id);
    Ok(Json(submit(&state, input).await?))
}
async fn get_one(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<Artifact>, ApiError> {
    Ok(Json(load(&state.store, &id)?))
}
async fn settings(State(state): State<ApiState>) -> Json<Value> {
    Json(json!({"enabled":enabled(&state.store)}))
}
async fn set_settings(
    State(state): State<ApiState>,
    Json(value): Json<Value>,
) -> Result<Json<Value>, ApiError> {
    let enabled = value["enabled"]
        .as_bool()
        .ok_or(bad("enabled boolean required"))?;
    state
        .store
        .kv_set(
            "artifact_tools_enabled",
            if enabled { "true" } else { "false" },
        )
        .map_err(ApiError::store)?;
    Ok(Json(json!({"enabled":enabled})))
}
async fn set_state(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(value): Json<Value>,
) -> Result<Json<Value>, ApiError> {
    if value.to_string().len() > 64 * 1024 {
        return Err(bad("Application state exceeds 64 KiB"));
    }
    let _guard = state.agent_mutations.lock().await;
    let mut a = load(&state.store, &id)?;
    if !a.trusted_on_device {
        return Err(bad("Authorize this imported application before running"));
    }
    if value["revision"]
        .as_u64()
        .is_some_and(|r| a.revisions.last().map(|v| v.revision as u64) != Some(r))
    {
        return Err(bad("Application revision changed"));
    }
    if value["stateVersion"].as_u64() != Some(a.state_version as u64) {
        return Err(bad("Application state format changed"));
    }
    a.state = value["state"].clone();
    save(&state.store, &a)?;
    Ok(Json(
        json!({"state":a.state,"stateVersion":a.state_version}),
    ))
}
async fn authorize_run(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> Result<Json<Value>, ApiError> {
    let _guard = state.agent_mutations.lock().await;
    let mut a = load(&state.store, &id)?;
    a.trusted_on_device = true;
    save(&state.store, &a)?;
    Ok(Json(json!({"authorized":true})))
}
async fn set_grants(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(value): Json<Value>,
) -> Result<Json<Value>, ApiError> {
    let domains: Vec<String> =
        serde_json::from_value(value["domains"].clone()).map_err(|e| bad(e.to_string()))?;
    let _guard = state.agent_mutations.lock().await;
    let mut a = load(&state.store, &id)?;
    if domains.iter().any(|s| !a.requested_domains.contains(s)) {
        return Err(bad("Only declared domains can be granted"));
    }
    a.approved_domains = domains;
    save(&state.store, &a)?;
    Ok(Json(json!({"domains":a.approved_domains})))
}
fn public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v) => {
            let o = v.octets();
            !v.is_private()
                && !v.is_loopback()
                && !v.is_link_local()
                && !v.is_unspecified()
                && !v.is_multicast()
                && o[0] != 0
                && o[0] < 224
                && !(o[0] == 100 && (64..128).contains(&o[1]))
                && !(o[0] == 192 && o[1] == 0)
                && !(o[0] == 198 && (o[1] == 18 || o[1] == 19))
        }
        IpAddr::V6(v) => {
            let s = v.segments();
            s[0] & 0xe000 == 0x2000 && !(s[0] == 0x2001 && (s[1] == 0 || s[1] == 0xdb8))
        }
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Fetch {
    url: String,
    #[serde(default)]
    method: String,
    #[serde(default)]
    body: Option<String>,
    #[serde(default)]
    content_type: Option<String>,
}
async fn fetch(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(input): Json<Fetch>,
) -> Result<Json<Value>, ApiError> {
    let a = load(&state.store, &id)?;
    if !a.trusted_on_device {
        return Err(bad("Authorize this imported application before running"));
    }
    let url = reqwest::Url::parse(&input.url).map_err(|e| bad(e.to_string()))?;
    let host = url
        .host_str()
        .ok_or(bad("Host required"))?
        .to_ascii_lowercase();
    if url.scheme() != "https"
        || url.port_or_known_default() != Some(443)
        || !url.username().is_empty()
        || url.password().is_some()
        || !domain(&host)
        || !a.approved_domains.contains(&host)
    {
        return Err(bad(
            "External domain is not authorized for this application",
        ));
    }
    if input.body.as_ref().is_some_and(|s| s.len() > 64 * 1024) {
        return Err(bad("Request exceeds 64 KiB"));
    }
    let addresses: Vec<SocketAddr> = tokio::time::timeout(
        Duration::from_secs(5),
        tokio::net::lookup_host((host.as_str(), 443)),
    )
    .await
    .map_err(ApiError::internal)?
    .map_err(ApiError::internal)?
    .collect();
    if addresses.is_empty() || addresses.iter().any(|a| !public_ip(a.ip())) {
        return Err(bad(
            "External requests cannot access local or reserved addresses",
        ));
    }
    let client = reqwest::Client::builder()
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(20))
        .resolve_to_addrs(&host, &addresses)
        .build()
        .map_err(ApiError::internal)?;
    let method = match input.method.as_str() {
        "" | "GET" => reqwest::Method::GET,
        "POST" => reqwest::Method::POST,
        _ => return Err(bad("Only GET and POST are supported")),
    };
    let mut request = client.request(method, url);
    if let Some(body) = input.body {
        let content_type = input
            .content_type
            .unwrap_or_else(|| "application/json".into());
        if ![
            "application/json",
            "text/plain",
            "application/x-www-form-urlencoded",
        ]
        .contains(&content_type.as_str())
        {
            return Err(bad("Unsupported request content type"));
        }
        request = request.header("Content-Type", content_type).body(body);
    }
    let response = tokio::select! {_=state.shutdown.cancelled()=>return Err(bad("Core is stopping")),r=request.send()=>r.map_err(ApiError::internal)?};
    if response.status().is_redirection() {
        return Err(bad(
            "External redirects require an explicit request to the authorized destination",
        ));
    }
    let status = response.status().as_u16();
    let mut stream = response.bytes_stream();
    let mut bytes = Vec::new();
    while let Some(chunk) = tokio::select! {_=state.shutdown.cancelled()=>return Err(bad("Core is stopping")),r=stream.next()=>r}
    {
        let chunk = chunk.map_err(ApiError::internal)?;
        if bytes.len() + chunk.len() > 1024 * 1024 {
            return Err(bad("External response exceeds 1 MiB"));
        }
        bytes.extend_from_slice(&chunk);
    }
    let body =
        String::from_utf8(bytes).map_err(|_| bad("Only UTF-8 external responses are supported"))?;
    Ok(Json(json!({"status":status,"body":body})))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn paths_and_outbound_addresses_are_confined() {
        for p in [
            "../x",
            "/x",
            "a//b",
            "a\\b",
            "a?x",
            "C:x",
            "my lesson.html",
            "@scope/library.js",
            "课程.html",
            "a%20b.js",
        ] {
            assert!(!valid_path(p));
        }
        assert!(valid_path("lib/chart.js"));
        for s in [
            "127.0.0.1",
            "10.1.2.3",
            "::1",
            "::ffff:127.0.0.1",
            "100.64.1.1",
            "fe80::1",
        ] {
            assert!(!public_ip(s.parse().unwrap()), "{s}");
        }
        assert!(public_ip("8.8.8.8".parse().unwrap()));
    }
    #[tokio::test]
    async fn revisions_are_atomic_and_state_versioned() {
        let state = ApiState::new(std::sync::Arc::new(Store::open_memory().unwrap()), "test");
        let input = || Submission {
            id: None,
            title: "Experiment".into(),
            description: String::new(),
            files: BTreeMap::from([("index.html".into(), "<button>Run</button>".into())]),
            assets: BTreeMap::new(),
            entry: entry(),
            expected_revision: 0,
            state_version: 1,
            requested_domains: vec![],
        };
        let saved = submit(&state, input()).await.unwrap();
        let mut update = input();
        update.id = Some(saved.id.clone());
        assert!(submit(&state, update).await.is_err());
        assert_eq!(load(&state.store, &saved.id).unwrap().revisions.len(), 1);
        let disabled = execute_tool(&state, r#"{"action":"get"}"#, CancellationToken::new()).await;
        assert!(disabled.is_error);
    }
}
