# Core SSE contract

All streams use `text/event-stream`. Chat events are named `message`; the
`data:` payload is JSON with a stable `type` discriminator.

## Core change feed (`GET /api/v1/events`)

- `change`: `{ "kind": "Provider|Model|Agent|Project|Conversation|Message|Other", "ids": [...] }`
- `resync`: a client lagged the bounded broadcast buffer and must re-query.

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

A turn ends with exactly one of `Finished`, `Cancelled`, or `Error`. If the HTTP
stream closes without one, clients emit a local `stream_ended` error.

For `ToolCallRequested`, the client executes the tool and posts the result to
`POST /api/v1/turns/tools/{callId}/result` with
`{ "output": string, "isError": boolean }`. Dropping the SSE connection cancels
the turn and wakes any pending tool host with a cancellation result.
