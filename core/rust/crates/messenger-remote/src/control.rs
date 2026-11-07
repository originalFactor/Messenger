//! Account relay transport for device-owned sessions.
use std::{collections::HashMap, sync::Arc, time::Duration};

use async_trait::async_trait;
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::{sync::mpsc, task::JoinHandle};
use tokio_util::sync::CancellationToken;

#[async_trait]
pub trait ControlHost: Send + Sync {
    fn advertised_name(&self) -> Option<String>;
    async fn dispatch(
        &self,
        request: Value,
        cancel: CancellationToken,
        permissions: mpsc::Receiver<String>,
        updates: mpsc::Sender<Value>,
    ) -> Value;
}

struct Job {
    cancel: CancellationToken,
    permissions: mpsc::Sender<String>,
    task: JoinHandle<()>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Frame {
    id: String,
    payload: Option<Value>,
    option_id: Option<String>,
}

pub(crate) struct ControlConnection {
    host: Arc<dyn ControlHost>,
    http: reqwest::Client,
    endpoint: String,
    cookie: String,
    credential: Option<String>,
    advertised: Option<String>,
    jobs: HashMap<String, Job>,
}

impl ControlConnection {
    pub fn new(
        host: Arc<dyn ControlHost>,
        http: reqwest::Client,
        endpoint: String,
        cookie: String,
    ) -> Self {
        Self {
            host,
            http,
            endpoint,
            cookie,
            credential: None,
            advertised: None,
            jobs: HashMap::new(),
        }
    }

    pub async fn refresh(&mut self) {
        self.jobs.retain(|_, job| !job.task.is_finished());
        let name = self.host.advertised_name();
        if name == self.advertised {
            return;
        }
        if name.is_none() {
            self.cancel_jobs();
        }
        if let Some(credential) = &self.credential {
            if post(
                &self.http,
                &self.endpoint,
                &self.cookie,
                json!({
                    "connectionId": credential, "kind": "advertise", "enabled": name.is_some(),
                    "name": name.as_deref().unwrap_or_default()
                }),
            )
            .await
            {
                self.advertised = name;
            }
        }
    }

    pub async fn receive(&mut self, event: &str, data: &str) {
        if event == "controlReady" {
            self.credential = serde_json::from_str::<Value>(data)
                .ok()
                .and_then(|value| value["connectionId"].as_str().map(str::to_owned));
            self.refresh().await;
            return;
        }
        let Some(credential) = self.credential.clone() else {
            return;
        };
        let Ok(frame) = serde_json::from_str::<Frame>(data) else {
            return;
        };
        if event == "controlSignal" {
            if let Some(job) = self.jobs.get(&frame.id) {
                if let Some(option) = frame.option_id {
                    let _ = job.permissions.try_send(option);
                } else {
                    job.cancel.cancel();
                }
            }
            return;
        }
        if event != "controlRequest" || self.jobs.contains_key(&frame.id) {
            return;
        }
        let Some(request) = frame.payload else { return };
        let host = self.host.clone();
        let http = self.http.clone();
        let endpoint = self.endpoint.clone();
        let cookie = self.cookie.clone();
        let cancel = CancellationToken::new();
        let task_cancel = cancel.clone();
        let (permissions, choices) = mpsc::channel(8);
        let (updates, mut events) = mpsc::channel(64);
        let id = frame.id;
        let task_id = id.clone();
        let task = tokio::spawn(async move {
            let reply = |payload: Value, done| {
                post(
                    &http,
                    &endpoint,
                    &cookie,
                    json!({
                        "connectionId": credential, "kind": "reply", "id": task_id,
                        "payload": payload, "done": done
                    }),
                )
            };
            let operation = async {
                if host.advertised_name().is_none() {
                    return json!({"type":"Error", "code":"control_disabled", "message":"Remote control is disabled."});
                }
                host.dispatch(request, task_cancel.clone(), choices, updates)
                    .await
            };
            tokio::pin!(operation);
            let result = loop {
                tokio::select! {
                    result = &mut operation => break result,
                    Some(event) = events.recv() => {
                        if !reply(event, false).await {
                            task_cancel.cancel();
                        }
                    }
                }
            };
            while let Ok(event) = events.try_recv() {
                if !reply(event, false).await {
                    break;
                }
            }
            let _ = reply(result, true).await;
        });
        self.jobs.insert(
            id,
            Job {
                cancel,
                permissions,
                task,
            },
        );
    }

    fn cancel_jobs(&self) {
        for job in self.jobs.values() {
            job.cancel.cancel();
        }
    }
}

impl Drop for ControlConnection {
    fn drop(&mut self) {
        self.cancel_jobs();
    }
}

async fn post(http: &reqwest::Client, endpoint: &str, cookie: &str, body: Value) -> bool {
    http.post(endpoint)
        .header("Cookie", cookie)
        .json(&body)
        .timeout(Duration::from_secs(5))
        .send()
        .await
        .is_ok_and(|reply| reply.status().is_success())
}
