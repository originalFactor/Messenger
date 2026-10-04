//! Built-in cloud AI provider model auto-sync (port of `syncBuiltinProviderModels`).
//!
//! Fetches `GET {server}/v1/models` using the user's `aiApiKey`, preserving existing
//! rows' deterministic IDs and user `isEnabled` choices. Throttled to once per hour
//! unless forced.

use messenger_llm::client::OpenAiClient;
use messenger_store::model::StoredModel;

use crate::models::BUILTIN_PROVIDER_ID;
use crate::sync::SyncEngine;

pub const BUILTIN_MODELS_SYNC_INTERVAL_MS: i64 = 60 * 60 * 1000; // 1 hour
pub const KV_LAST_BUILTIN_MODELS_SYNC_AT: &str = "last_builtin_models_sync_at";

impl<'a> SyncEngine<'a> {
    pub async fn sync_builtin_provider_models(&self, force: bool) -> Result<usize, String> {
        let provider = match self.store.get_provider(BUILTIN_PROVIDER_ID).map_err(|e| e.to_string())? {
            Some(p) if !p.api_key.trim().is_empty() => p,
            _ => return Ok(0),
        };

        let now = now_ms();
        let existing = self
            .store
            .list_models_by_provider(BUILTIN_PROVIDER_ID)
            .map_err(|e| e.to_string())?;

        let last_sync = self
            .store
            .kv_get(KV_LAST_BUILTIN_MODELS_SYNC_AT)
            .ok()
            .flatten()
            .and_then(|s| s.parse::<i64>().ok())
            .unwrap_or(0);

        if !force && !existing.is_empty() && (now - last_sync) < BUILTIN_MODELS_SYNC_INTERVAL_MS {
            return Ok(existing.len());
        }

        let client = OpenAiClient::new(&provider.base_url, &provider.api_key);
        let models_resp = match client.get_models().await {
            Ok(resp) => resp,
            Err(e) => {
                return Err(format!("Built-in model sync failed: {e}"));
            }
        };

        let existing_map: std::collections::HashMap<String, StoredModel> = existing
            .into_iter()
            .map(|m| (m.model_id.clone(), m))
            .collect();

        let mut count = 0;
        // Delete all old ones first to clean up removed models
        for m in existing_map.values() {
            let _ = self.store.delete_model(&m.id);
        }

        for dto in models_resp.data {
            let prev = existing_map.get(&dto.id);
            let row = StoredModel {
                id: prev
                    .map(|p| p.id.clone())
                    .unwrap_or_else(|| format!("{BUILTIN_PROVIDER_ID}:{}", dto.id)),
                provider_id: BUILTIN_PROVIDER_ID.to_string(),
                model_id: dto.id.clone(),
                display_name: dto.id.clone(),
                is_enabled: prev.map(|p| p.is_enabled).unwrap_or(true),
                context_window: dto.context_window.unwrap_or(0),
                input_rate: dto.input_rate.or_else(|| prev.and_then(|p| p.input_rate)),
                output_rate: dto.output_rate.or_else(|| prev.and_then(|p| p.output_rate)),
                input_modalities: "text".to_string(),
                output_modalities: "text".to_string(),
                supports_tool_calling: false,
                supports_thinking: false,
                supports_json_output: false,
                supports_temperature: false,
                created_at: prev.map(|p| p.created_at).unwrap_or(now),
            };
            self.store.upsert_model(&row).map_err(|e| e.to_string())?;
            count += 1;
        }

        let _ = self
            .store
            .kv_set(KV_LAST_BUILTIN_MODELS_SYNC_AT, &now.to_string());

        Ok(count)
    }
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::Session;
    use crate::models::CloudUser;
    use messenger_store::model::StoredProvider;
    use messenger_store::Store;

    #[tokio::test]
    async fn sync_builtin_models_fetches_and_preserves_enabled_state() {
        let server = wiremock::MockServer::start().await;
        let store = Store::open_memory().unwrap();
        let engine = SyncEngine::new(&store, Session::default());

        store
            .upsert_provider(&StoredProvider {
                id: BUILTIN_PROVIDER_ID.into(),
                name: "Builtin".into(),
                base_url: format!("{}/v1", server.uri()),
                api_key: "sk-test".into(),
                created_at: 1,
                updated_at: 1,
            })
            .unwrap();

        // Seed a pre-existing model that user explicitly disabled
        store
            .upsert_model(&StoredModel {
                id: format!("{BUILTIN_PROVIDER_ID}:gpt-4o"),
                provider_id: BUILTIN_PROVIDER_ID.into(),
                model_id: "gpt-4o".into(),
                display_name: "gpt-4o".into(),
                is_enabled: false,
                context_window: 1000,
                input_rate: None,
                output_rate: None,
                input_modalities: "text".into(),
                output_modalities: "text".into(),
                supports_tool_calling: false,
                supports_thinking: false,
                supports_json_output: false,
                supports_temperature: false,
                created_at: 1,
            })
            .unwrap();

        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .and(wiremock::matchers::path("/v1/models"))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "data": [
                    {"id": "gpt-4o", "context_window": 128000, "input_rate": 0.5},
                    {"id": "claude-3-5", "context_window": 200000}
                ]
            })))
            .mount(&server)
            .await;

        let count = engine.sync_builtin_provider_models(true).await.unwrap();
        assert_eq!(count, 2);

        let gpt4 = store.get_model(&format!("{BUILTIN_PROVIDER_ID}:gpt-4o")).unwrap().unwrap();
        assert_eq!(gpt4.context_window, 128000);
        assert_eq!(gpt4.input_rate, Some(0.5));
        assert!(!gpt4.is_enabled, "disabled state must survive sync");

        let claude = store.get_model(&format!("{BUILTIN_PROVIDER_ID}:claude-3-5")).unwrap().unwrap();
        assert_eq!(claude.context_window, 200000);
        assert!(claude.is_enabled);
    }
}
