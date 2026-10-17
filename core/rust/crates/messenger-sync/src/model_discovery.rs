//! Provider model discovery and models.dev enrichment (port of
//! `ApiRepositoryImpl.fetchModels` + `ModelsDevRepositoryImpl`).
//!
//! Two independent steps, both owned by the core so every platform behaves
//! identically:
//!
//! 1. `GET {baseUrl}/models` against the provider's own protocol
//!    (Chat/Responses/Messages/Interactions all expose `/models`), returning
//!    the ids plus whatever context/rate extensions the provider reports.
//! 2. models.dev metadata (`https://models.dev/models.json`, 24 h cached in
//!    the store's kv table) filling the gaps: context window, modalities and
//!    capability flags.
//!
//! The result is a list of rows ready to upsert; the caller decides which of
//! them to persist (the UI lets the user tick a subset).

use std::collections::BTreeSet;

use messenger_llm::client::OpenAiClient;
use messenger_store::model::StoredModel;
use messenger_store::Store;
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// One discovered model, already enriched with models.dev metadata.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DiscoveredModel {
    /// Provider-reported model id (what the request `model` field carries).
    pub model_id: String,
    pub display_name: String,
    /// Context window in tokens; 0 = unknown/unlimited.
    pub context_window: i64,
    pub input_rate: Option<f64>,
    pub output_rate: Option<f64>,
    /// Comma-separated modality codes (`text,image`).
    pub input_modalities: String,
    pub output_modalities: String,
    pub supports_tool_calling: bool,
    pub supports_thinking: bool,
    pub supports_json_output: bool,
    pub supports_temperature: bool,
}

const MODELS_DEV_URL: &str = "https://models.dev/models.json";
const MODELS_DEV_TTL_MS: i64 = 24 * 60 * 60 * 1000;
const KV_MODELS_DEV_BODY: &str = "models_dev_body";
const KV_MODELS_DEV_FETCHED_AT: &str = "models_dev_fetched_at";

/// Fetch `GET /models` for one provider and enrich each entry with
/// models.dev metadata. Errors are returned verbatim so the UI can show the
/// provider's own message.
pub async fn discover_provider_models(
    base_url: &str,
    api_key: &str,
    api_format: Option<&str>,
) -> Result<Vec<DiscoveredModel>, String> {
    let format = messenger_llm::ApiFormat::from_store(api_format);
    let client = OpenAiClient::with_format(base_url, api_key, format);
    let response = client
        .get_models()
        .await
        .map_err(|error| error.to_string())?;
    Ok(response
        .entries()
        .into_iter()
        .map(|entry| DiscoveredModel {
            display_name: entry.id.clone(),
            model_id: entry.id,
            context_window: entry.context_window.unwrap_or(0),
            input_rate: entry.input_rate,
            output_rate: entry.output_rate,
            input_modalities: "text".to_string(),
            output_modalities: "text".to_string(),
            supports_tool_calling: false,
            supports_thinking: false,
            supports_json_output: false,
            supports_temperature: false,
        })
        .collect())
}

/// models.dev record, tolerating both the 2026 schema (`tool_call`,
/// `modalities`, `limit.context`, boolean `reasoning`/`temperature`) and the
/// legacy one (`functionCall`, `vision`, `contextWindow`, `input`/`output`).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
struct ModelsDevRecord {
    #[serde(rename = "contextWindow")]
    context_window: i64,
    reasoning: Option<bool>,
    #[serde(rename = "tool_call")]
    tool_call: Option<bool>,
    #[serde(rename = "functionCall")]
    function_call: Option<bool>,
    #[serde(rename = "structured_output")]
    structured_output: Option<bool>,
    temperature: Option<bool>,
    vision: Option<String>,
    input: Option<Value>,
    output: Option<Value>,
    modalities: Option<Value>,
    limit: Option<Value>,
}

/// Load the models.dev catalogue (network or 24 h cache), then merge it into
/// [models]. Returns the same list with gaps filled; `None` metadata leaves a
/// row untouched.
pub async fn enrich_with_models_dev(store: &Store, models: Vec<DiscoveredModel>) -> Vec<DiscoveredModel> {
    let Some(catalogue) = load_models_dev(store).await else {
        return models;
    };
    models
        .into_iter()
        .map(|model| {
            let record = catalogue.find(&model.model_id);
            apply_models_dev(model, record)
        })
        .collect()
}

