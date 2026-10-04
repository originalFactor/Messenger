//! The sync engine (port of `CloudSyncRepository.kt`'s sync core): paged
//! delta pull with cursor checks, delta application with the
//! builtin-exclusion and role guards, push of pending upserts/deletes, and
//! the builtin provider/title-agent seeding rules.

use messenger_store::model::{StoredAgent, StoredProvider};
use messenger_store::{EntityKind, Store, StoreEvent};

use crate::api::{CloudApiClient, CloudError, CloudResult, Session};
use crate::documents::{
    agent_document_to_row, agent_row_to_request, conversation_document_to_row,
    conversation_row_to_request, message_document_to_row, model_document_to_row,
    provider_document_to_row, provider_row_to_request,
};
use crate::models::*;

/// Paged pull size (Kotlin `SYNC_PAGE_SIZE`).
const SYNC_PAGE_SIZE: u32 = 100;

pub const KV_SESSION: &str = "cloud_session";
pub const KV_SESSION_HOST: &str = "cloud_session_host";
pub const KV_USER: &str = "cloud_user_json";
pub const KV_SERVER_URL: &str = "cloud_server_url";

#[derive(Debug, Clone, Default)]
pub struct SyncResult {
    pub latest_version: i64,
    pub agents: usize,
    pub conversations: usize,
    pub providers: usize,
}

pub struct SyncEngine<'a> {
    store: &'a Store,
    client: CloudApiClient,
}

impl<'a> SyncEngine<'a> {
    pub fn new(store: &'a Store, session: Session) -> Self {
        Self {
            store,
            client: CloudApiClient::new(session),
        }
    }

    // ------------------------------------------------------------------
    // session / account state (kv-backed)
    // ------------------------------------------------------------------

    pub fn save_session(&self, session: &Session) -> Result<(), String> {
        self.store
            .kv_set(KV_SESSION, session.cookie.as_deref().unwrap_or_default())
            .map_err(|e| e.to_string())?;
        self.store
            .kv_set(KV_SESSION_HOST, session.host.as_deref().unwrap_or_default())
            .map_err(|e| e.to_string())
    }

    pub fn clear_session(&self) -> Result<(), String> {
        self.store.kv_delete(KV_SESSION).map_err(|e| e.to_string())?;
        self.store.kv_delete(KV_SESSION_HOST).map_err(|e| e.to_string())?;
        self.store.kv_delete(KV_USER).map_err(|e| e.to_string())
    }

    pub fn current_user(&self) -> Option<CloudUser> {
        self.store
            .kv_get(KV_USER)
            .ok()
            .flatten()
            .and_then(|json| serde_json::from_str(&json).ok())
    }

    pub fn save_user(&self, user: &CloudUser) -> Result<(), String> {
        let json = serde_json::to_string(user).map_err(|e| e.to_string())?;
        self.store.kv_set(KV_USER, &json).map_err(|e| e.to_string())
    }

    pub fn server_url(&self) -> String {
        self.store
            .kv_get(KV_SERVER_URL)
            .ok()
            .flatten()
            .filter(|s| !s.trim().is_empty())
            .unwrap_or_else(|| DEFAULT_CLOUD_SERVER_URL.to_string())
    }

    pub fn set_server_url(&self, url: &str) -> Result<(), String> {
        self.store
            .kv_set(KV_SERVER_URL, url.trim_end_matches('/'))
            .map_err(|e| e.to_string())
    }

    fn endpoint(&self, path: &str) -> String {
        format!("{}/{}", self.server_url().trim_end_matches('/'), path)
    }

    fn signed_in(&self) -> Result<CloudUser, CloudError> {
        self.current_user().ok_or_else(|| CloudError::Http {
            status: 401,
            message: "Please sign in first".into(),
        })
    }

    // ------------------------------------------------------------------
    // auth flows (thin wrappers persisting session/user)
    // ------------------------------------------------------------------

    pub async fn login(&self, email: &str, password: &str) -> CloudResult<CloudUser> {
        let body = CredentialsRequest {
            email: email.to_string(),
            password: password.to_string(),
        };
        let user = self.client.login(&self.endpoint("api/auth/login"), &body).await?;
        self.complete_authentication(&user).await?;
        Ok(user)
    }

    pub async fn register(&self, email: &str, password: &str) -> CloudResult<CloudUser> {
        let body = CredentialsRequest {
            email: email.to_string(),
            password: password.to_string(),
        };
        let user = self.client.register(&self.endpoint("api/auth/register"), &body).await?;
        self.complete_authentication(&user).await?;
        Ok(user)
    }

