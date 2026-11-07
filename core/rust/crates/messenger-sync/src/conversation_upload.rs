//! Upload standalone TUI conversations without applying a remote snapshot.

use std::collections::HashSet;

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::SyncEngine;
use crate::api::{CloudError, CloudResult};
use crate::documents::conversation_row_to_request;
use crate::models::{BUILTIN_PROVIDER_ID, ROLE_TITLE};

#[derive(Default, Serialize)]
pub struct ConversationUploadResult {
    pub uploaded: usize,
    pub skipped: usize,
    pub conflicts: usize,
}

#[derive(Serialize, Deserialize)]
struct UploadRecord {
    server: String,
    account: String,
    digest: Option<String>,
    #[serde(default)]
    version: Option<i64>,
}

impl SyncEngine<'_> {
    pub async fn upload_conversations(
        &self,
        conversation_id: Option<&str>,
        claim_new: bool,
        active: &HashSet<String>,
    ) -> CloudResult<ConversationUploadResult> {
        let server = self.server_url();
        let session = self.client.session();
        let mut result = ConversationUploadResult::default();
        let user = self.current_user().filter(|_| {
            session
                .host
                .as_deref()
                .is_some_and(|host| host.trim_end_matches('/') == server)
                && session
                    .cookie
                    .as_deref()
                    .is_some_and(|cookie| !cookie.is_empty())
        });
        let Some(user) = user else {
            return if conversation_id.is_some() {
                Ok(result)
            } else {
                Err(CloudError::Http {
                    status: 401,
                    message: "Sign in to Messenger Cloud first.".into(),
                })
            };
        };
        let rows = match conversation_id {
            Some(id) => self
                .store
                .get_conversation(id)
                .map_err(CloudError::network)?
                .into_iter()
                .collect(),
            None => self
                .store
                .list_conversations()
                .map_err(CloudError::network)?,
        };
        let mut cloud_agents = None;
        for conversation in rows {
            if conversation.project_id.is_some() || active.contains(&conversation.id) {
                result.skipped += 1;
                continue;
            }
            let key = format!("tui_conversation_upload:{}", conversation.id);
            let saved = self
                .store
                .kv_get(&key)
                .map_err(CloudError::network)?
                .map(|value| serde_json::from_str::<UploadRecord>(&value))
                .transpose()
                .map_err(CloudError::network)?;
            let mut record = match saved {
                Some(record) if record.server == server && record.account == user.id => record,
                None if conversation_id.is_none() || claim_new => UploadRecord {
                    server: server.clone(),
                    account: user.id.clone(),
                    digest: None,
                    version: None,
                },
                _ => {
                    result.skipped += 1;
                    continue;
                }
            };
            let messages = self
                .store
                .list_messages_by_conversation(&conversation.id)
                .map_err(CloudError::network)?;
            if messages.is_empty() {
                continue;
            }
            let mut payload = conversation_row_to_request(&conversation, &messages);
            if payload.provider_id == BUILTIN_PROVIDER_ID {
                let model_id = conversation.override_model_id.clone().or(self
                    .store
                    .get_agent(&conversation.agent_id)
                    .map_err(CloudError::network)?
                    .and_then(|agent| agent.default_model_id));
                // Core stores a model row ID; the Web client expects the Cloud model ID.
                payload.override_model_id = model_id.map(|id| {
                    id.strip_prefix("builtin-messenger-cloud-ai:")
                        .unwrap_or(&id)
                        .to_owned()
                });
            }
            let canonical = serde_json::to_value(&payload).map_err(CloudError::network)?;
            let digest = format!("{:x}", Sha256::digest(canonical.to_string().as_bytes()));
            if record.digest.as_deref() == Some(&digest) {
                continue;
            }
            if let Some(version) = record.version {
                if self
                    .conversation_changed(&server, &conversation.id, version)
                    .await?
                {
                    result.conflicts += 1;
                    continue;
                }
            }
            // Bind the chat before the first request, even when the upload fails.
            if record.digest.is_none() {
                self.save_upload_record(&key, &record)?;
            }
            let (agents, default_agent) = match &cloud_agents {
                Some(agents) => agents,
                None => cloud_agents.insert(self.upload_agent_ids(&server).await?),
            };
            if !agents.contains(&payload.agent_id) {
                payload.agent_id = default_agent.clone();
            }
            let response = self
                .with_session(self.client.put_conversation(
                    &format!("{server}/api/conversations/{}", conversation.id),
                    &payload,
                ))
                .await?;
            record.digest = Some(digest);
            record.version = Some(response.version);
            self.save_upload_record(&key, &record)?;
            result.uploaded += 1;
        }
        Ok(result)
    }

    fn save_upload_record(&self, key: &str, record: &UploadRecord) -> CloudResult<()> {
        let value = serde_json::to_string(record).map_err(CloudError::network)?;
        self.store.kv_set(key, &value).map_err(CloudError::network)
    }

    async fn upload_agent_ids(&self, server: &str) -> CloudResult<(HashSet<String>, String)> {
        let url = format!("{server}/api/sync");
        let mut agents = HashSet::new();
        let mut default_agent = None;
        let mut cursor = None;
        loop {
            let page = self
                .with_session(
                    self.client
                        .sync_agents_page(&url, 0, cursor.as_deref(), 100, None),
                )
                .await?;
            for agent in page
                .documents
                .into_iter()
                .filter(|agent| !agent.deleted && agent.role.as_deref() != Some(ROLE_TITLE))
            {
                if agent.is_default {
                    default_agent = Some(agent.id.clone());
                }
                agents.insert(agent.id);
            }
            match page.next_cursor {
                Some(next) if page.has_more && cursor.as_ref() != Some(&next) => {
                    cursor = Some(next)
                }
                _ if !page.has_more => break,
                _ => {
                    return Err(CloudError::InvalidBody(
                        "Missing or repeated Agent page cursor.".into(),
                    ));
                }
            }
        }
        let default_agent = default_agent.ok_or_else(|| {
            CloudError::InvalidBody("The Cloud account has no default Agent.".into())
        })?;
        Ok((agents, default_agent))
    }

    async fn conversation_changed(
        &self,
        server: &str,
        id: &str,
        version: i64,
    ) -> CloudResult<bool> {
        let url = format!("{server}/api/sync");
        let mut cursor = None;
        loop {
            let page = self
                .with_session(self.client.sync_conversations_page(
                    &url,
                    version,
                    cursor.as_deref(),
                    100,
                    None,
                ))
                .await?;
            if page.documents.iter().any(|row| row.id == id) {
                return Ok(true);
            }
            match page.next_cursor {
                Some(next) if page.has_more && cursor.as_ref() != Some(&next) => {
                    cursor = Some(next)
                }
                _ if !page.has_more => return Ok(false),
                _ => {
                    return Err(CloudError::InvalidBody(
                        "Missing or repeated conversation page cursor.".into(),
                    ));
                }
            }
        }
    }
}
