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

## Agent Skills (device-local Messenger turns)

`PUT /api/v1/skills/{name}` stores packages; `POST /api/v1/skills/validate`
previews imports without writes. Mutations emit the existing `change` feed with
`kind: "Other"` and `ids: ["agent_skill:<name>"]`; re-query `/skills` or the detail.
There are no new SSE event types.

Native chat and device-control Messenger turns share progressive disclosure:
when effective agent/conversation tool settings permit `messenger_skills` and
`disableTools` is false, the system prompt includes enabled skill names and
descriptions as escaped JSON metadata. The Core-owned `messenger_skills` tool
accepts `{name, path?}` (`path` defaults to `SKILL.md`) and returns bundled text.
It emits `ToolCallStarted` / `ToolCallFinished`, without `ToolCallRequested` or
platform execution. Disabled/deleted skills and invalid/missing/binary paths
produce an `isError` result. Binary assets remain preserved in package detail
as `binaryFiles` base64 but cannot be read as text by this tool.

Skill scripts are never executed by import or by the reader. `allowed-tools`
is informational and cannot change permissions. Client-supplied declarations
cannot replace this reserved tool. Skill metadata/files are not injected into
title generation or third-party ACP sessions; packages remain device-local.

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

ACP `SessionSettings` snapshots also include Agent `availableCommands`, `usage` (`used`/`size`) and advertised `steering` metadata. Local control `prompt` accepts ACP content blocks; `steer` forwards `_session/steering` with host-owned `promptRequired` idle fallback and returns `Steering.result.outcome`. Injected inputs persist with the owning transcript. Control request bodies are bounded at 24 MiB.

## Agent model relay streams

The separate authenticated loopback model relay projects Chat, Responses,
Messages and Interactions SSE into the caller protocol according to the selected
Provider. Converted streams preserve text increments, reasoning summaries,
function calls, usage and terminal events. A truncated upstream stream emits
`response.failed` for Responses clients or `error` for the other protocols.
Client disconnect and relay shutdown cancel upstream reads and blocked writes.
