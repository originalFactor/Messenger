//! Minimal native lifecycle boundary.
//!
//! The app shells use this only to open the SQLite store and start/stop the
//! loopback HTTP API. All business calls travel over `/api/v1`; no entity,
//! cloud, chat, or document operation is exported through UniFFI.

use std::{net::SocketAddr, sync::Arc};

use messenger_store::{import_legacy, Store};

#[derive(uniffi::Object)]
pub struct CoreHandle {
    store: Arc<Store>,
}

#[derive(uniffi::Object)]
pub struct HttpServerHandle {
    local_addr: String,
    token: String,
    server: tokio::sync::Mutex<Option<messenger_http::ServerHandle>>,
}

#[derive(uniffi::Error, thiserror::Error, Debug)]
pub enum CoreError {
    #[error("{detail}")]
    Generic { detail: String },
}

#[uniffi::export]
impl CoreHandle {
    #[uniffi::constructor]
    pub fn open(
        store_path: String,
        legacy_db_dir: Option<String>,
        now_ms: i64,
    ) -> Result<Arc<Self>, CoreError> {
        let store = Arc::new(Store::open(std::path::Path::new(&store_path)).map_err(|e| {
            CoreError::Generic {
                detail: e.to_string(),
            }
        })?);
        if let Some(dir) = legacy_db_dir {
            if let Err(error) = import_legacy(&store, std::path::Path::new(&dir), now_ms) {
                eprintln!("messenger-core: legacy import failed: {error}");
            }
        }
        Ok(Arc::new(Self { store }))
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn start_http_server(&self, port: u16) -> Result<Arc<HttpServerHandle>, CoreError> {
        let bind = SocketAddr::from(([127, 0, 0, 1], port));
        let server = messenger_http::ServerHandle::start(Arc::clone(&self.store), bind)
            .await
            .map_err(|error| CoreError::Generic {
                detail: error.to_string(),
            })?;
        Ok(Arc::new(HttpServerHandle {
            local_addr: server.local_addr.to_string(),
            token: server.token.clone(),
            server: tokio::sync::Mutex::new(Some(server)),
        }))
    }
}

#[uniffi::export]
impl HttpServerHandle {
    pub fn local_addr(&self) -> String {
        self.local_addr.clone()
    }

    pub fn token(&self) -> String {
        self.token.clone()
    }

    #[uniffi::method(async_runtime = "tokio")]
    pub async fn stop(&self) -> Result<(), CoreError> {
        let server = self.server.lock().await.take();
        if let Some(server) = server {
            server.stop().await.map_err(|error| CoreError::Generic {
                detail: error.to_string(),
            })?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn starts_and_stops_the_loopback_server() {
        let dir = tempfile::tempdir().unwrap();
        let core = CoreHandle::open(
            dir.path().join("store.db").to_string_lossy().to_string(),
            None,
            1,
        )
        .unwrap();
        let server = core.start_http_server(0).await.unwrap();
        assert!(server.local_addr().starts_with("127.0.0.1:"));
        assert!(server.token().starts_with("messenger-"));
        server.stop().await.unwrap();
        assert!(server.local_addr().starts_with("127.0.0.1:"));
    }
}
