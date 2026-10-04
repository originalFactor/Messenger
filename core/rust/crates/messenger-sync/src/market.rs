//! Agent Market integration (port of market methods in `CloudSyncRepository.kt`).

use messenger_store::model::StoredAgent;

use crate::api::{CloudError, CloudResult};
use crate::models::{
    CloudMarketAgent, CloudMarketAgentListResponse, CloudMarketAgentRequest,
    CloudMarketAgentUpdate,
};
use crate::sync::SyncEngine;

impl<'a> SyncEngine<'a> {
    pub async fn list_market_agents(
        &self,
        query: &str,
        cursor: Option<&str>,
    ) -> CloudResult<CloudMarketAgentListResponse> {
        let _ = self.signed_in()?;
        let endpoint = self.endpoint("api/market/agents");
        self.client.list_market_agents(&endpoint, query.trim(), cursor).await
    }

    pub async fn get_market_agent(&self, id: &str) -> CloudResult<CloudMarketAgent> {
        let _ = self.signed_in()?;
        let endpoint = self.endpoint(&format!("api/market/agents/{id}"));
        let resp = self.client.get_market_agent(&endpoint).await?;
        Ok(resp.agent)
    }

    pub async fn publish_market_agent(&self, agent_id: &str) -> CloudResult<CloudMarketAgent> {
        let _ = self.signed_in()?;
        let agent = self
            .store
            .get_agent(agent_id)
            .map_err(|e| CloudError::Network(e.to_string()))?
            .ok_or_else(|| CloudError::Network("Agent not found".into()))?;

        if agent.is_default {
            return Err(CloudError::Network("The default Agent cannot be published.".into()));
        }
        if agent.market_agent_id.is_some() {
            return Err(CloudError::Network("This Agent is already linked to the market.".into()));
        }

        let endpoint = self.endpoint("api/market/agents");
        let body = CloudMarketAgentRequest {
            name: agent.name.clone(),
            system_prompt: agent.system_prompt.clone(),
            temperature: agent.temperature.unwrap_or(0.0),
            top_p: agent.top_p.unwrap_or(0.0),
            max_tokens: agent.max_tokens,
            reasoning_effort: agent.reasoning_effort.clone(),
        };

        let response = self.client.create_market_agent(&endpoint, &body).await?.agent;

        // Persist publisher market link locally
        let mut updated = agent;
        updated.market_agent_id = Some(response.id.clone());
        updated.market_agent_version = Some(response.version);
        updated.market_agent_role = Some("publisher".into());
        self.store
            .upsert_agent(&updated)
            .map_err(|e| CloudError::Network(e.to_string()))?;

        Ok(response)
    }

    pub async fn push_market_agent_update(&self, agent_id: &str) -> CloudResult<CloudMarketAgent> {
        let _ = self.signed_in()?;
        let agent = self
            .store
            .get_agent(agent_id)
            .map_err(|e| CloudError::Network(e.to_string()))?
            .ok_or_else(|| CloudError::Network("Agent not found".into()))?;

        let market_id = agent
            .market_agent_id
            .as_deref()
            .ok_or_else(|| CloudError::Network("Publish this Agent first.".into()))?;

        if agent.market_agent_role.as_deref() != Some("publisher") {
            return Err(CloudError::Network("Only the publisher can update this Agent.".into()));
        }

        let endpoint = self.endpoint(&format!("api/market/agents/{market_id}"));
        let body = CloudMarketAgentRequest {
            name: agent.name.clone(),
            system_prompt: agent.system_prompt.clone(),
            temperature: agent.temperature.unwrap_or(0.0),
            top_p: agent.top_p.unwrap_or(0.0),
            max_tokens: agent.max_tokens,
            reasoning_effort: agent.reasoning_effort.clone(),
        };

        let response = self.client.update_market_agent(&endpoint, &body).await?.agent;

        let mut updated = agent;
        updated.market_agent_version = Some(response.version);
        self.store
            .upsert_agent(&updated)
            .map_err(|e| CloudError::Network(e.to_string()))?;

        Ok(response)
    }

    pub async fn remove_market_agent(&self, agent_id: &str) -> CloudResult<()> {
        let _ = self.signed_in()?;
        let agent = self
            .store
            .get_agent(agent_id)
            .map_err(|e| CloudError::Network(e.to_string()))?
            .ok_or_else(|| CloudError::Network("Agent not found".into()))?;

        let market_id = agent
            .market_agent_id
            .as_deref()
            .ok_or_else(|| CloudError::Network("This Agent is not published.".into()))?;

        if agent.market_agent_role.as_deref() != Some("publisher") {
            return Err(CloudError::Network("Only the publisher can remove this Agent.".into()));
        }

        let endpoint = self.endpoint(&format!("api/market/agents/{market_id}"));
        let _ = self.client.delete_market_agent(&endpoint).await;

        let mut updated = agent;
        updated.market_agent_id = None;
        updated.market_agent_version = None;
        updated.market_agent_role = None;
        self.store
            .upsert_agent(&updated)
            .map_err(|e| CloudError::Network(e.to_string()))?;

        Ok(())
    }

