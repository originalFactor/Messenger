//! Bearer-protected device-local learning workbench and workspace-free Agent adapter.
use super::*;
use messenger_store::learning::*;
use serde_json::json;

pub(crate) const TOOL_NAME: &str = "messenger_learning";
const ENABLED_KEY: &str = "learning_tools_enabled";
pub(crate) fn routes() -> Router<ApiState> {
    Router::new()
        .route("/learning/export", axum::routing::get(export))
        .route("/learning/import/preview", post(import_preview))
        .route("/learning/import", post(import_commit))
        .route("/learning/overview", axum::routing::get(overview))
        .route(
            "/learning/settings",
            axum::routing::get(get_settings).put(put_settings),
        )
        .route("/learning/records/:kind", axum::routing::get(list))
        .route(
            "/learning/records/:kind/:id",
            axum::routing::get(get).put(put).delete(delete),
        )
        .route(
            "/learning/reviews/:id/state",
            axum::routing::get(review_state),
        )
        .route("/learning/reviews/:id/submit", post(submit))
        .route(
            "/learning/reviews/:id/attempts",
            axum::routing::get(attempts),
        )
        .layer(axum::extract::DefaultBodyLimit::max(16 * 1024 * 1024))
}
pub(crate) fn enabled(store: &Store) -> bool {
    store.kv_get(ENABLED_KEY).ok().flatten().as_deref() == Some("true")
}
fn error(e: LearningError) -> ApiError {
    match e {
        LearningError::Storage(e) => ApiError::store(e),
        LearningError::Invalid(s) => ApiError::BadRequest(s),
        LearningError::NotFound => ApiError::not_found("learning record"),
        LearningError::Conflict(message) => ApiError::Conflict {
            code: "learning_conflict".into(),
            message,
        },
    }
}
fn kind(s: &str) -> std::result::Result<LearningKind, ApiError> {
    serde_json::from_value(json!(s))
        .map_err(|_| ApiError::BadRequest("unknown learning kind".into()))
}
fn require_kind(
    store: &Store,
    k: LearningKind,
    id: &str,
) -> std::result::Result<LearningRecord, ApiError> {
    let r = store
        .get_learning(id)
        .map_err(error)?
        .ok_or_else(|| ApiError::not_found("learning record"))?;
    if r.content.kind() != k {
        return Err(ApiError::not_found("learning record"));
    }
    Ok(r)
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Settings {
    enabled: bool,
}
pub(crate) async fn get_settings(State(state): State<ApiState>) -> Json<Settings> {
    Json(Settings {
        enabled: enabled(&state.store),
    })
}
pub(crate) async fn put_settings(
    State(state): State<ApiState>,
    Json(body): Json<Settings>,
) -> std::result::Result<Json<Settings>, ApiError> {
    state
        .store
        .kv_set(ENABLED_KEY, if body.enabled { "true" } else { "false" })
        .map_err(ApiError::store)?;
    Ok(Json(body))
}
pub(crate) async fn overview(
    State(state): State<ApiState>,
) -> std::result::Result<Json<LearningOverview>, ApiError> {
    Ok(Json(
        state.store.learning_overview(now_ms()).map_err(error)?,
    ))
}
pub(crate) async fn list(
    State(state): State<ApiState>,
    Path(k): Path<String>,
    Query(q): Query<LearningQuery>,
) -> std::result::Result<Json<LearningPage>, ApiError> {
    Ok(Json(
        state.store.list_learning(kind(&k)?, &q).map_err(error)?,
    ))
}
pub(crate) async fn get(
    State(state): State<ApiState>,
    Path((k, id)): Path<(String, String)>,
) -> std::result::Result<Json<LearningRecord>, ApiError> {
    Ok(Json(require_kind(&state.store, kind(&k)?, &id)?))
}
/// Caller holds domain and asset gates. Retain owners for historical revisions too.
fn resource_entries<'a>(
    state: &ApiState,
    records: impl IntoIterator<Item = &'a LearningRecord>,
) -> std::result::Result<Vec<(String, String)>, ApiError> {
    let mut assets = std::collections::BTreeMap::<String, crate::assets::Asset>::new();
    for record in records {
        let mut ids = std::collections::BTreeSet::new();
        if let Some(source) = &record.source {
            ids.extend(source.asset_ids.iter().cloned());
        }
        if let LearningContent::Material {
            asset_id,
            artifact_id,
            ..
        } = &record.content
        {
            if let Some(id) = asset_id {
                ids.insert(id.clone());
            }
            if let Some(id) = artifact_id {
                crate::artifacts::load(&state.store, id)?;
            }
        }
        if ids.len() > 256 {
            return Err(ApiError::BadRequest(
                "learning source exceeds 256 owned assets".into(),
            ));
        }
        for id in ids {
            if !assets.contains_key(&id) {
                let asset = state.assets.get(&id)?;
                state.assets.read_bytes(&id)?;
                assets.insert(id.clone(), asset);
            }
            let asset = assets.get_mut(&id).expect("asset inserted above");
            let owner = crate::assets::AssetReference {
                owner_type: "learning".into(),
                owner_id: record.id.clone(),
            };
            if !asset.references.contains(&owner) {
                if asset.references.len() >= 10000 {
                    return Err(ApiError::BadRequest(
                        "asset reference quota exceeded".into(),
                    ));
                }
                asset.references.push(owner);
            }
            asset.unreferenced_at = None;
        }
    }
    assets
        .into_iter()
        .map(|(id, asset)| {
            Ok((
                format!("local_asset:{id}"),
                serde_json::to_string(&asset).map_err(ApiError::internal)?,
            ))
        })
        .collect()
}
async fn save_owned(
    state: &ApiState,
    record: LearningRecord,
) -> std::result::Result<LearningRecord, ApiError> {
    let _domain = state.agent_mutations.lock().await;
    let _assets = state.assets.gate.lock().await;
    let entries = resource_entries(state, std::iter::once(&record))?;
    state
        .store
        .upsert_learning_with_local_kv(record, now_ms(), &entries)
        .map_err(error)
}
pub(crate) async fn put(
    State(state): State<ApiState>,
    Path((k, id)): Path<(String, String)>,
    Json(body): Json<LearningRecord>,
) -> std::result::Result<Json<LearningRecord>, ApiError> {
    if id != body.id || kind(&k)? != body.content.kind() {
        return Err(ApiError::BadRequest(
            "learning path must match body id/kind".into(),
        ));
    }
    Ok(Json(save_owned(&state, body).await?))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct DeleteQuery {
    version: i64,
}
pub(crate) async fn delete(
    State(state): State<ApiState>,
    Path((k, id)): Path<(String, String)>,
    Query(q): Query<DeleteQuery>,
) -> std::result::Result<StatusCode, ApiError> {
    let _domain = state.agent_mutations.lock().await;
    let _assets = state.assets.gate.lock().await;
    require_kind(&state.store, kind(&k)?, &id)?;
    let history: std::collections::HashSet<_> = state
        .store
        .learning_attempt_asset_ids(&id)
        .map_err(error)?
        .into_iter()
        .collect();
    let owner = crate::assets::AssetReference {
        owner_type: "learning".into(),
        owner_id: id.clone(),
    };
    let mut entries = Vec::new();
    let now = now_ms();
    for mut asset in state.assets.manifest()? {
        if !asset.references.contains(&owner) || history.contains(&asset.id) {
            continue;
        }
        asset.references.retain(|reference| reference != &owner);
        asset.unreferenced_at = if asset.references.is_empty() {
            Some(now)
        } else {
            None
        };
        entries.push((
            format!("local_asset:{}", asset.id),
            serde_json::to_string(&asset).map_err(ApiError::internal)?,
        ));
    }
    state
        .store
        .delete_learning_with_local_kv(&id, q.version, &entries)
        .map_err(error)?;
    Ok(StatusCode::NO_CONTENT)
}
pub(crate) async fn review_state(
    State(state): State<ApiState>,
    Path(id): Path<String>,
) -> std::result::Result<Json<ReviewState>, ApiError> {
    require_kind(&state.store, LearningKind::ReviewCard, &id)?;
    Ok(Json(
        state
            .store
            .learning_review_state(&id)
            .map_err(error)?
            .ok_or_else(|| ApiError::not_found("review state"))?,
    ))
}
pub(crate) async fn submit(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Json(body): Json<ReviewSubmission>,
) -> std::result::Result<Json<ReviewAttempt>, ApiError> {
    if id != body.card_id {
        return Err(ApiError::BadRequest("review path must match cardId".into()));
    }
    Ok(Json(
        messenger_core::learning::submit_review(&state.store, body, now_ms()).map_err(error)?,
    ))
}
#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct PageQuery {
    offset: Option<usize>,
    limit: Option<usize>,
}
pub(crate) async fn attempts(
    State(state): State<ApiState>,
    Path(id): Path<String>,
    Query(q): Query<PageQuery>,
) -> std::result::Result<Json<Value>, ApiError> {
    Ok(Json(
        json!({"items":state.store.learning_attempts(&id,q.offset.unwrap_or(0),q.limit.unwrap_or(50)).map_err(error)?}),
    ))
}
pub(crate) async fn export(
    State(state): State<ApiState>,
) -> std::result::Result<Json<LearningPackage>, ApiError> {
    Ok(Json(state.store.export_learning().map_err(error)?))
}
pub(crate) async fn import_preview(
    State(state): State<ApiState>,
    Json(body): Json<LearningPackage>,
) -> std::result::Result<Json<LearningImportPreview>, ApiError> {
    let _domain = state.agent_mutations.lock().await;
    let _assets = state.assets.gate.lock().await;
    let entries = resource_entries(&state, body.records.iter().chain(&body.revisions))?;
    Ok(Json(
        state
            .store
            .import_learning_with_local_kv(&body, false, &entries)
            .map_err(error)?,
    ))
}
pub(crate) async fn import_commit(
    State(state): State<ApiState>,
    Json(body): Json<LearningPackage>,
) -> std::result::Result<Json<LearningImportPreview>, ApiError> {
    let _domain = state.agent_mutations.lock().await;
    let _assets = state.assets.gate.lock().await;
    let entries = resource_entries(&state, body.records.iter().chain(&body.revisions))?;
    Ok(Json(
        state
            .store
            .import_learning_with_local_kv(&body, true, &entries)
            .map_err(error)?,
    ))
}
pub(crate) fn tool() -> messenger_tools::BuiltinTool {
    messenger_tools::BuiltinTool{name:TOOL_NAME.into(),description:"Query the device-local learning library or save editable learning drafts (courses, knowledge points, mistakes, notes, exercises, materials, study plans/tasks, review cards). Requires the learning feature switch. No workspace required. Never assigns a review rating or claims mastery. Sources must include an independent snapshot. Upserts use version 0 to create or the current version to update; content.kind selects the typed content schema. Read a record before updating. This tool cannot delete records or submit review scores.".into(),parameters_json:json!({"type":"object","properties":{"action":{"type":"string","enum":["overview","list","get","upsert"]},"kind":{"type":"string","enum":["course","knowledgePoint","item","material","plan","task","reviewCard"]},"id":{"type":"string"},"query":{"type":"object","properties":{"courseId":{"type":"string"},"knowledgePointId":{"type":"string"},"tag":{"type":"string"},"search":{"type":"string"},"archived":{"type":"boolean"},"dueBefore":{"type":"integer"},"offset":{"type":"integer"},"limit":{"type":"integer"}},"additionalProperties":false},"record":{"type":"object","description":"LearningRecord: id, version, title, optional courseId/knowledgePointIds/tags/archived/source, content. content discriminated by kind: course {description}; knowledgePoint {parentId?,description,position}; item {itemKind:mistake|note|exercise,question,originalAnswer,correctAnswer,explanation,errorReason}; material {materialKind:text|image|file|link|artifact,text,url?,assetId?,artifactId?}; plan {goal,targetAt?}; task {planId,itemId?,status:pending|inProgress|completed,position,dueAt?}; reviewCard {itemId,paused}. source {kind:chat|acp|manual|import,conversationId?,messageIds:[],snapshot,assetIds:[]}. Times UTC milliseconds."}},"required":["action"],"additionalProperties":false}).to_string(),write_access:false,read_only:false,workspace_required:false}
}
pub(crate) async fn execute_tool(state: &ApiState, args: &str) -> ToolExecutionResult {
    let store = &state.store;
    #[derive(Deserialize)]
    #[serde(deny_unknown_fields)]
    struct Args {
        action: String,
        kind: Option<LearningKind>,
        id: Option<String>,
        query: Option<LearningQuery>,
        record: Option<LearningRecord>,
    }
    let result = async {
        if !enabled(store) {
            return Err(ApiError::BadRequest(
                "learning tool capability is disabled".into(),
            ));
        }
        let a: Args =
            serde_json::from_str(args).map_err(|e| ApiError::BadRequest(e.to_string()))?;
        let required_kind = || {
            a.kind
                .ok_or_else(|| ApiError::BadRequest("kind required".into()))
        };
        match a.action.as_str() {
            "overview" => serde_json::to_value(store.learning_overview(now_ms()).map_err(error)?)
                .map_err(ApiError::internal),
            "list" => {
                let page = store
                    .list_learning(required_kind()?, &a.query.unwrap_or_default())
                    .map_err(error)?;
                let items:Vec<_>=page.items.iter().map(|r|json!({"id":r.id,"version":r.version,"title":r.title,"kind":r.content.kind(),"courseId":r.course_id,"tags":r.tags,"archived":r.archived})).collect();
                Ok(
                    json!({"items":items,"total":page.total,"offset":page.offset,"limit":page.limit}),
                )
            }
            "get" => serde_json::to_value(require_kind(
                store,
                required_kind()?,
                a.id.as_deref()
                    .ok_or_else(|| ApiError::BadRequest("id required".into()))?,
            )?)
            .map_err(ApiError::internal),
            "upsert" => {
                let r = a
                    .record
                    .ok_or_else(|| ApiError::BadRequest("record required".into()))?;
                if a.kind.is_some_and(|k| k != r.content.kind())
                    || a.id.as_ref().is_some_and(|id| id != &r.id)
                {
                    return Err(ApiError::BadRequest("id/kind does not match record".into()));
                }
                let saved = save_owned(state,r).await?;
                Ok(
                    json!({"id":saved.id,"version":saved.version,"title":saved.title,"content":{"kind":saved.content.kind()}}),
                )
            }
            _ => Err(ApiError::BadRequest(
                "unsupported learning action; review scoring requires user feedback".into(),
            )),
        }
    }.await;
    match result {
        Ok(value) => {
            let structured_content = value.get("id").and_then(Value::as_str).map(|id| {
                json!({
                    "version":1,"type":"learningRecord","id":id,
                    "kind":value["content"]["kind"],"recordVersion":value["version"]
                })
            });
            ToolExecutionResult {
                output: value.to_string(),
                is_error: false,
                structured_content,
            }
        }
        Err(e) => ToolExecutionResult {
            output: format!("{e:?}"),
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
        http::{Method, Request},
    };
    use http_body_util::BodyExt;
    use tower::ServiceExt;
    async fn request(
        state: ApiState,
        method: Method,
        path: &str,
        body: Value,
        auth: bool,
    ) -> (StatusCode, Value) {
        let mut builder = Request::builder()
            .method(method)
            .uri(format!("/api/v1{path}"))
            .header("content-type", "application/json");
        if auth {
            builder = builder.header("Authorization", "Bearer test");
        }
        let response = router(state)
            .oneshot(builder.body(Body::from(body.to_string())).unwrap())
            .await
            .unwrap();
        let status = response.status();
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        (
            status,
            serde_json::from_slice(&bytes).unwrap_or(Value::Null),
        )
    }
    #[test]
    fn workbench_openapi_parses_and_local_references_resolve() {
        let contract: Value = serde_yaml_ng::from_str(include_str!("../openapi.yaml")).unwrap();
        fn check(value: &Value, root: &Value) {
            match value {
                Value::Object(map) => {
                    if let Some(reference) = map
                        .get("$ref")
                        .and_then(Value::as_str)
                        .and_then(|r| r.strip_prefix('#'))
                    {
                        assert!(
                            root.pointer(reference).is_some(),
                            "unresolved schema reference {reference}"
                        );
                    }
                    for child in map.values() {
                        check(child, root);
                    }
                }
                Value::Array(items) => {
                    for item in items {
                        check(item, root);
                    }
                }
                _ => {}
            }
        }
        check(&contract, &contract);
        assert_eq!(
            contract["paths"]["/learning/reviews/{id}/submit"]["post"]["requestBody"]["content"]
                ["application/json"]["schema"]["$ref"],
            "#/components/schemas/ReviewSubmission"
        );
    }
    #[tokio::test]
    async fn learning_delete_releases_owners_atomically_and_preserves_review_history() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "test");
        let owner = |id: &str| crate::assets::AssetReference {
            owner_type: "learning".into(),
            owner_id: id.into(),
        };
        let resource = state
            .assets
            .import_bytes(b"temporary material", "text/plain", owner("material"))
            .await
            .unwrap();
        let material:LearningRecord=serde_json::from_value(json!({"id":"material","title":"Remove me","content":{"kind":"material","materialKind":"file","assetId":resource.id}})).unwrap();
        save_owned(&state, material).await.unwrap();
        assert_eq!(
            request(
                state.clone(),
                Method::DELETE,
                "/learning/records/material/material?version=1",
                Value::Null,
                true
            )
            .await
            .0,
            StatusCode::NO_CONTENT
        );
        let released = state.assets.get(&resource.id).unwrap();
        assert!(released.references.is_empty());
        let released_at = released.unreferenced_at.unwrap();
        assert!(!state
            .store
            .export_learning()
            .unwrap()
            .revisions
            .iter()
            .any(|r| r.id == "material"));
        assert_eq!(state.assets.gc(released_at + 86_399_999).await.unwrap(), 0);
        assert_eq!(state.assets.gc(released_at + 86_400_000).await.unwrap(), 1);
        let source = state
            .assets
            .import_bytes(b"review source", "text/plain", owner("item"))
            .await
            .unwrap();
        let item:LearningRecord=serde_json::from_value(json!({"id":"item","title":"Review","content":{"kind":"item","itemKind":"exercise"},"source":{"kind":"manual","snapshot":"Retained prompt","assetIds":[source.id]}})).unwrap();
        save_owned(&state, item).await.unwrap();
        let card:LearningRecord=serde_json::from_value(json!({"id":"card","title":"Review card","content":{"kind":"reviewCard","itemId":"item"}})).unwrap();
        save_owned(&state, card).await.unwrap();
        assert_eq!(
            request(
                state.clone(),
                Method::DELETE,
                "/learning/records/item/item?version=1",
                Value::Null,
                true
            )
            .await
            .0,
            StatusCode::CONFLICT
        );
        assert!(state
            .assets
            .get(&source.id)
            .unwrap()
            .references
            .contains(&owner("item")));
        assert!(state
            .assets
            .get(&source.id)
            .unwrap()
            .unreferenced_at
            .is_none());
        messenger_core::learning::submit_review(
            &state.store,
            ReviewSubmission {
                submission_id: "retain-history".into(),
                card_id: "card".into(),
                item_version: 1,
                state_version: 0,
                answer: "answer".into(),
                rating: ReviewRating::Good,
            },
            now_ms(),
        )
        .unwrap();
        assert_eq!(
            request(
                state.clone(),
                Method::DELETE,
                "/learning/records/reviewCard/card?version=1",
                Value::Null,
                true
            )
            .await
            .0,
            StatusCode::NO_CONTENT
        );
        assert_eq!(
            request(
                state.clone(),
                Method::DELETE,
                "/learning/records/item/item?version=1",
                Value::Null,
                true
            )
            .await
            .0,
            StatusCode::NO_CONTENT
        );
        assert!(state
            .assets
            .get(&source.id)
            .unwrap()
            .references
            .contains(&owner("item")));
        assert_eq!(
            state.store.learning_attempts("card", 0, 10).unwrap().len(),
            1
        );
        assert_eq!(state.assets.gc(i64::MAX).await.unwrap(), 0);
        let shared = state
            .assets
            .import_bytes(b"shared material", "text/plain", owner("shared"))
            .await
            .unwrap();
        state
            .assets
            .reference(
                &shared.id,
                crate::assets::AssetReference {
                    owner_type: "manual".into(),
                    owner_id: "keep".into(),
                },
            )
            .await
            .unwrap();
        save_owned(&state,serde_json::from_value(json!({"id":"shared","title":"Shared","content":{"kind":"material","materialKind":"file","assetId":shared.id}})).unwrap()).await.unwrap();
        assert_eq!(
            request(
                state.clone(),
                Method::DELETE,
                "/learning/records/material/shared?version=1",
                Value::Null,
                true
            )
            .await
            .0,
            StatusCode::NO_CONTENT
        );
        let shared = state.assets.get(&shared.id).unwrap();
        assert_eq!(shared.references.len(), 1);
        assert_eq!(shared.references[0].owner_id, "keep");
        assert!(shared.unreferenced_at.is_none());
    }
    #[tokio::test]
    async fn learning_resource_pins_commit_with_record_and_conflict_rolls_back() {
        let state = ApiState::new(Arc::new(Store::open_memory().unwrap()), "test");
        let imported = crate::assets::AssetReference {
            owner_type: "import".into(),
            owner_id: "test".into(),
        };
        let first = state
            .assets
            .import_bytes(b"independent original", "text/plain", imported.clone())
            .await
            .unwrap();
        let next = state
            .assets
            .import_bytes(b"new resource", "text/plain", imported)
            .await
            .unwrap();
        let body = json!({"id":"material","title":"Owned text","content":{"kind":"material","materialKind":"file","assetId":first.id}});
        assert_eq!(
            request(
                state.clone(),
                Method::PUT,
                "/learning/records/material/material",
                body.clone(),
                true
            )
            .await
            .0,
            StatusCode::OK
        );
        assert!(state
            .assets
            .get(&first.id)
            .unwrap()
            .references
            .iter()
            .any(|r| r.owner_type == "learning" && r.owner_id == "material"));
        let mut stale = body;
        stale["content"]["assetId"] = json!(next.id);
        assert_eq!(
            request(
                state.clone(),
                Method::PUT,
                "/learning/records/material/material",
                stale,
                true
            )
            .await
            .0,
            StatusCode::CONFLICT
        );
        assert!(!state
            .assets
            .get(&next.id)
            .unwrap()
            .references
            .iter()
            .any(|r| r.owner_type == "learning"));
        let missing = json!({"id":"missing","title":"Unavailable","content":{"kind":"material","materialKind":"file","assetId":"0".repeat(64)}});
        assert_eq!(
            request(
                state.clone(),
                Method::PUT,
                "/learning/records/material/missing",
                missing,
                true
            )
            .await
            .0,
            StatusCode::NOT_FOUND
        );
        assert!(state.store.get_learning("missing").unwrap().is_none());
        state.store.kv_set(ENABLED_KEY, "true").unwrap();
        let tool=execute_tool(&state,&json!({"action":"upsert","record":{"id":"source-note","title":"Snapshot","content":{"kind":"item","itemKind":"note"},"source":{"kind":"acp","snapshot":"Independent source","assetIds":[next.id]}}}).to_string()).await;
        assert!(!tool.is_error, "{}", tool.output);
        assert!(state
            .assets
            .get(&next.id)
            .unwrap()
            .references
            .iter()
            .any(|r| r.owner_id == "source-note"));
    }
    #[tokio::test]
    async fn user_review_and_metadata_package_roundtrip() {
        let store = Arc::new(Store::open_memory().unwrap());
        let state = ApiState::new(store.clone(), "test");
        for (kind, id, content) in [
            (
                "item",
                "i",
                json!({"kind":"item","itemKind":"exercise","question":"2+2"}),
            ),
            ("reviewCard", "r", json!({"kind":"reviewCard","itemId":"i"})),
        ] {
            let (status, _) = request(
                state.clone(),
                Method::PUT,
                &format!("/learning/records/{kind}/{id}"),
                json!({"id":id,"title":id,"content":content}),
                true,
            )
            .await;
            assert_eq!(status, StatusCode::OK);
        }
        let (status, initial) = request(
            state.clone(),
            Method::GET,
            "/learning/reviews/r/state",
            Value::Null,
            true,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(initial["version"], 0);
        let body = json!({"submissionId":"s","cardId":"r","itemVersion":1,"stateVersion":0,"answer":"4","rating":"good"});
        let (status, attempt) = request(
            state.clone(),
            Method::POST,
            "/learning/reviews/r/submit",
            body.clone(),
            true,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(attempt["state"]["version"], 1);
        assert_eq!(
            request(
                state.clone(),
                Method::POST,
                "/learning/reviews/r/submit",
                body.clone(),
                true
            )
            .await
            .1,
            attempt
        );
        let mut stale = body;
        stale["submissionId"] = json!("other");
        assert_eq!(
            request(
                state.clone(),
                Method::POST,
                "/learning/reviews/r/submit",
                stale,
                true
            )
            .await
            .0,
            StatusCode::CONFLICT
        );
        let (_, package) = request(
            state.clone(),
            Method::GET,
            "/learning/export",
            Value::Null,
            true,
        )
        .await;
        let other = ApiState::new(Arc::new(Store::open_memory().unwrap()), "test");
        assert_eq!(
            request(
                other.clone(),
                Method::POST,
                "/learning/import/preview",
                package.clone(),
                true
            )
            .await
            .0,
            StatusCode::OK
        );
        assert_eq!(
            request(
                other.clone(),
                Method::GET,
                "/learning/overview",
                Value::Null,
                true
            )
            .await
            .1["items"],
            0
        );
        assert_eq!(
            request(
                other.clone(),
                Method::POST,
                "/learning/import",
                package,
                true
            )
            .await
            .0,
            StatusCode::OK
        );
        assert_eq!(
            request(
                other.clone(),
                Method::GET,
                "/learning/reviews/r/attempts",
                Value::Null,
                true
            )
            .await
            .1["items"][0],
            attempt
        );
        assert_eq!(
            request(
                other.clone(),
                Method::DELETE,
                "/local-data",
                Value::Null,
                true
            )
            .await
            .0,
            StatusCode::NO_CONTENT
        );
        assert_eq!(
            request(other, Method::GET, "/learning/overview", Value::Null, true)
                .await
                .1["items"],
            0
        );
        store.kv_set(ENABLED_KEY, "true").unwrap();
        let result = execute_tool(&state, r#"{"action":"get","kind":"item","id":"i"}"#).await;
        assert!(!result.is_error);
        assert_eq!(
            result.structured_content.unwrap(),
            json!({"version":1,"type":"learningRecord","id":"i","kind":"item","recordVersion":1})
        );
    }
    #[tokio::test]
    async fn authenticated_crud_conflict_and_tool_gate() {
        let store = Arc::new(Store::open_memory().unwrap());
        let state = ApiState::new(store.clone(), "test");
        assert_eq!(
            request(
                state.clone(),
                Method::GET,
                "/learning/overview",
                Value::Null,
                false
            )
            .await
            .0,
            StatusCode::UNAUTHORIZED
        );
        let r = json!({"id":"c","title":"Algebra","content":{"kind":"course","description":"Functions"}});
        let (status, saved) = request(
            state.clone(),
            Method::PUT,
            "/learning/records/course/c",
            r.clone(),
            true,
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(saved["version"], 1);
        assert_eq!(
            request(
                state.clone(),
                Method::PUT,
                "/learning/records/course/c",
                r,
                true
            )
            .await
            .0,
            StatusCode::CONFLICT
        );
        let (_, page) = request(
            state.clone(),
            Method::GET,
            "/learning/records/course?search=Algebra&limit=1",
            Value::Null,
            true,
        )
        .await;
        assert_eq!(page["total"], 1);
        assert!(
            execute_tool(&state, r#"{"action":"overview"}"#)
                .await
                .is_error
        );
        store.kv_set(ENABLED_KEY, "true").unwrap();
        assert!(
            !execute_tool(&state, r#"{"action":"overview"}"#)
                .await
                .is_error
        );
        let summaries = execute_tool(&state, r#"{"action":"list","kind":"course"}"#).await;
        let summaries: Value = serde_json::from_str(&summaries.output).unwrap();
        assert_eq!(summaries["items"][0]["title"], "Algebra");
        assert!(summaries["items"][0].get("content").is_none());
        assert!(
            execute_tool(&state, r#"{"action":"submit","rating":"easy"}"#)
                .await
                .is_error
        );
        assert!(
            execute_tool(&state, r#"{"action":"delete","id":"c"}"#)
                .await
                .is_error
        );
        assert_eq!(
            request(
                state,
                Method::DELETE,
                "/learning/records/course/c?version=1",
                Value::Null,
                true
            )
            .await
            .0,
            StatusCode::NO_CONTENT
        );
    }
}
