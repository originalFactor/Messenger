//! Account settings, avatar uploads, market mutations, and login-restore
//! helpers added on top of the core sync engine.
//!
//! These are the operations the Kotlin `CloudSyncRepository` performs beyond
//! `SyncEngine`'s own surface (Kotlin previously used Ktor + Room directly).
//! Both the UniFFI boundary (`messenger-ffi`) and the browser boundary
//! (`messenger-wasm`) export exactly this set, so the two adapters stay
//! method-for-method identical.

use messenger_store::model::{StoredAgent, StoredConversation};

use crate::api::{CloudError, CloudResult};
use crate::documents::agent_document_to_row;
use crate::models::{CloudMarketAgent, CloudUser, SuccessResponse};
use crate::sync::SyncEngine;

impl<'a> SyncEngine<'a> {
    // ------------------------------------------------------------------
    // account settings
    // ------------------------------------------------------------------

    pub async fn change_password(&self, current: &str, new: &str) -> CloudResult<()> {
        let _ = self.signed_in()?;
        let body = crate::api::CredentialsPair {
            current_password: current.to_string(),
            new_password: new.to_string(),
        };
        let _: SuccessResponse = self
            .client
            .change_password(&self.endpoint("api/auth/password"), &body)
            .await?;
        Ok(())
    }

    /// Deletes the account and the local state that belonged to it
    /// (builtin provider, session, user, pending queues).
    pub async fn delete_account(&self, current: &str) -> CloudResult<()> {
        let account = self.signed_in()?;
        let body = crate::api::CredentialsPair {
            current_password: current.to_string(),
            new_password: String::new(),
        };
        let _: SuccessResponse = self
            .client
            .delete_account(&self.endpoint("api/auth/account"), &body)
            .await?;

        self.remove_builtin_provider()?;
        self.clear_session().map_err(CloudError::Network)?;
        // The account is gone: its cursor and pending queues are meaningless.
        let _ = self.store.clear_sync_meta(&account.id);
        Ok(())
    }

    // ------------------------------------------------------------------
    // avatar uploads (bytes-based: the caller owns file access)
    // ------------------------------------------------------------------

    pub async fn upload_user_avatar(
        &self,
        bytes: Vec<u8>,
        filename: &str,
        mime: &str,
    ) -> CloudResult<String> {
        let _ = self.signed_in()?;
        let response = self
            .client
            .upload_avatar(&self.endpoint("api/avatars/user"), filename, bytes, mime)
            .await?;
        Ok(response.url.unwrap_or_default())
    }

    pub async fn upload_agent_avatar(
        &self,
        agent_id: &str,
        bytes: Vec<u8>,
        filename: &str,
        mime: &str,
    ) -> CloudResult<String> {
        let _ = self.signed_in()?;
        let response = self
            .client
            .upload_avatar(
                &self.endpoint(&format!("api/avatars/agents/{agent_id}")),
                filename,
                bytes,
                mime,
            )
            .await?;
        Ok(response.url.unwrap_or_default())
    }

    pub async fn delete_user_avatar(&self) -> CloudResult<String> {
        let _ = self.signed_in()?;
        let response = self
            .client
            .delete_avatar(&self.endpoint("api/avatars/user"))
            .await?;
        Ok(response.url.unwrap_or_default())
    }

    pub async fn delete_agent_avatar(&self, agent_id: &str) -> CloudResult<String> {
        let _ = self.signed_in()?;
        let response = self
            .client
            .delete_avatar(&self.endpoint(&format!("api/avatars/agents/{agent_id}")))
            .await?;
        Ok(response.url.unwrap_or_default())
    }

    /// Drops the per-account local state that must not survive a logout or a
    /// server change: agent market links and the builtin cloud provider
    /// (which is rebuilt from the next login's API key).
    pub fn clear_market_links_and_builtin_provider(&self) -> CloudResult<()> {
        for agent in self.store.list_agents().map_err(CloudError::network)? {
            if agent.market_agent_id.is_none()
                && agent.market_agent_version.is_none()
                && agent.market_agent_role.is_none()
            {
                continue;
            }
            let cleared = StoredAgent {
                market_agent_id: None,
                market_agent_version: None,
                market_agent_role: None,
                ..agent
            };
            self.store.upsert_agent(&cleared).map_err(CloudError::network)?;
        }
        self.remove_builtin_provider()
    }

    // ------------------------------------------------------------------
    // login flows and full pushes
    // ------------------------------------------------------------------