    pub async fn refresh_user(&self) -> CloudResult<CloudUser> {
        let user = self.client.me(&self.endpoint("api/auth/me")).await?;
        self.save_user(&user).map_err(|e| CloudError::Network(e))?;
        Ok(user)
    }

    pub async fn logout(&self) -> CloudResult<()> {
        let _ = self.client.logout(&self.endpoint("api/auth/logout")).await;
        self.clear_session().map_err(CloudError::Network)?;
        Ok(())
    }

    async fn complete_authentication(&self, user: &CloudUser) -> CloudResult<()> {
        // The login/register responses set the session cookie; this engine
        // relies on the caller capturing it (FFI layer surfaces Set-Cookie).
        self.save_user(user).map_err(CloudError::Network)?;
        Ok(())
    }

    // ------------------------------------------------------------------
    // sync core
    // ------------------------------------------------------------------

    /// Full sync cycle (pull + apply). `since_override` skips the stored
    /// cursor (post-push pull), `replace_local` wipes rows first (login
    /// restore), `expected_server_version` guards against /me drift.
    pub async fn sync_internal(
        &self,
        since_override: Option<i64>,
        replace_local: bool,
        expected_server_version: Option<i64>,
    ) -> CloudResult<SyncResult> {
        let account = self.signed_in()?;
        let since = match since_override {
            Some(v) => v,
            None => self
                .store
                .sync_cursor(&account.id)
                .map_err(|e| CloudError::Network(e.to_string()))?,
        };

        let (delta, raw_server_latest) = self.fetch_cloud_sync(since).await?;
        // Guard against the server reporting an older version than our
        // cursor (the max-with-since floor in latest_version would mask it).
        if raw_server_latest < since {
            return Err(CloudError::Network(format!(
                "Cloud sync cursor moved backwards: since={since} latest={raw_server_latest}"
            )));
        }
        if let Some(expected) = expected_server_version {
            if delta.latest_version < expected {
                return Err(CloudError::Network(format!(
                    "Cloud sync version mismatch: /me={expected} /sync={}",
                    delta.latest_version
                )));
            }
        }

        if replace_local {
            self.wipe_local(&account.id)?;
        }

        self.apply_delta(&delta)
            .map_err(|e| CloudError::Network(e.to_string()))?;
        self.ensure_builtin_provider(&account)
            .map_err(CloudError::Network)?;
        self.ensure_builtin_title_agent()
            .map_err(CloudError::Network)?;

        self.store
            .set_sync_meta(&account.id, delta.latest_version, &self.pending_upserts(&account.id)?, &self.pending_deletes(&account.id)?)
            .map_err(|e| CloudError::Network(e.to_string()))?;

        Ok(SyncResult {
            latest_version: delta.latest_version,
            agents: delta.agents.len(),
            conversations: delta.conversations.len(),
            providers: delta.providers.len(),
        })
    }

    /// Push pending local changes, then pull with the post-push version so
    /// we don't re-apply our own deltas (the re-entrant full-page rewrite
    /// the Kotlin comments warn about).
    pub async fn push_pending_changes(&self) -> CloudResult<SyncResult> {
        let account = self.signed_in()?;
        let pushed_version = self.push_local_snapshot(true).await?;
        self.sync_internal(
            Some(pushed_version.max(0)),
            false,
            Some(account.sync_version),
        )
        .await
    }