/// models.dev lookup + merge, exposed for callers that already have rows
/// (the "add model manually" path).
pub async fn models_dev_metadata(store: &Store, model_id: &str) -> Option<DiscoveredModel> {
    let catalogue = load_models_dev(store).await?;
    let record = catalogue.find(model_id)?;
    Some(apply_models_dev(
        DiscoveredModel {
            model_id: model_id.to_string(),
            display_name: model_id.to_string(),
            context_window: 0,
            input_rate: None,
            output_rate: None,
            input_modalities: "text".to_string(),
            output_modalities: "text".to_string(),
            supports_tool_calling: false,
            supports_thinking: false,
            supports_json_output: false,
            supports_temperature: false,
        },
        Some(record),
    ))
}

struct ModelsDevCatalogue {
    records: std::collections::HashMap<String, ModelsDevRecord>,
}

impl ModelsDevCatalogue {
    /// Exact id first (`deepseek/deepseek-chat`), then a bare-id suffix match.
    fn find(&self, model_id: &str) -> Option<&ModelsDevRecord> {
        let trimmed = model_id.trim();
        if trimmed.is_empty() {
            return None;
        }
        self.records.get(trimmed).or_else(|| {
            self.records
                .iter()
                .find(|(key, _)| key.ends_with(&format!("/{trimmed}")))
                .map(|(_, record)| record)
        })
    }
}

fn apply_models_dev(mut model: DiscoveredModel, record: Option<&ModelsDevRecord>) -> DiscoveredModel {
    let Some(record) = record else {
        return model;
    };
    // Context: the newer `limit.context` wins, then the legacy column, and the
    // provider's own value is never overwritten when it already had one.
    if model.context_window == 0 {
        model.context_window = limit_context(record)
            .or(Some(record.context_window).filter(|value| *value > 0))
            .unwrap_or(0);
    }

    let mut input = modalities_of(record.modalities.as_ref(), "input");
    let mut output = modalities_of(record.modalities.as_ref(), "output");
    input.extend(legacy_modalities(record.input.as_ref()));
    output.extend(legacy_modalities(record.output.as_ref()));
    if matches!(record.vision.as_deref(), Some("supported") | Some("combined")) {
        input.insert("image".to_string());
    }
    if !input.is_empty() {
        model.input_modalities = join_modalities(input);
    }
    if !output.is_empty() {
        model.output_modalities = join_modalities(output);
    }

    if let Some(value) = record.tool_call.or(record.function_call) {
        model.supports_tool_calling = value;
    }
    if let Some(value) = record.reasoning {
        model.supports_thinking = value;
    }
    if let Some(value) = record.structured_output {
        model.supports_json_output = value;
    }
    if let Some(value) = record.temperature {
        model.supports_temperature = value;
    }
    model
}

fn limit_context(record: &ModelsDevRecord) -> Option<i64> {
    record
        .limit
        .as_ref()
        .and_then(|limit| limit.get("context"))
        .and_then(Value::as_i64)
        .filter(|value| *value > 0)
}

fn modalities_of(modalities: Option<&Value>, key: &str) -> BTreeSet<String> {
    let Some(array) = modalities.and_then(|value| value.get(key)).and_then(Value::as_array) else {
        return BTreeSet::new();
    };
    array
        .iter()
        .filter_map(Value::as_str)
        .filter(|code| is_modality(code))
        .map(String::from)
        .collect()
}

/// Legacy `input`/`output`: an object keyed by modality code, or an array of
/// codes / `{ "type": code }` objects.
fn legacy_modalities(value: Option<&Value>) -> BTreeSet<String> {
    let mut found = BTreeSet::new();
    match value {
        Some(Value::Object(map)) => {
            for key in map.keys() {
                if is_modality(key) {
                    found.insert(key.clone());
                }
            }
            if let Some(types) = map.get("types").and_then(Value::as_array) {
                for entry in types.iter().filter_map(Value::as_str) {
                    if is_modality(entry) {
                        found.insert(entry.to_string());
                    }
                }
            }
            if let Some(kind) = map.get("type").and_then(Value::as_str) {
                if is_modality(kind) {
                    found.insert(kind.to_string());
                }
            }
        }
        Some(Value::Array(entries)) => {
            for entry in entries {
                let code = entry
                    .as_str()
                    .or_else(|| entry.get("type").and_then(Value::as_str));
                if let Some(code) = code.filter(|code| is_modality(code)) {
                    found.insert(code.to_string());
                }
            }
        }
        _ => {}
    }
    found
}