    /// Push the whole local snapshot (not just pending rows).
    pub async fn push_snapshot(&self) -> CloudResult<i64> {
        self.push_local_snapshot(false).await
    }

    /// Replace the cloud's copy of this account with the local data:
    /// delete every remote-only row, merge the two default agents, then push
    /// everything. Mirrors the Kotlin `replaceCloudWithLocal`.
    pub async fn replace_cloud_with_local(&self) -> CloudResult<crate::sync::SyncResult> {
        let account = self.signed_in()?;
        let remote = self.fetch_cloud_sync(0).await?.0;

        let local_agents = self.store.list_agents().map_err(CloudError::network)?;
        let local_providers = self.store.list_providers().map_err(CloudError::network)?;
        let local_conversations = self.store.list_conversations().map_err(CloudError::network)?;

        for agent in remote.agents.iter().filter(|a| !a.deleted && !a.is_default) {
            if !local_agents.iter().any(|l| l.id == agent.id) {
                let _ = self
                    .client
                    .delete_agent(&self.endpoint(&format!("api/agents/{}", agent.id)))
                    .await;
            }
        }
        for provider in remote.providers.iter().filter(|p| !p.deleted) {
            if !local_providers.iter().any(|l| l.id == provider.id) {
                let _ = self
                    .client
                    .delete_provider(&self.endpoint(&format!("api/providers/{}", provider.id)))
                    .await;
            }
        }
        for conversation in remote.conversations.iter().filter(|c| !c.deleted) {
            if !local_conversations.iter().any(|l| l.id == conversation.id) {
                let _ = self
                    .client
                    .delete_conversation(&self.endpoint(&format!("api/conversations/{}", conversation.id)))
                    .await;
            }
        }

        self.merge_local_default_with_cloud_default(&remote)?;

        let pushed_version = self.push_local_snapshot(false).await?;
        let current = self.refresh_user().await?;
        let latest_version = account.sync_version.max(current.sync_version).max(pushed_version);
        self.store
            .set_sync_meta(
                &account.id,
                latest_version,
                &self.pending_upserts_json(&account.id)?,
                &self.pending_deletes_json(&account.id)?,
            )
            .map_err(CloudError::network)?;

        Ok(crate::sync::SyncResult {
            latest_version,
            agents: local_agents.len(),
            conversations: local_conversations.len(),
            providers: local_providers.len(),
        })
    }

    /// The account keeps exactly one default agent; when the local and cloud
    /// defaults differ, the local one adopts the cloud id and its
    /// conversations follow, so the cloud row is not duplicated.
    fn merge_local_default_with_cloud_default(
        &self,
        remote: &crate::models::CloudSyncResponse,
    ) -> CloudResult<()> {
        let Some(cloud_default) = remote.agents.iter().find(|a| a.is_default && !a.deleted) else {
            return Ok(());
        };
        let local_default = self
            .store
            .list_agents()
            .map_err(CloudError::network)?
            .into_iter()
            .find(|a| a.is_default);
        let Some(local_default) = local_default else {
            return Ok(());
        };
        if local_default.id == cloud_default.id {
            return Ok(());
        }

        let moved = StoredAgent {
            id: cloud_default.id.clone(),
            ..local_default.clone()
        };
        self.store.upsert_agent(&moved).map_err(CloudError::network)?;
        for conversation in self
            .store
            .list_conversations()
            .map_err(CloudError::network)?
            .into_iter()
            .filter(|c: &StoredConversation| c.agent_id == local_default.id)
        {
            let updated = StoredConversation {
                agent_id: cloud_default.id.clone(),
                ..conversation
            };
            self.store
                .upsert_conversation(&updated)
                .map_err(CloudError::network)?;
        }
        self.store
            .delete_agent(&local_default.id)
            .map_err(CloudError::network)?;
        self.store
            .kv_set("current_agent_id", &cloud_default.id)
            .map_err(CloudError::network)?;
        Ok(())
    }

    /// Seed/refresh the builtin title agent through the public entry point
    /// (the sync cycle does this itself; the app calls it at startup too).
    pub fn ensure_title_agent(&self) -> Result<(), String> {
        self.ensure_builtin_title_agent()
    }

    // ------------------------------------------------------------------
    // market
    // ------------------------------------------------------------------