    /// Push local snapshot (full or pending-only), excluding the builtin
    /// provider/title agent. Returns the latest server version seen.
    pub async fn push_local_snapshot(&self, only_pending: bool) -> CloudResult<i64> {
        let account = self.signed_in()?;
        let mut latest_version = 0i64;

        // Deletes first.
        for entry in self.pending_delete_entries(&account.id)? {
            let (kind, id) = entry;
            let url = self.endpoint(&format!("api/{kind}s/{id}"));
            let response = match kind.as_str() {
                "agent" => self.client.delete_agent(&url).await?,
                "provider" => self.client.delete_provider(&url).await?,
                "conversation" => self.client.delete_conversation(&url).await?,
                _ => continue,
            };
            latest_version = latest_version.max(response.version);
            self.remove_pending_delete(&account.id, &kind, &id)?;
        }

        let should_push = |kind: &str, id: &str, pending: &Vec<String>| -> bool {
            !only_pending || pending.contains(&format!("{kind}:{id}"))
        };
        let pending_upserts = self.pending_upsert_entries(&account.id)?;

        // Agents (builtin title agent never syncs).
        for agent in self.store.list_agents().map_err(|e| CloudError::Network(e.to_string()))? {
            if agent.id == BUILTIN_TITLE_AGENT_ID {
                continue;
            }
            if !should_push("agent", &agent.id, &pending_upserts) {
                continue;
            }
            let url = self.endpoint(&format!("api/agents/{}", agent.id));
            let response = self.client.put_agent(&url, &agent_row_to_request(&agent)).await?;
            latest_version = latest_version.max(response.version);
            self.remove_pending_upsert(&account.id, "agent", &agent.id)?;
        }

        // Providers (builtin cloud provider never syncs).
        for provider in self.store.list_providers().map_err(|e| CloudError::Network(e.to_string()))? {
            if provider.id == BUILTIN_PROVIDER_ID {
                continue;
            }
            if !should_push("provider", &provider.id, &pending_upserts) {
                continue;
            }
            let models = self
                .store
                .list_models_by_provider(&provider.id)
                .map_err(|e| CloudError::Network(e.to_string()))?;
            let url = self.endpoint(&format!("api/providers/{}", provider.id));
            let response = self
                .client
                .put_provider(&url, &provider_row_to_request(&provider, &models))
                .await?;
            latest_version = latest_version.max(response.version);
            self.remove_pending_upsert(&account.id, "provider", &provider.id)?;
        }

        // Conversations with their messages embedded.
        for conversation in self
            .store
            .list_conversations()
            .map_err(|e| CloudError::Network(e.to_string()))?
        {
            if !should_push("conversation", &conversation.id, &pending_upserts) {
                continue;
            }
            let messages = self
                .store
                .list_messages_by_conversation(&conversation.id)
                .map_err(|e| CloudError::Network(e.to_string()))?;
            let url = self.endpoint(&format!("api/conversations/{}", conversation.id));
            let response = self
                .client
                .put_conversation(&url, &conversation_row_to_request(&conversation, &messages))
                .await?;
            latest_version = latest_version.max(response.version);
            self.remove_pending_upsert(&account.id, "conversation", &conversation.id)?;
        }

        Ok(latest_version)
    }

    /// Mark one local entity dirty (or deleted); debouncing is the caller's
    /// job (FFI layer schedules `push_pending_changes`).
    pub fn request_local_change(&self, kind: &str, id: &str, deleted: bool) -> Result<(), String> {
        let account = self
            .current_user()
            .ok_or_else(|| "not signed in".to_string())?;
        if deleted {
            self.add_pending_delete(&account.id, kind, id)
        } else {
            self.add_pending_upsert(&account.id, kind, id)
        }
    }

    // ------------------------------------------------------------------
    // pending queues (sync_meta-backed)
    // ------------------------------------------------------------------

    fn read_pending_list(&self, account: &str, column: &str) -> CloudResult<Vec<String>> {
        let raw = self
            .store
            .sync_meta_field(account, column)
            .map_err(|e| CloudError::Network(e.to_string()))?;
        Ok(serde_json::from_str(&raw).unwrap_or_default())
    }

    fn write_pending_list(&self, account: &str, column: &str, list: &[String]) -> CloudResult<()> {
        let cursor = self
            .store
            .sync_cursor(account)
            .map_err(|e| CloudError::Network(e.to_string()))?;
        let upserts = if column == "pendingUpserts" {
            serde_json::to_string(list).unwrap_or_else(|_| "[]".into())
        } else {
            self.raw_pending(account, "pendingUpserts")?
        };
        let deletes = if column == "pendingDeletes" {
            serde_json::to_string(list).unwrap_or_else(|_| "[]".into())
        } else {
            self.raw_pending(account, "pendingDeletes")?
        };
        self.store
            .set_sync_meta(account, cursor, &upserts, &deletes)
            .map_err(|e| CloudError::Network(e.to_string()))
    }

    fn raw_pending(&self, account: &str, column: &str) -> CloudResult<String> {
        self.store
            .sync_meta_field(account, column)
            .map_err(|e| CloudError::Network(e.to_string()))
    }

    fn pending_upserts(&self, account: &str) -> CloudResult<String> {
        self.raw_pending(account, "pendingUpserts")
    }

    fn pending_deletes(&self, account: &str) -> CloudResult<String> {
        self.raw_pending(account, "pendingDeletes")
    }

    fn pending_upsert_entries(&self, account: &str) -> CloudResult<Vec<String>> {
        self.read_pending_list(account, "pendingUpserts")
    }