fn is_modality(code: &str) -> bool {
    matches!(code, "text" | "image" | "audio" | "video")
}

fn join_modalities(modalities: BTreeSet<String>) -> String {
    modalities.into_iter().collect::<Vec<_>>().join(",")
}

async fn load_models_dev(store: &Store) -> Option<ModelsDevCatalogue> {
    let cached_body = store.kv_get(KV_MODELS_DEV_BODY).ok().flatten();
    let cache_fresh = store
        .kv_get(KV_MODELS_DEV_FETCHED_AT)
        .ok()
        .flatten()
        .and_then(|value| value.parse::<i64>().ok())
        .is_some_and(|at| now_ms() - at < MODELS_DEV_TTL_MS);

    if cache_fresh {
        if let Some(catalogue) = cached_body.as_deref().and_then(parse_catalogue) {
            return Some(catalogue);
        }
    }

    // The catalogue is a one-off GET with its own timeout; the sync engine's
    // client carries the session cookie, which models.dev does not need.
    let http = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .ok()?;
    match http.get(MODELS_DEV_URL).send().await {
        Ok(response) if response.status().is_success() => {
            let body = response.text().await.ok()?;
            let catalogue = parse_catalogue(&body)?;
            let _ = store.kv_set(KV_MODELS_DEV_BODY, &body);
            let _ = store.kv_set(KV_MODELS_DEV_FETCHED_AT, &now_ms().to_string());
            Some(catalogue)
        }
        // Stale cache beats no metadata at all.
        _ => cached_body.as_deref().and_then(parse_catalogue),
    }
}

fn parse_catalogue(body: &str) -> Option<ModelsDevCatalogue> {
    let records: std::collections::HashMap<String, ModelsDevRecord> =
        serde_json::from_str(body).ok()?;
    if records.is_empty() {
        return None;
    }
    Some(ModelsDevCatalogue { records })
}

fn now_ms() -> i64 {
    messenger_store::now_ms()
}

