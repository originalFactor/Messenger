#![cfg(unix)]

use std::{os::unix::fs::OpenOptionsExt, sync::Arc, time::Duration};

use messenger_http::ServerHandle;
use messenger_store::Store;
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

#[tokio::test]
async fn editor_stdio_uses_the_running_core_and_replays_its_transcript() {
    tokio::time::timeout(Duration::from_secs(15), async {
        let provider = wiremock::MockServer::start().await;
        wiremock::Mock::given(wiremock::matchers::method("POST"))
            .respond_with(wiremock::ResponseTemplate::new(200).set_body_string(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ACP reply\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
            ))
            .mount(&provider)
            .await;
        let store = Arc::new(Store::open_memory().unwrap());
        store.upsert_agent(&serde_json::from_value(json!({
            "id":"agent","name":"Default","system_prompt":"","is_default":true,
            "default_model_id":"model","created_at":1,"updated_at":1
        })).unwrap()).unwrap();
        store.upsert_provider(&serde_json::from_value(json!({
            "id":"provider","name":"Fixture","base_url":provider.uri(),"api_key":"test",
            "api_format":"chat","created_at":1,"updated_at":1
        })).unwrap()).unwrap();
        store.upsert_model(&serde_json::from_value(json!({
            "id":"model","provider_id":"provider","model_id":"test-model","display_name":"Fixture",
            "is_enabled":true,"context_window":0,"input_modalities":"text","output_modalities":"text",
            "supports_tool_calling":false,"supports_thinking":false,"supports_json_output":false,
            "supports_temperature":true,"created_at":1
        })).unwrap()).unwrap();

        let server = ServerHandle::start_local(store.clone()).await.unwrap();
        let path = std::env::temp_dir().join(format!("messenger-acp-{}.json", uuid::Uuid::new_v4()));
        let descriptor = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600)
            .open(&path).unwrap();
        serde_json::to_writer(descriptor, &json!({"address":server.local_addr,"token":server.token})).unwrap();
        let _descriptor = RemoveFile(path.clone());

        let mut child = tokio::process::Command::new(env!("CARGO_BIN_EXE_messenger-core-server"))
            .args(["acp", "--connect"]).arg(&path)
            .stdin(std::process::Stdio::piped()).stdout(std::process::Stdio::piped())
            .kill_on_drop(true).spawn().unwrap();
        let mut input = child.stdin.take().unwrap();
        let mut output = BufReader::new(child.stdout.take().unwrap()).lines();

        let initialize = json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1}});
        write(&mut input, initialize).await;
        let (reply, _) = receive(&mut output, 1).await;
        assert_eq!(reply["result"]["protocolVersion"], 1);
        assert_eq!(reply["result"]["agentCapabilities"]["loadSession"], true);

        let cwd = std::env::current_dir().unwrap();
        write(&mut input, json!({"jsonrpc":"2.0","id":2,"method":"session/new",
            "params":{"cwd":cwd,"mcpServers":[]}})).await;
        let (reply, _) = receive(&mut output, 2).await;
        let id = reply["result"]["sessionId"].as_str().unwrap();
        assert!(store.get_conversation(id).unwrap().is_some());

        write(&mut input, json!({"jsonrpc":"2.0","id":3,"method":"session/prompt",
            "params":{"sessionId":id,"prompt":[
                {"type":"text","text":"question"},
                {"type":"resource_link","name":"reference","uri":"file:///never-open-this"}
            ]}})).await;
        let (reply, notifications) = receive(&mut output, 3).await;
        assert_eq!(reply["result"]["stopReason"], "end_turn");
        assert!(notifications.iter().any(|update| update["content"]["text"] == "ACP reply"));
        assert!(store.list_messages_by_conversation(id).unwrap()[0].content.contains("file:///never-open-this"));

        write(&mut input, json!({"jsonrpc":"2.0","id":4,"method":"session/load",
            "params":{"sessionId":id,"cwd":cwd,"mcpServers":[]}})).await;
        let (_, replay) = receive(&mut output, 4).await;
        assert_eq!(replay[0]["sessionUpdate"], "user_message_chunk");
        assert_eq!(replay[1]["sessionUpdate"], "agent_message_chunk");
        assert_eq!(replay[1]["content"]["text"], "ACP reply");
        drop(input);
        assert!(child.wait().await.unwrap().success());
        server.stop().await.unwrap();
    }).await.unwrap();
}

async fn write(input: &mut tokio::process::ChildStdin, frame: Value) {
    input
        .write_all(format!("{frame}\n").as_bytes())
        .await
        .unwrap();
}

async fn receive(
    output: &mut tokio::io::Lines<BufReader<tokio::process::ChildStdout>>,
    id: u32,
) -> (Value, Vec<Value>) {
    let mut notifications = Vec::new();
    loop {
        let line = output.next_line().await.unwrap().expect("ACP stdio closed");
        let frame: Value = serde_json::from_str(&line).unwrap();
        if frame["id"] == id {
            assert!(frame["error"].is_null(), "{frame}");
            return (frame, notifications);
        }
        if frame["method"] == "session/update" {
            notifications.push(frame["params"]["update"].clone());
        }
    }
}

struct RemoveFile(std::path::PathBuf);

impl Drop for RemoveFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}