    fn pending_delete_entries(&self, account: &str) -> CloudResult<Vec<(String, String)>> {
        Ok(self
            .read_pending_list(account, "pendingDeletes")?
            .into_iter()
            .filter_map(|entry| {
                entry.split_once(':').map(|(k, v)| (k.to_string(), v.to_string()))
            })
            .collect())
    }

    fn add_pending_upsert(&self, account: &str, kind: &str, id: &str) -> Result<(), String> {
        let mut list = self.read_pending_list(account, "pendingUpserts").map_err(|e| e.to_string())?;
        let entry = format!("{kind}:{id}");
        if !list.contains(&entry) {
            list.push(entry);
        }
        self.write_pending_list(account, "pendingUpserts", &list)
            .map_err(|e| e.to_string())
    }

    fn remove_pending_upsert(&self, account: &str, kind: &str, id: &str) -> CloudResult<()> {
        let mut list = self.read_pending_list(account, "pendingUpserts")?;
        list.retain(|e| e != &format!("{kind}:{id}"));
        self.write_pending_list(account, "pendingUpserts", &list)
    }

    fn add_pending_delete(&self, account: &str, kind: &str, id: &str) -> Result<(), String> {
        let mut list = self.read_pending_list(account, "pendingDeletes").map_err(|e| e.to_string())?;
        let entry = format!("{kind}:{id}");
        if !list.contains(&entry) {
            list.push(entry);
        }
        self.write_pending_list(account, "pendingDeletes", &list)
            .map_err(|e| e.to_string())
    }

    fn remove_pending_delete(&self, account: &str, kind: &str, id: &str) -> CloudResult<()> {
        let mut list = self.read_pending_list(account, "pendingDeletes")?;
        list.retain(|e| e != &format!("{kind}:{id}"));
        self.write_pending_list(account, "pendingDeletes", &list)
    }

    // ------------------------------------------------------------------
    // pull
    // ------------------------------------------------------------------

    async fn fetch_cloud_sync(&self, since: i64) -> CloudResult<(CloudSyncResponse, i64)> {
        let mut agents = Vec::new();
        let mut agents_latest = since;
        let mut raw_server_latest = 0i64;
        let mut cursor: Option<String> = None;
        loop {
            let page = self
                .client
                .sync_agents_page(&self.endpoint("api/sync"), since, cursor.as_deref(), SYNC_PAGE_SIZE)
                .await?;
            agents.extend(page.documents);
            agents_latest = agents_latest.max(page.latest_version);
            raw_server_latest = raw_server_latest.max(page.latest_version);
            cursor = if page.has_more { page.next_cursor } else { None };
            if cursor.is_none() {
                break;
            }
        }

        let mut conversations = Vec::new();
        let mut conversations_latest = since;
        let mut cursor: Option<String> = None;
        loop {
            let page = self
                .client
                .sync_conversations_page(&self.endpoint("api/sync"), since, cursor.as_deref(), SYNC_PAGE_SIZE)
                .await?;
            conversations.extend(page.documents);
            conversations_latest = conversations_latest.max(page.latest_version);
            cursor = if page.has_more { page.next_cursor } else { None };
            if cursor.is_none() {
                break;
            }
        }

        let mut providers = Vec::new();
        let mut providers_latest = since;
        let mut cursor: Option<String> = None;
        loop {
            let page = self
                .client
                .sync_providers_page(&self.endpoint("api/sync"), since, cursor.as_deref(), SYNC_PAGE_SIZE)
                .await?;
            providers.extend(page.documents);
            providers_latest = providers_latest.max(page.latest_version);
            cursor = if page.has_more { page.next_cursor } else { None };
            if cursor.is_none() {
                break;
            }
        }

        let response = CloudSyncResponse {
            agents,
            conversations,
            providers,
            latest_version: agents_latest
                .max(conversations_latest)
                .max(providers_latest),
        };
        Ok((response, raw_server_latest))
    }