/// Convert a discovered row into a store row (deterministic id, enabled flag
/// and creation time preserved from [previous] when the model already exists).
pub fn discovered_to_stored(
    provider_id: &str,
    discovered: &DiscoveredModel,
    previous: Option<&StoredModel>,
    enabled: bool,
    now: i64,
) -> StoredModel {
    StoredModel {
        id: previous
            .map(|row| row.id.clone())
            .unwrap_or_else(|| format!("{provider_id}:{}", discovered.model_id)),
        provider_id: provider_id.to_string(),
        model_id: discovered.model_id.clone(),
        display_name: if discovered.display_name.is_empty() {
            discovered.model_id.clone()
        } else {
            discovered.display_name.clone()
        },
        is_enabled: enabled,
        context_window: discovered.context_window,
        input_rate: discovered.input_rate,
        output_rate: discovered.output_rate,
        input_modalities: discovered.input_modalities.clone(),
        output_modalities: discovered.output_modalities.clone(),
        supports_tool_calling: discovered.supports_tool_calling,
        supports_thinking: discovered.supports_thinking,
        supports_json_output: discovered.supports_json_output,
        supports_temperature: discovered.supports_temperature,
        created_at: previous.map(|row| row.created_at).unwrap_or(now),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn record(value: serde_json::Value) -> ModelsDevRecord {
        serde_json::from_value(value).unwrap()
    }

    fn blank(model_id: &str) -> DiscoveredModel {
        DiscoveredModel {
            model_id: model_id.into(),
            display_name: model_id.into(),
            context_window: 0,
            input_rate: None,
            output_rate: None,
            input_modalities: "text".into(),
            output_modalities: "text".into(),
            supports_tool_calling: false,
            supports_thinking: false,
            supports_json_output: false,
            supports_temperature: false,
        }
    }

    #[test]
    fn gemini_model_lists_flatten_into_entries() {
        let response: messenger_llm::dto::ModelsResponse = serde_json::from_value(json!({
            "models": [
                { "name": "models/gemini-3-flash" },
                { "name": "models/gemini-3-pro" }
            ]
        }))
        .unwrap();
        let ids: Vec<String> = response.entries().into_iter().map(|e| e.id).collect();
        assert_eq!(ids, ["gemini-3-flash", "gemini-3-pro"]);
    }

    #[test]
    fn openai_model_lists_keep_their_extensions() {
        let response: messenger_llm::dto::ModelsResponse = serde_json::from_value(json!({
            "data": [{ "id": "gpt-4o", "context_window": 128000, "input_rate": 2.5 }]
        }))
        .unwrap();
        let entry = response.entries().into_iter().next().unwrap();
        assert_eq!(entry.id, "gpt-4o");
        assert_eq!(entry.context_window, Some(128000));
        assert_eq!(entry.input_rate, Some(2.5));
    }

    #[test]
    fn models_dev_2026_schema_fills_gaps() {
        let enriched = apply_models_dev(
            blank("deepseek-chat"),
            Some(&record(json!({
                "reasoning": true,
                "tool_call": true,
                "structured_output": true,
                "temperature": true,
                "modalities": { "input": ["text", "image"], "output": ["text"] },
                "limit": { "context": 131072 }
            }))),
        );
        assert_eq!(enriched.context_window, 131072);
        assert_eq!(enriched.input_modalities, "image,text");
        assert_eq!(enriched.output_modalities, "text");
        assert!(enriched.supports_tool_calling);
        assert!(enriched.supports_thinking);
        assert!(enriched.supports_json_output);
        assert!(enriched.supports_temperature);
    }

    #[test]
    fn models_dev_legacy_schema_still_maps() {
        let enriched = apply_models_dev(
            blank("legacy-model"),
            Some(&record(json!({
                "contextWindow": 8192,
                "functionCall": true,
                "vision": "supported",
                "input": { "text": {}, "image": {} },
                "output": { "text": {} }
            }))),
        );
        assert_eq!(enriched.context_window, 8192);
        assert_eq!(enriched.input_modalities, "image,text");
        assert!(enriched.supports_tool_calling);
    }

    #[test]
    fn provider_reported_context_wins_over_models_dev() {
        let mut model = blank("m");
        model.context_window = 32_000;
        let enriched = apply_models_dev(model, Some(&record(json!({ "limit": { "context": 1 } }))));
        assert_eq!(enriched.context_window, 32_000);
    }

    #[test]
    fn missing_metadata_leaves_the_row_untouched() {
        let model = blank("unknown-model");
        assert_eq!(apply_models_dev(model.clone(), None), model);
    }

    #[test]
    fn catalogue_matches_exact_then_suffix() {
        let mut records = std::collections::HashMap::new();
        records.insert(
            "deepseek/deepseek-chat".to_string(),
            record(json!({ "limit": { "context": 64000 } })),
        );
        let catalogue = ModelsDevCatalogue { records };
        assert!(catalogue.find("deepseek/deepseek-chat").is_some());
        assert!(catalogue.find("deepseek-chat").is_some());
        assert!(catalogue.find("nope").is_none());
        assert!(catalogue.find("").is_none());
    }

    #[test]
    fn discovered_rows_keep_identity_and_enabled_state() {
        let previous = StoredModel {
            id: "p1:m".into(),
            provider_id: "p1".into(),
            model_id: "m".into(),
            display_name: "m".into(),
            is_enabled: false,
            context_window: 1,
            input_rate: None,
            output_rate: None,
            input_modalities: "text".into(),
            output_modalities: "text".into(),
            supports_tool_calling: false,
            supports_thinking: false,
            supports_json_output: false,
            supports_temperature: false,
            created_at: 7,
        };
        let row = discovered_to_stored("p1", &blank("m"), Some(&previous), false, 99);
        assert_eq!(row.id, "p1:m");
        assert_eq!(row.created_at, 7);
        assert!(!row.is_enabled);

        let fresh = discovered_to_stored("p1", &blank("m"), None, true, 99);
        assert_eq!(fresh.id, "p1:m");
        assert_eq!(fresh.created_at, 99);
        assert!(fresh.is_enabled);
    }
}
