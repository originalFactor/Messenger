# Background task shutdown inventory

`ServerHandle::stop()` cancels the per-start shutdown token, joins the axum
listener, joins the remote-control and scheduler loops held in its `JoinSet`,
and abort/joins local in-process tasks registered through `ApiState.tasks`.
That is not yet proof that every external side effect or Core-owned worker
stopped. The inventory below is the remaining gap and is the reason
`androidApp/docs/CORE_CUTOVER_OWNERSHIP.md` still forbids a production Room
cutover.

## Tracked by `ServerHandle`

- axum listener with graceful shutdown and `ShutdownStream` for endless SSE;
- `RemoteHub::run_with_control` (remote-control/workspace relay connection loop);
- `scheduled_tasks::run` (timer loop).
- `ApiState.tasks` local request tasks: native/legacy turn workers, background
  turn replay and event fan-out, control command dispatch/event logging/queued
  sync, remote control client lanes, and one scheduled task execution per tick.
  These are aborted and joined on `stop()`. A test registers a pending tracked
  task and verifies `stop()` does not leave the handle running.
- The `AgentRelay` loopback listener created through the control API is held by
  `ApiState` and joined by both `DELETE /control/agent-relay` and
  `ServerHandle::stop()`. Its upstream SSE conversion workers still stop through
  the shutdown token and remain part of the external-side-effect gap below.

## Request-spawned tasks still needing an owner

| Source | Work started | Stop condition / missing proof |
| --- | --- | --- |
| `lib.rs::start_turn_inner` | legacy/native chat turn worker | now tracked/abort-joined; a provider/tool side effect outside the process still needs its own ambiguity record. |
| `background_turn.rs` | replay/event fan-out and SSE event iterators | now tracked/abort-joined; replay generation starts before returning the SSE response. |
| `control.rs` | local/background command dispatch, task-event recording and queued Cloud sync | now tracked/abort-joined; queued network work may remain ambiguous until sync recovery. |
| `remote_client.rs` | remote control client lane | now tracked/abort-joined; a remote host command may have executed and must not be replayed. |
| `scheduled_tasks.rs` | one task execution per tick | now tracked/abort-joined; externally dispatched work needs its durable claim and recovery. |
| `image_generation.rs` | one provider job per admitted image request | explicit cancel or provider completion; a paid/remote request may remain ambiguous. |
| `delegation.rs` | ACP delegation event drain | awaited by the delegating request, but external ACP descendants are separate. |
| `external_acp.rs`, `android_acp.rs`, `acp_mcp.rs` | ACP process reader/writer, per-worker loopback MCP servers, RPC/message tasks | lifetime token/cancel or channel close; direct child exit does not prove descendants stopped. |
| `agent_relay.rs` | model relay listener and SSE conversion workers | loopback listener is now taken and joined by relay stop / server stop; upstream SSE conversion workers still rely on the shutdown token and a provider-side stream ending. |

Tasks that only forward bytes or settle a local channel can be joined by a
single `ApiState` task tracker. Tasks that own an OS process, a detached
descendant, a paid remote request or a remote tool call also need an
authoritative stop/ambiguity barrier. An in-process `JoinHandle` alone cannot
prove an Android Runtime child, ACP subprocess tree or remote side effect has
stopped.

## Required implementation sequence

1. Done for local tasks: `ApiState.tasks` tracks abort-safe in-process work and
   `ServerHandle::stop` joins it. Keep future `tokio::spawn` calls that use
   `ApiState` in this tracker. A failed or non-terminating task must keep
   ownership live and return a visible failure, never a timeout that silently
   releases the database.
2. Still required: give `AgentRelay`, `ImageGenerationService`, external ACP sessions and
   Android Runtime processes explicit stop-and-join barriers. Record ambiguous
   outcomes durably; never replay a paid or remote call.
3. Add shutdown tests that start one representative of each task class, cancel
   while it is in flight, and assert `stop()` does not return until all handles
   are joined. Real device tests must separately cover Runtime/ACP descendants.
4. Only after those tests and platform fences pass may the Android/Desktop
   production lifecycle claim that an old Core instance and its workers cannot
   write after shutdown.
