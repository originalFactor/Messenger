//! SaaS HTTP client (port of `CloudApiClient.kt`). Session auth is the
//! `messenger_session` cookie scoped to one host — carried as an explicit
//! `Cookie` header on every request instead of a cookie jar (the app stores
//! exactly one session at a time, same as the Kotlin PersistentCookieStorage).

use serde::de::DeserializeOwned;
use serde::Serialize;

use crate::models::*;

#[derive(Debug, thiserror::Error)]
pub enum CloudError {
    #[error("HTTP {status}: {message}")]
    Http { status: u16, message: String },
    #[error("network error: {0}")]
    Network(String),
    #[error("failed to decode response: {0}")]
    InvalidBody(String),
}

pub type CloudResult<T> = Result<T, CloudError>;

/// Session state: cookie string (`name=value`) + the host it belongs to.
#[derive(Debug, Clone, Default)]
pub struct Session {
    pub cookie: Option<String>,
    pub host: Option<String>,
}

pub struct CloudApiClient {
    http: reqwest::Client,
    session: Session,
}

impl CloudApiClient {
    pub fn new(session: Session) -> Self {
        Self {
            http: reqwest::Client::new(),
            session,
        }
    }

    pub fn session(&self) -> &Session {
        &self.session
    }

    // -- auth --

    pub async fn register(&self, url: &str, body: &CredentialsRequest) -> CloudResult<CloudUser> {
        let resp: UserResponse = self.post_json(url, body).await?;
        Ok(resp.user)
    }

    pub async fn login(&self, url: &str, body: &CredentialsRequest) -> CloudResult<CloudUser> {
        let resp: UserResponse = self.post_json(url, body).await?;
        Ok(resp.user)
    }

    pub async fn logout(&self, url: &str) -> CloudResult<SuccessResponse> {
        self.post_empty(url).await
    }

    pub async fn me(&self, url: &str) -> CloudResult<CloudUser> {
        let resp: UserResponse = self.get_json(url).await?;
        Ok(resp.user)
    }

    pub async fn change_password(
        &self,
        url: &str,
        body: &CredentialsPair,
    ) -> CloudResult<SuccessResponse> {
        self.put_json(url, body).await
    }

    pub async fn delete_account(&self, url: &str, body: &CredentialsPair) -> CloudResult<SuccessResponse> {
        self.delete_with_body_json(url, body).await
    }

    // -- sync pull (paged per collection) --

    pub async fn sync_agents_page(
        &self,
        url: &str,
        since: i64,
        cursor: Option<&str>,
        limit: u32,
    ) -> CloudResult<CloudSyncPage<CloudAgentDocument>> {
        let full = with_query(url, &sync_params(since, "agents", cursor, limit));
        self.get_json(&full).await
    }

    pub async fn sync_conversations_page(
        &self,
        url: &str,
        since: i64,
        cursor: Option<&str>,
        limit: u32,
    ) -> CloudResult<CloudSyncPage<CloudConversationDocument>> {
        let full = with_query(url, &sync_params(since, "conversations", cursor, limit));
        self.get_json(&full).await
    }

    pub async fn sync_providers_page(
        &self,
        url: &str,
        since: i64,
        cursor: Option<&str>,
        limit: u32,
    ) -> CloudResult<CloudSyncPage<CloudProviderDocument>> {
        let full = with_query(url, &sync_params(since, "providers", cursor, limit));
        self.get_json(&full).await
    }

    // -- entity push --

    pub async fn put_agent(&self, url: &str, body: &CloudAgentRequest) -> CloudResult<CloudUpsertResponse> {
        self.put_json(url, body).await
    }

    pub async fn delete_agent(&self, url: &str) -> CloudResult<CloudUpsertResponse> {
        self.delete_json(url).await
    }

    pub async fn put_conversation(
        &self,
        url: &str,
        body: &CloudConversationRequest,
    ) -> CloudResult<CloudUpsertResponse> {
        self.put_json(url, body).await
    }

    pub async fn delete_conversation(&self, url: &str) -> CloudResult<CloudUpsertResponse> {
        self.delete_json(url).await
    }