    /// Apply a pulled delta with all the Kotlin guards: builtin rows never
    /// sync, remote tombstones respect the title-holder rule, default and
    /// title roles stay single-holder, and conversations require their
    /// agent to exist locally.
    fn apply_delta(&self, delta: &CloudSyncResponse) -> Result<(), String> {
        // Agents.
        for remote in &delta.agents {
            if remote.id == BUILTIN_TITLE_AGENT_ID {
                continue;
            }
            if remote.deleted {
                let local = self.store.get_agent(&remote.id).map_err(|e| e.to_string())?;
                if local.map(|a| a.role == ROLE_TITLE).unwrap_or(false) {
                    continue;
                }
                let conversations = self
                    .store
                    .list_conversations_by_agent(&remote.id)
                    .map_err(|e| e.to_string())?;
                for conversation in conversations {
                    self.store
                        .delete_messages_by_conversation(&conversation.id)
                        .map_err(|e| e.to_string())?;
                    self.store.delete_conversation(&conversation.id).map_err(|e| e.to_string())?;
                }
                self.store.delete_agent(&remote.id).map_err(|e| e.to_string())?;
            } else {
                // Preserve a locally-cached avatar file over a remote URL.
                let existing_avatar = self
                    .store
                    .get_agent(&remote.id)
                    .map_err(|e| e.to_string())?
                    .and_then(|local| local.avatar)
                    .filter(|avatar| !avatar.starts_with("http://") && !avatar.starts_with("https://"));
                if remote.is_default {
                    for other in self.store.list_agents().map_err(|e| e.to_string())? {
                        if other.is_default && other.id != remote.id {
                            let mut demoted = other;
                            demoted.is_default = false;
                            self.store.upsert_agent(&demoted).map_err(|e| e.to_string())?;
                        }
                    }
                }
                if remote.role.as_deref() == Some(ROLE_TITLE) {
                    for other in self.store.list_agents().map_err(|e| e.to_string())? {
                        if other.role == ROLE_TITLE && other.id != remote.id {
                            let mut demoted = other;
                            demoted.role = ROLE_CHAT.to_string();
                            self.store.upsert_agent(&demoted).map_err(|e| e.to_string())?;
                        }
                    }
                }
                self.store
                    .upsert_agent(&agent_document_to_row(remote, existing_avatar))
                    .map_err(|e| e.to_string())?;
            }
        }

        // Providers (delete + reinsert models inside one delta apply).
        for remote in &delta.providers {
            if remote.id == BUILTIN_PROVIDER_ID {
                continue;
            }
            if remote.deleted {
                self.delete_provider_models(&remote.id)?;
                self.store.delete_provider(&remote.id).map_err(|e| e.to_string())?;
            } else {
                self.delete_provider_models(&remote.id)?;
                self.store
                    .upsert_provider(&provider_document_to_row(remote))
                    .map_err(|e| e.to_string())?;
                for model in &remote.models {
                    self.store
                        .upsert_model(&messenger_store::model::StoredModel {
                            is_enabled: model.is_enabled,
                            context_window: model.context_window,
                            ..model_document_to_row(model, &remote.id)
                        })
                        .map_err(|e| e.to_string())?;
                }
            }
        }

        // Conversations: replace-all semantics per conversation.
        for remote in &delta.conversations {
            if remote.deleted {
                self.store
                    .delete_messages_by_conversation(&remote.id)
                    .map_err(|e| e.to_string())?;
                self.store.delete_conversation(&remote.id).map_err(|e| e.to_string())?;
            } else {
                if self
                    .store
                    .get_agent(&remote.agent_id)
                    .map_err(|e| e.to_string())?
                    .is_none()
                {
                    self.store
                        .delete_messages_by_conversation(&remote.id)
                        .map_err(|e| e.to_string())?;
                    self.store.delete_conversation(&remote.id).map_err(|e| e.to_string())?;
                    continue;
                }
                self.store
                    .delete_messages_by_conversation(&remote.id)
                    .map_err(|e| e.to_string())?;
                self.store
                    .upsert_conversation(&conversation_document_to_row(remote))
                    .map_err(|e| e.to_string())?;
                for message in &remote.messages {
                    self.store
                        .upsert_message(&message_document_to_row(message, &remote.id))
                        .map_err(|e| e.to_string())?;
                }
            }
        }

        self.store.notify(StoreEvent { kind: EntityKind::Other, ids: vec!["sync_apply".into()] });
        Ok(())
    }

    fn delete_provider_models(&self, provider_id: &str) -> Result<(), String> {
        for model in self.store.list_models_by_provider(provider_id).map_err(|e| e.to_string())? {
            self.store.delete_model(&model.id).map_err(|e| e.to_string())?;
        }
        Ok(())
    }

