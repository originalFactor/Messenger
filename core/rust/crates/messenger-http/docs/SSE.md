# Core SSE contract

All streams use `text/event-stream`. Chat events are named `message`; the
`data:` payload is JSON with a stable `type` discriminator.

## Core change feed (`GET /api/v1/events`)

- `change`: `{ "kind": "Provider|Model|Agent|Project|Conversation|Message|Other", "ids": [...] }`
- `resync`: a client lagged the bounded broadcast buffer and must re-query.
- `remoteTool`: `{ callId, projectId, conversationId, workspace, name, argumentsJson }` —
  another device of the account asked this one to run a workspace tool of a
  project it owns. The platform client executes it in `workspace` exactly like a
  `ToolCallRequested` and posts the result to
  `POST /api/v1/turns/tools/{callId}/result` (`callId` is the namespaced
  `remote:…` id from the event).
- `remoteToolCancelled`: `{ callId }` — the initiator gave up; stop the running
  tool. A late result for this id is answered with 404 and dropped.

The feed is endless and shared by every request: it stays subscribed to store
changes for the server's whole lifetime. It ends only when the server stops
(`ServerHandle::stop`), which closes it so the graceful shutdown can finish.

## Chat (`POST /api/v1/conversations/{id}/messages`)

Event types:

- `TurnStarted`
- `StreamingStarted { messageId }`
- `TextDelta { round, text }`
- `ReasoningFormatDetected { format }`
- `RoundPersisted { round, messageId }`
- `FinalMessagePersisted { messageId, content }`
- `ToolCallStarted { callId, name }`
- `ToolCallRequested { callId, name, argumentsJson }`
- `ToolCallFinished { callId, name, output, isError }`
- `UsageRecorded { promptTokens, completionTokens, cachedTokens }`
- `TitleGenerated { title }`
- `TitleFailed { code }`
- `Finished { messageId }`
- `Cancelled`
- `Error { code, message }`

A conversation of a project owned by another device streams the same events,
but its workspace tools run on the owner: `ToolCallStarted`/`ToolCallFinished`
appear, `ToolCallRequested` does not. If the owner goes offline mid-call the call
finishes with an `isError` result the model can read.

A turn ends with exactly one of `Finished`, `Cancelled`, or `Error`. If the HTTP
stream closes without one, clients emit a local `stream_ended` error.

After receiving a terminal event, the TUI can upload the persisted conversation
through `POST /api/v1/cloud/conversations/push`. Upload errors are separate from
the chat stream.

For `ToolCallRequested`, the client executes the tool and posts the result to
`POST /api/v1/turns/tools/{callId}/result` with
`{ "output": string, "isError": boolean }`. Dropping the SSE connection cancels
the turn and wakes any pending tool host with a cancellation result.

## Device session commands (`POST /api/v1/control/commands/{requestId}`)

Non-turn actions finish with `Catalog`, `Created`, `History`, `Mode`, `Store`, `SessionSettings` or
`Error`. Catalog sessions include `projectId` (nullable) and `updatedAt`
(milliseconds since the Unix epoch, or 0 for legacy ACP sessions). Prompt commands stream the turn events above, ending in `Finished`,
`Cancelled` or `Error`. The device sends `UserMessagePersisted {messageId}`
when the Messenger user row is accepted. Third-party ACP turns can also send
`ThoughtDelta {text}`, `ToolUpdate {update}` and
`Permission {tool, options: [{id, name, kind}]}`. Only the client that
started the turn can respond through its active command. For cloud control
the relay carries the same payloads in `controlReply` frames.

On Android, the local events feed can additionally emit
`acpProcess {processId, profile, cwd}` to the signature-protected Runtime
bridge in the main app. The process input feed emits `{line}` and ends when
Core no longer needs the process. See [ACP.md](ACP.md) for the endpoint pair.

`store {kind:"acp"|"mcp"}` fetches the signed-in Cloud integration catalog and returns
`Store {items:[...]}`. ACP catalog agents include descriptions and omit disabled
profiles. Local ACP sessions also appear in the normal conversation list endpoints;
these are projections of local transcripts and never create Cloud sync rows.

ACP `sessionSettings {sessionId}` restores the Agent if necessary and returns
`SessionSettings {settings:{configOptions,modes?}}`. `setSessionConfig` uses either
`{sessionId,configId,value}` (opaque select ID or boolean), or `{sessionId,modeId}`
for legacy modes. These commands reserve the session and reject active turns.
The Agent's response is authoritative: replace the full config option list after
a successful request, and preserve current settings on rejection. Agent
`config_option_update` / `current_mode_update` notifications update the snapshot;
during a turn they also emit `SessionSettings`. ACP terminal turn events include
the latest `settings`. Config changes time out after 30 seconds; cancellation
discards that worker so later reads restore actual Agent state.

ACP history rows include `timestamp` and optional `events` (thought/tool events).
`ToolUpdate.update` preserves Agent input, output, content and partial updates.
Native history pages contain complete local rows; Cloud control pages remain
bounded previews, with `eventsTruncated:true` if an oversized tool trace is omitted.