    pub async fn put_provider(
        &self,
        url: &str,
        body: &CloudProviderRequest,
    ) -> CloudResult<CloudUpsertResponse> {
        self.put_json(url, body).await
    }

    pub async fn delete_provider(&self, url: &str) -> CloudResult<CloudUpsertResponse> {
        self.delete_json(url).await
    }

    // -- cards --

    pub async fn preview_redeem_card(&self, url: &str, code: &str) -> CloudResult<CloudCardPreviewResponse> {
        self.post_json(url, &RedeemCodeRequest { code: code.to_string() }).await
    }

    pub async fn redeem_card(&self, url: &str, code: &str) -> CloudResult<CloudRedeemResponse> {
        self.post_json(url, &RedeemCodeRequest { code: code.to_string() }).await
    }

    // -- market --

    pub async fn list_market_agents(
        &self,
        url: &str,
        query: &str,
        cursor: Option<&str>,
    ) -> CloudResult<CloudMarketAgentListResponse> {
        let mut params = vec![("query".to_string(), query.to_string())];
        if let Some(c) = cursor {
            params.push(("cursor".to_string(), c.to_string()));
        }
        let full = with_query(url, &params);
        self.get_json(&full).await
    }

    pub async fn get_market_agent(&self, url: &str) -> CloudResult<CloudMarketAgentResponse> {
        self.get_json(url).await
    }

    pub async fn create_market_agent(
        &self,
        url: &str,
        body: &CloudMarketAgentRequest,
    ) -> CloudResult<CloudMarketAgentResponse> {
        self.post_json(url, body).await
    }

    pub async fn update_market_agent(
        &self,
        url: &str,
        body: &CloudMarketAgentRequest,
    ) -> CloudResult<CloudMarketAgentResponse> {
        self.put_json(url, body).await
    }

    pub async fn delete_market_agent(&self, url: &str) -> CloudResult<SuccessResponse> {
        self.delete_json(url).await
    }

    // -- avatar upload/delete & conditional download --

    pub async fn upload_avatar(
        &self,
        url: &str,
        filename: &str,
        bytes: Vec<u8>,
        mime: &str,
    ) -> CloudResult<CloudAvatarResponse> {
        let part = reqwest::multipart::Part::bytes(bytes)
            .file_name(filename.to_string())
            .mime_str(mime)
            .map_err(|e| CloudError::Network(e.to_string()))?;
        let form = reqwest::multipart::Form::new().part("file", part);
        let request = self.add_session(self.http.put(url).multipart(form));
        let response = request
            .send()
            .await
            .map_err(|e| CloudError::Network(e.to_string()))?;
        let status = response.status();
        if !status.is_success() {
            let code = status.as_u16();
            let raw = response.text().await.unwrap_or_default();
            let message = if raw.is_empty() {
                format!("HTTP {code}")
            } else {
                crate::documents::extract_error_message(&raw)
            };
            return Err(CloudError::Http { status: code, message });
        }
        response
            .json::<CloudAvatarResponse>()
            .await
            .map_err(|e| CloudError::InvalidBody(e.to_string()))
    }

    pub async fn delete_avatar(&self, url: &str) -> CloudResult<CloudAvatarResponse> {
        self.delete_json(url).await
    }