    pub async fn import_market_agent(&self, market_id: &str) -> CloudResult<StoredAgent> {
        let _ = self.signed_in()?;
        let market = self.get_market_agent(market_id).await?;

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);

        let new_id = uuid::Uuid::new_v4().to_string();
        let imported = StoredAgent {
            id: new_id.clone(),
            name: market.name,
            avatar: market.avatar_url,
            system_prompt: market.system_prompt,
            description: String::new(),
            default_model_id: None,
            temperature: Some(market.temperature),
            top_p: Some(market.top_p),
            max_tokens: market.max_tokens,
            reasoning_effort: market.reasoning_effort,
            is_default: false,
            follow_default_system_prompt: false,
            follow_default_model: false,
            follow_default_temperature: false,
            follow_default_top_p: false,
            follow_default_max_tokens: false,
            follow_default_reasoning_effort: false,
            market_agent_id: Some(market.id),
            market_agent_version: Some(market.version),
            market_agent_role: Some("importer".into()),
            role: "chat".into(),
            tools_enabled: false,
            tools_follow_default: false,
            tools_config: String::new(),
            created_at: now,
            updated_at: now,
        };

        self.store
            .upsert_agent(&imported)
            .map_err(|e| CloudError::Network(e.to_string()))?;
        let _ = self.request_local_change("agent", &new_id, false);

        Ok(imported)
    }

    pub async fn check_market_agent_update(&self, agent_id: &str) -> CloudResult<CloudMarketAgentUpdate> {
        let _ = self.signed_in()?;
        let agent = self
            .store
            .get_agent(agent_id)
            .map_err(|e| CloudError::Network(e.to_string()))?
            .ok_or_else(|| CloudError::Network("Agent not found".into()))?;

        let market_id = agent
            .market_agent_id
            .as_deref()
            .ok_or_else(|| CloudError::Network("This Agent is not linked to the market.".into()))?;

        if agent.market_agent_role.as_deref() != Some("importer") {
            return Err(CloudError::Network("Published Agents do not receive market updates.".into()));
        }

        let market = self.get_market_agent(market_id).await?;
        let has_update = market.version > agent.market_agent_version.unwrap_or(0);
        Ok(CloudMarketAgentUpdate {
            agent: market,
            has_update,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::Session;
    use crate::models::CloudUser;
    use messenger_store::Store;

    #[tokio::test]
    async fn publish_and_import_market_agent() {
        let server = wiremock::MockServer::start().await;
        let store = Store::open_memory().unwrap();
        let engine = SyncEngine::new(&store, Session::default());
        engine.save_user(&CloudUser { id: "u1".into(), ..Default::default() }).unwrap();
        store.kv_set("cloud_server_url", server.uri().as_str()).unwrap();

        // Seed an agent
        let agent = StoredAgent {
            id: "a1".into(),
            name: "My Assistant".into(),
            avatar: None,
            system_prompt: "prompt".into(),
            description: String::new(),
            default_model_id: None,
            temperature: Some(0.7),
            top_p: Some(1.0),
            max_tokens: None,
            reasoning_effort: None,
            is_default: false,
            follow_default_system_prompt: false,
            follow_default_model: false,
            follow_default_temperature: false,
            follow_default_top_p: false,
            follow_default_max_tokens: false,
            follow_default_reasoning_effort: false,
            market_agent_id: None,
            market_agent_version: None,
            market_agent_role: None,
            role: "chat".into(),
            tools_enabled: false,
            tools_follow_default: false,
            tools_config: String::new(),
            created_at: 1,
            updated_at: 1,
        };
        store.upsert_agent(&agent).unwrap();

        // Mock create market agent
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .and(wiremock::matchers::path("/api/market/agents"))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "agent": {
                    "id": "mkt-1",
                    "name": "My Assistant",
                    "systemPrompt": "prompt",
                    "temperature": 0.7,
                    "topP": 1.0,
                    "version": 1
                },
                "isOwner": true
            })))
            .mount(&server)
            .await;

        let published = engine.publish_market_agent("a1").await.unwrap();
        assert_eq!(published.id, "mkt-1");

        let saved = store.get_agent("a1").unwrap().unwrap();
        assert_eq!(saved.market_agent_id.as_deref(), Some("mkt-1"));
        assert_eq!(saved.market_agent_role.as_deref(), Some("publisher"));
        assert_eq!(saved.market_agent_version, Some(1));

        // Mock get market agent for import
        wiremock::Mock::given(wiremock::matchers::method("GET"))
            .and(wiremock::matchers::path("/api/market/agents/mkt-1"))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "agent": {
                    "id": "mkt-1",
                    "name": "My Assistant",
                    "systemPrompt": "prompt",
                    "temperature": 0.7,
                    "topP": 1.0,
                    "version": 1
                },
                "isOwner": false
            })))
            .mount(&server)
            .await;

        let imported = engine.import_market_agent("mkt-1").await.unwrap();
        assert_eq!(imported.name, "My Assistant");
        assert_eq!(imported.market_agent_id.as_deref(), Some("mkt-1"));
        assert_eq!(imported.market_agent_role.as_deref(), Some("importer"));
    }
}
