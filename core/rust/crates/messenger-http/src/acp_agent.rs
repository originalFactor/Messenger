//! Local ACP v1 endpoint. The editor-facing stdio bridge forwards bytes only.
use super::*;
use agent_client_protocol::{
    schema::{v1::*, ProtocolVersion},
    Agent, ByteStreams, Client, ConnectionTo,
};
use axum::extract::{
    ws::{Message, WebSocket},
    WebSocketUpgrade,
};
use futures_util::SinkExt;
use messenger_core::parts::{decode_parts, ContentPart};
use std::{collections::HashSet, path::Path};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_util::compat::{TokioAsyncReadCompatExt, TokioAsyncWriteCompatExt};

pub(crate) async fn upgrade(
    State(state): State<ApiState>,
    socket: WebSocketUpgrade,
) -> axum::response::Response {
    socket
        .max_message_size(65_536)
        .max_frame_size(65_536)
        .on_upgrade(move |socket| bridge(state, socket))
}

async fn bridge(state: ApiState, socket: WebSocket) {
    let lifetime = state.shutdown.child_token();
    let _cancel = lifetime.clone().drop_guard();
    let (sdk, bridge) = tokio::io::duplex(65_536);
    let (sdk_read, sdk_write) = tokio::io::split(sdk);
    let (mut bridge_read, mut bridge_write) = tokio::io::split(bridge);
    let (mut output, mut input) = futures_util::StreamExt::split(socket);
    let outgoing = async {
        let mut bytes = [0; 8192];
        loop {
            let count = bridge_read.read(&mut bytes).await?;
            if count == 0 {
                break;
            }
            if output
                .send(Message::Binary(bytes[..count].to_vec()))
                .await
                .is_err()
            {
                break;
            }
        }
        Ok::<_, std::io::Error>(())
    };
    let incoming = async {
        while let Some(Ok(message)) = input.next().await {
            match message {
                Message::Binary(bytes) => bridge_write.write_all(&bytes).await?,
                Message::Text(text) => bridge_write.write_all(text.as_bytes()).await?,
                Message::Close(_) => break,
                _ => {}
            }
        }
        Ok::<_, std::io::Error>(())
    };
    let service = agent(
        state,
        lifetime.clone(),
        ByteStreams::new(sdk_write.compat_write(), sdk_read.compat()),
    );
    tokio::select! {
        _ = incoming => {},
        _ = outgoing => {},
        _ = service => {},
        _ = lifetime.cancelled() => {},
    }
}