    /// Download avatar with optional ETag for 304 conditional request.
    /// Returns:
    /// - Ok(None) on HTTP 304 Not Modified
    /// - Ok(Some((bytes, etag, content_type))) on HTTP 200
    pub async fn download_avatar_conditional(
        &self,
        url: &str,
        if_none_match: Option<&str>,
    ) -> CloudResult<Option<(Vec<u8>, Option<String>, Option<String>)>> {
        let mut builder = self.http.get(url);
        builder = self.add_session(builder);
        if let Some(etag) = if_none_match {
            builder = builder.header(reqwest::header::IF_NONE_MATCH, etag);
        }
        let response = builder
            .send()
            .await
            .map_err(|e| CloudError::Network(e.to_string()))?;
        let status = response.status();
        if status == reqwest::StatusCode::NOT_MODIFIED {
            return Ok(None);
        }
        if !status.is_success() {
            let code = status.as_u16();
            let raw = response.text().await.unwrap_or_default();
            let message = if raw.is_empty() {
                format!("HTTP {code}")
            } else {
                crate::documents::extract_error_message(&raw)
            };
            return Err(CloudError::Http { status: code, message });
        }
        let etag = response
            .headers()
            .get(reqwest::header::ETAG)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string);
        let content_type = response
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .map(str::to_string);
        let bytes = response
            .bytes()
            .await
            .map_err(|e| CloudError::Network(e.to_string()))?
            .to_vec();
        Ok(Some((bytes, etag, content_type)))
    }

    // -- internals --

    fn add_session(&self, request: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        match (&self.session.cookie, &self.session.host) {
            (Some(cookie), Some(host)) if !cookie.is_empty() && !host.is_empty() => {
                request.header("Cookie", cookie.as_str())
            }
            _ => request,
        }
    }

    async fn send<T: DeserializeOwned>(&self, request: reqwest::RequestBuilder) -> CloudResult<T> {
        let response = self
            .add_session(request)
            .send()
            .await
            .map_err(|e| CloudError::Network(e.to_string()))?;
        let status = response.status();
        if !status.is_success() {
            let code = status.as_u16();
            let raw = response.text().await.unwrap_or_default();
            let message = if raw.is_empty() {
                format!("HTTP {code}")
            } else {
                crate::documents::extract_error_message(&raw)
            };
            return Err(CloudError::Http { status: code, message });
        }
        response
            .json::<T>()
            .await
            .map_err(|e| CloudError::InvalidBody(e.to_string()))
    }

    async fn get_json<T: DeserializeOwned>(&self, url: &str) -> CloudResult<T> {
        self.send(self.http.get(url)).await
    }

    async fn post_json<T: DeserializeOwned, B: Serialize>(
        &self,
        url: &str,
        body: &B,
    ) -> CloudResult<T> {
        self.send(self.http.post(url).json(body)).await
    }

    async fn post_empty<T: DeserializeOwned>(&self, url: &str) -> CloudResult<T> {
        self.send(self.http.post(url)).await
    }

    async fn put_json<T: DeserializeOwned, B: Serialize>(
        &self,
        url: &str,
        body: &B,
    ) -> CloudResult<T> {
        self.send(self.http.put(url).json(body)).await
    }

    async fn delete_json<T: DeserializeOwned>(&self, url: &str) -> CloudResult<T> {
        self.send(self.http.delete(url)).await
    }

    async fn delete_with_body_json<T: DeserializeOwned, B: Serialize>(
        &self,
        url: &str,
        body: &B,
    ) -> CloudResult<T> {
        self.send(self.http.delete(url).json(body)).await
    }
}

/// Password-shaped bodies share the two-field JSON of credentials.
#[derive(Debug, Clone, Serialize)]
pub struct CredentialsPair {
    #[serde(rename = "currentPassword")]
    pub current_password: String,
    #[serde(rename = "newPassword")]
    pub new_password: String,
}

impl CloudError {
    /// `Display`-any error as a network error (used where the source is a
    /// rusqlite/`String` failure rather than a transport one).
    pub fn network(error: impl std::fmt::Display) -> Self {
        CloudError::Network(error.to_string())
    }
}

fn sync_params(since: i64, collection: &str, cursor: Option<&str>, limit: u32) -> Vec<(String, String)> {
    let mut params = vec![
        ("since".to_string(), since.to_string()),
        ("collection".to_string(), collection.to_string()),
        ("limit".to_string(), limit.to_string()),
    ];
    if let Some(cursor) = cursor {
        params.push(("cursor".to_string(), cursor.to_string()));
    }
    params
}

fn with_query(url: &str, params: &[(String, String)]) -> String {
    let separator = if url.contains('?') { '&' } else { '?' };
    let query = params
        .iter()
        .map(|(k, v)| format!("{k}={v}"))
        .collect::<Vec<_>>()
        .join("&");
    format!("{url}{separator}{query}")
}