    /// Apply a market update to a locally imported agent (name, prompt and
    /// sampling parameters follow the market entry; model bindings and
    /// follow-default flags are cleared, exactly like the Kotlin path).
    pub async fn apply_market_agent_update(
        &self,
        agent_id: &str,
        market: &CloudMarketAgent,
    ) -> CloudResult<StoredAgent> {
        let _ = self.signed_in()?;
        let agent = self
            .store
            .get_agent(agent_id)
            .map_err(CloudError::network)?
            .ok_or_else(|| CloudError::Network("Agent not found".into()))?;
        if agent.market_agent_id.as_deref() != Some(market.id.as_str())
            || agent.market_agent_role.as_deref() != Some("importer")
        {
            return Err(CloudError::Network(
                "This Agent is not linked to the selected market entry.".into(),
            ));
        }

        let avatar = match market.avatar_url.as_deref() {
            Some(url) => {
                let account = self.signed_in()?;
                match self
                    .cache_avatar(
                        "market-agent",
                        &account.id,
                        &market.id,
                        url,
                        market.avatar_version.map(|v| v.to_string()).as_deref(),
                    )
                    .await
                {
                    Ok(path) => Some(path),
                    Err(_) => agent.avatar.clone(),
                }
            }
            None => agent.avatar.clone(),
        };

        let updated = StoredAgent {
            name: market.name.clone(),
            avatar,
            system_prompt: market.system_prompt.clone(),
            temperature: Some(market.temperature),
            top_p: Some(market.top_p),
            max_tokens: market.max_tokens,
            reasoning_effort: market.reasoning_effort.clone(),
            follow_default_system_prompt: false,
            follow_default_model: false,
            follow_default_temperature: false,
            follow_default_top_p: false,
            follow_default_max_tokens: false,
            follow_default_reasoning_effort: false,
            market_agent_version: Some(market.version),
            updated_at: crate::sync::now_ms(),
            ..agent
        };
        self.store.upsert_agent(&updated).map_err(CloudError::network)?;
        let _ = self.request_local_change("agent", &updated.id, false);
        Ok(updated)
    }

    /// Import a market agent, caching its avatar locally when present.
    pub async fn import_market_agent_with_avatar(&self, market_id: &str) -> CloudResult<StoredAgent> {
        let imported = self.import_market_agent(market_id).await?;
        let Some(url) = imported.avatar.as_deref() else {
            return Ok(imported);
        };
        if !url.starts_with("http://") && !url.starts_with("https://") {
            return Ok(imported);
        }
        let account = self.signed_in()?;
        let market = self.get_market_agent(market_id).await?;
        let Ok(local) = self
            .cache_avatar(
                "market-agent",
                &account.id,
                &market.id,
                url,
                market.avatar_version.map(|v| v.to_string()).as_deref(),
            )
            .await
        else {
            return Ok(imported);
        };
        let updated = StoredAgent {
            avatar: Some(local),
            ..imported
        };
        self.store.upsert_agent(&updated).map_err(CloudError::network)?;
        Ok(updated)
    }

    /// Cache a remote avatar through an `AvatarManager` rooted at the store's
    /// own files directory (the caller passes it at construction).
    pub async fn cache_avatar(
        &self,
        scope: &str,
        account_id: &str,
        id: &str,
        url: &str,
        version: Option<&str>,
    ) -> CloudResult<String> {
        // Native caches into the configured directory; wasm returns a
        // `data:` URI and ignores the directory entirely.
        let dir = self.avatars_dir.clone().unwrap_or_default();
        let manager = crate::avatar::AvatarManager::new(self.client(), dir);
        manager
            .cache_remote_avatar(scope, account_id, id, url, version, &self.server_url())
            .await
    }
}

/// Convenience for the FFI layer: turn a market document into the store row
/// shape the Kotlin side expects (fresh import, no local model binding).
#[allow(dead_code)]
pub fn market_document_to_new_agent(doc: &crate::models::CloudMarketAgent) -> StoredAgent {
    let cloud = crate::models::CloudAgentDocument {
        id: uuid::Uuid::new_v4().to_string(),
        name: doc.name.clone(),
        avatar_url: doc.avatar_url.clone(),
        avatar_version: doc.avatar_version,
        system_prompt: doc.system_prompt.clone(),
        temperature: doc.temperature,
        top_p: doc.top_p,
        max_tokens: doc.max_tokens,
        reasoning_effort: doc.reasoning_effort.clone(),
        market_agent_id: Some(doc.id.clone()),
        market_agent_version: Some(doc.version),
        market_agent_role: Some("importer".into()),
        created_at: now(),
        updated_at: now(),
        ..Default::default()
    };
    agent_document_to_row(&cloud, None)
}

#[allow(dead_code)]
fn now() -> i64 {
    crate::sync::now_ms()
}

#[allow(dead_code)]
fn unused(_: &CloudUser) {}