async fn agent(
    state: ApiState,
    lifetime: CancellationToken,
    transport: impl agent_client_protocol::ConnectTo<Agent>,
) -> agent_client_protocol::Result<()> {
    let sessions = Arc::new(Mutex::new(HashMap::<String, CancellationToken>::new()));
    let turns = sessions.clone();
    let loaded = Arc::new(Mutex::new(HashSet::new()));
    let new_loaded = loaded.clone();
    let load_loaded = loaded.clone();
    let new_state = state.clone();
    let load_state = state.clone();
    let initialize = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let initialized_new = initialize.clone();
    let initialized_load = initialize.clone();
    let initialized_prompt = initialize.clone();
    Agent
        .builder()
        .name("Messenger")
        .on_receive_request(
            async move |_req: InitializeRequest, responder, _| {
                initialize.store(true, std::sync::atomic::Ordering::Release);
                responder.respond(
                    InitializeResponse::new(ProtocolVersion::V1)
                        .agent_info(Implementation::new(
                            "messenger-core",
                            messenger_core::version::NAME,
                        ))
                        .agent_capabilities(AgentCapabilities::new().load_session(true)),
                )
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |req: NewSessionRequest, responder, _| {
                require_initialized(&initialized_new)?;
                validate_setup(&req.cwd, &req.mcp_servers)?;
                let project = project_for_directory(&new_state, &req.cwd)?;
                let agent = new_state
                    .store
                    .get_default_agent()
                    .map_err(rpc_error)?
                    .ok_or_else(|| rpc_error("the default Messenger Agent is missing"))?;
                let session =
                    crate::control::create_conversation(&new_state, &agent.id, project, false)
                        .map_err(rpc_api_error)?;
                new_loaded.lock().await.insert(session.id.clone());
                responder.respond(NewSessionResponse::new(SessionId::new(session.id)))
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |req: LoadSessionRequest, responder, conn| {
                require_initialized(&initialized_load)?;
                validate_setup(&req.cwd, &req.mcp_servers)?;
                let conversation = crate::control::conversation(&load_state, &req.session_id.0)
                    .map_err(rpc_api_error)?;
                validate_project(&load_state, &conversation, &req.cwd)?;
                for row in load_state
                    .store
                    .list_messages_by_conversation(&conversation.id)
                    .map_err(rpc_error)?
                {
                    for update in replay(row) {
                        conn.send_notification(SessionNotification::new(
                            req.session_id.clone(),
                            update,
                        ))?;
                    }
                }
                load_loaded.lock().await.insert(conversation.id);
                responder.respond(LoadSessionResponse::new())
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_request(
            async move |req: PromptRequest, responder, conn| {
                require_initialized(&initialized_prompt)?;
                if !loaded.lock().await.contains(req.session_id.0.as_ref()) {
                    return Err(agent_client_protocol::Error::new(
                        -32002,
                        "Unknown session on this connection",
                    ));
                }
                let reason = prompt(&state, &turns, &lifetime, conn, req).await?;
                responder.respond(PromptResponse::new(reason))
            },
            agent_client_protocol::on_receive_request!(),
        )
        .on_receive_notification(
            async move |req: CancelNotification, _| {
                if let Some(token) = sessions.lock().await.get(req.session_id.0.as_ref()) {
                    token.cancel();
                }
                Ok(())
            },
            agent_client_protocol::on_receive_notification!(),
        )
        .connect_to(transport)
        .await
}

fn replay(row: StoredMessage) -> Vec<SessionUpdate> {
    let message = |block| {
        let chunk = ContentChunk::new(block).message_id(MessageId::new(row.id.clone()));
        match row.role.as_str() {
            "user" => Some(SessionUpdate::UserMessageChunk(chunk)),
            "assistant" => Some(SessionUpdate::AgentMessageChunk(chunk)),
            _ => None,
        }
    };
    let parts = decode_parts(row.parts_json.as_deref());
    if parts.is_empty() || row.error_message.is_some() {
        return message(ContentBlock::Text(TextContent::new(
            row.error_message.as_deref().unwrap_or(&row.content),
        )))
        .into_iter()
        .collect();
    }
    parts
        .into_iter()
        .filter_map(|part| match part {
            ContentPart::Text { text } => message(ContentBlock::Text(TextContent::new(text))),
            ContentPart::Image { image } => {
                let (header, data) = image.data_uri.split_once(',')?;
                let mime = header.strip_prefix("data:")?.strip_suffix(";base64")?;
                message(ContentBlock::Image(ImageContent::new(data, mime)))
            }
            ContentPart::ToolCall {
                call_id,
                name,
                arguments,
            } => {
                let call = ToolCall::new(ToolCallId::new(call_id), name)
                    .status(ToolCallStatus::InProgress)
                    .raw_input(serde_json::from_str::<serde_json::Value>(&arguments).ok());
                Some(SessionUpdate::ToolCall(call))
            }
            ContentPart::ToolResult {
                call_id,
                output,
                is_error,
                ..
            } => {
                let fields = ToolCallUpdateFields::new()
                    .status(if is_error {
                        ToolCallStatus::Failed
                    } else {
                        ToolCallStatus::Completed
                    })
                    .content(vec![ToolCallContent::from(ContentBlock::Text(
                        TextContent::new(output),
                    ))]);
                Some(SessionUpdate::ToolCallUpdate(ToolCallUpdate::new(
                    ToolCallId::new(call_id),
                    fields,
                )))
            }
        })
        .collect()
}

fn require_initialized(value: &std::sync::atomic::AtomicBool) -> agent_client_protocol::Result<()> {
    if value.load(std::sync::atomic::Ordering::Acquire) {
        return Ok(());
    }
    Err(agent_client_protocol::Error::new(
        -32000,
        "Initialize the ACP connection first",
    ))
}

fn validate_setup(cwd: &Path, mcp: &[McpServer]) -> agent_client_protocol::Result<()> {
    if !cwd.is_absolute() || !cwd.is_dir() || !mcp.is_empty() {
        return Err(agent_client_protocol::Error::new(
            -32602,
            "An absolute local working directory and empty mcpServers are required",
        ));
    }
    Ok(())
}

fn project_for_directory(
    state: &ApiState,
    cwd: &Path,
) -> agent_client_protocol::Result<Option<String>> {
    let canonical = cwd.canonicalize().map_err(rpc_error)?;
    let project = state
        .store
        .list_projects()
        .map_err(rpc_error)?
        .into_iter()
        .find(|p| {
            state.remote.owner_of(p) == ProjectOwner::Local
                && Path::new(&p.workspace)
                    .canonicalize()
                    .is_ok_and(|path| path == canonical)
        });
    Ok(project.map(|project| project.id))
}

fn validate_project(
    state: &ApiState,
    conversation: &StoredConversation,
    cwd: &Path,
) -> agent_client_protocol::Result<()> {
    if let Some(project) =
        crate::control::local_workspace(state, conversation.project_id.as_deref())
            .map_err(rpc_api_error)?
    {
        if Path::new(&project.workspace)
            .canonicalize()
            .map_err(rpc_error)?
            != cwd.canonicalize().map_err(rpc_error)?
        {
            return Err(agent_client_protocol::Error::new(
                -32602,
                "The conversation belongs to a different working directory",
            ));
        }
    }
    Ok(())
}

async fn prompt(
    state: &ApiState,
    turns: &Mutex<HashMap<String, CancellationToken>>,
    lifetime: &CancellationToken,
    conn: ConnectionTo<Client>,
    req: PromptRequest,
) -> agent_client_protocol::Result<StopReason> {
    let id = req.session_id.0.to_string();
    let conversation = crate::control::conversation(state, &id).map_err(rpc_api_error)?;
    crate::control::local_workspace(state, conversation.project_id.as_deref())
        .map_err(rpc_api_error)?;
    let mut text = Vec::new();
    for block in req.prompt {
        text.push(match block {
            ContentBlock::Text(value) => value.text,
            ContentBlock::ResourceLink(link) => format!("{} ({})", link.name, link.uri),
            _ => {
                return Err(agent_client_protocol::Error::new(
                    -32602,
                    "Unsupported prompt content",
                ))
            }
        });
    }
    let text = text.join("\n");
    if text.len() > 128_000 {
        return Err(agent_client_protocol::Error::new(
            -32602,
            "Prompt is too large",
        ));
    }
    let cancel = lifetime.child_token();
    let mut active = turns.lock().await;
    let mut events = start_turn(
        state.clone(),
        id.clone(),
        crate::control::text_request(text),
        cancel.clone(),
        true,
    )
    .await
    .map_err(rpc_api_error)?;
    active.insert(id.clone(), cancel.clone());
    drop(active);
    let _guard = cancel.drop_guard();
    let result = forward_turn(conn, req.session_id, &mut events).await;
    turns.lock().await.remove(&id);
    result
}

async fn forward_turn(
    conn: ConnectionTo<Client>,
    session: SessionId,
    events: &mut mpsc::UnboundedReceiver<String>,
) -> agent_client_protocol::Result<StopReason> {
    while let Some(frame) = events.recv().await {
        let event: serde_json::Value = serde_json::from_str(&frame).map_err(rpc_error)?;
        let string = |key: &str| event[key].as_str().unwrap_or_default();
        let update = match string("type") {
            "Finished" => return Ok(StopReason::EndTurn),
            "Cancelled" => return Ok(StopReason::Cancelled),
            "Error" => return Err(rpc_error(string("message"))),
            "TextDelta" => SessionUpdate::AgentMessageChunk(ContentChunk::new(ContentBlock::Text(
                TextContent::new(string("text")),
            ))),
            "ToolCallStarted" => SessionUpdate::ToolCall(
                ToolCall::new(ToolCallId::new(string("callId")), string("name"))
                    .status(ToolCallStatus::InProgress),
            ),
            "ToolCallFinished" => SessionUpdate::ToolCallUpdate(ToolCallUpdate::new(
                ToolCallId::new(string("callId")),
                ToolCallUpdateFields::new().status(if event["isError"] == true {
                    ToolCallStatus::Failed
                } else {
                    ToolCallStatus::Completed
                }),
            )),
            _ => continue,
        };
        conn.send_notification(SessionNotification::new(session.clone(), update))?;
    }
    Err(rpc_error("turn ended without a terminal event"))
}

fn rpc_error(error: impl std::fmt::Display) -> agent_client_protocol::Error {
    agent_client_protocol::Error::new(-32000, error.to_string())
}

fn rpc_api_error(error: ApiError) -> agent_client_protocol::Error {
    rpc_error(format!("{error:?}"))
}