    fn wipe_local(&self, account_id: &str) -> CloudResult<()> {
        // Delete in FK-safe order; the builtin rows re-seed below.
        for provider in self.store.list_providers().map_err(|e| CloudError::Network(e.to_string()))? {
            self.store.delete_provider(&provider.id).map_err(|e| CloudError::Network(e.to_string()))?;
        }
        for conversation in self.store.list_conversations().map_err(|e| CloudError::Network(e.to_string()))? {
            self.store
                .delete_messages_by_conversation(&conversation.id)
                .map_err(|e| CloudError::Network(e.to_string()))?;
            self.store.delete_conversation(&conversation.id).map_err(|e| CloudError::Network(e.to_string()))?;
        }
        for agent in self.store.list_agents().map_err(|e| CloudError::Network(e.to_string()))? {
            self.store.delete_agent(&agent.id).map_err(|e| CloudError::Network(e.to_string()))?;
        }
        self.store.kv_set("current_agent_id", "").map_err(|e| CloudError::Network(e.to_string()))?;
        let _ = account_id;
        Ok(())
    }

    // ------------------------------------------------------------------
    // builtin seeding
    // ------------------------------------------------------------------

    /// Seed/update the builtin cloud AI provider from the signed-in user's
    /// AI key (never synced; excluded from push and pull).
    pub fn ensure_builtin_provider(&self, user: &CloudUser) -> Result<(), String> {
        let Some(api_key) = user.ai_api_key.as_deref().filter(|k| !k.is_empty()) else {
            return Ok(());
        };
        let base_url = format!("{}/v1", self.server_url().trim_end_matches('/'));
        let now = now_ms();
        let row = match self.store.get_provider(BUILTIN_PROVIDER_ID).map_err(|e| e.to_string())? {
            Some(existing) => StoredProvider {
                base_url,
                api_key: api_key.to_string(),
                updated_at: now,
                ..existing
            },
            None => StoredProvider {
                id: BUILTIN_PROVIDER_ID.to_string(),
                name: BUILTIN_PROVIDER_NAME.to_string(),
                base_url,
                api_key: api_key.to_string(),
                created_at: now,
                updated_at: now,
            },
        };
        self.store.upsert_provider(&row).map_err(|e| e.to_string())
    }

    /// Seed the builtin title agent only when no title-role holder exists at
    /// all (transfers survive; the builtin never re-seeds afterwards).
    pub fn ensure_builtin_title_agent(&self) -> Result<(), String> {
        let has_holder = self
            .store
            .list_agents()
            .map_err(|e| e.to_string())?
            .iter()
            .any(|agent| agent.role == ROLE_TITLE);
        if has_holder {
            return Ok(());
        }
        if self.store.get_agent(BUILTIN_TITLE_AGENT_ID).map_err(|e| e.to_string())?.is_some() {
            return Ok(());
        }
        let now = now_ms();
        self.store.upsert_agent(&StoredAgent {
            id: BUILTIN_TITLE_AGENT_ID.to_string(),
            name: "标题生成".to_string(),
            avatar: None,
            system_prompt: BUILTIN_TITLE_AGENT_PROMPT.to_string(),
            description: String::new(),
            default_model_id: None,
            temperature: Some(0.4),
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
            role: ROLE_TITLE.to_string(),
            tools_enabled: false,
            tools_follow_default: false,
            tools_config: String::new(),
            created_at: now,
            updated_at: now,
        })
        .map_err(|e| e.to_string())
    }

    /// Login-time local-data baseline: any non-builtin rows count (the
    /// fresh-install state — a single unmodified default agent — does not).
    /// The prompt-baseline refinement lands with the M2 re-anchor.
    pub fn has_local_data(&self) -> Result<bool, String> {
        let agents = self
            .store
            .list_agents()
            .map_err(|e| e.to_string())?
            .into_iter()
            .filter(|a| a.id != BUILTIN_TITLE_AGENT_ID && a.role != ROLE_TITLE)
            .any(|a| {
                !a.description.trim().is_empty()
                    || a.default_model_id.is_some()
                    || a.market_agent_id.is_some()
                    || a.follow_default_model
                    || a.follow_default_system_prompt
            });
        let providers = self
            .store
            .list_providers()
            .map_err(|e| e.to_string())?
            .into_iter()
            .any(|p| p.id != BUILTIN_PROVIDER_ID);
        let conversations = !self
            .store
            .list_conversations()
            .map_err(|e| e.to_string())?
            .is_empty();
        Ok(agents || providers || conversations)
    }
}

const BUILTIN_TITLE_AGENT_PROMPT: &str = "You generate short conversation titles. Based on the messages you receive, reply with a single concise title in the same language as the user's messages. Reply with the title text only — no quotes, no trailing punctuation, no explanations.";

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}
