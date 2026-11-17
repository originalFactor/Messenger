# Background task shutdown inventory

`ServerHandle::stop()` cancels the per-start shutdown token, joins the axum
listener, and joins the remote-control and scheduler loops held in its
`JoinSet`. That is not yet proof that every Core-owned task stopped. The
inventory below is the gap between those two guarantees and is the reason
`androidApp/docs/CORE_CUTOVER_OWNERSHIP.md` still forbids a production Room
cutover.

## Tracked by `ServerHandle`

- axum listener with graceful shutdown and `ShutdownStream` for endless SSE;
- `RemoteHub::run_with_control` (remote-control/workspace relay connection loop);
- `scheduled_tasks::run` (timer loop).

## Request-spawned tasks still needing an owner

| Source | Work started | Stop condition / missing proof |
| --- | --- | --- |
| `lib.rs::start_turn_inner` | legacy/native chat turn worker | cancellation token ends provider/tool await; the spawned `JoinHandle` is not joined by server stop. |
| `background_turn.rs` | replay/event fan-out and SSE event iterators | channel close or shutdown token; detached handles are not joined. |
| `control.rs` | local/background command dispatch, task-event recording and queued Cloud sync | request cancellation, channel close or shutdown check; worker handles are not joined. |
| `remote_client.rs` | remote control client lane | lane cancel, observer close or session check; network timeout/host work is not joined by stop. |
| `scheduled_tasks.rs` | one task execution per tick | task cancel token; individual execution handles are not joined. |
| `image_generation.rs` | one provider job per admitted image request | explicit cancel or provider completion; a paid/remote request may remain ambiguous. |
| `delegation.rs` | ACP delegation event drain | awaited by the delegating request, but external ACP descendants are separate. |
| `external_acp.rs`, `android_acp.rs`, `acp_mcp.rs` | ACP process reader/writer, per-worker loopback MCP servers, RPC/message tasks | lifetime token/cancel or channel close; direct child exit does not prove descendants stopped. |
| `agent_relay.rs` | model relay listener and SSE conversion workers | per-relay shutdown token; listener/workers are owned by `AgentRelay`, not `ServerHandle`. |

Tasks that only forward bytes or settle a local channel can be joined by a
single `ApiState` task tracker. Tasks that own an OS process, a detached
descendant, a paid remote request or a remote tool call also need an
authoritative stop/ambiguity barrier. An in-process `JoinHandle` alone cannot
prove an Android Runtime child, ACP subprocess tree or remote side effect has
stopped.

## Required implementation sequence

1. Add an owner-scoped `TaskScope` to `ApiState`; replace production
   `tokio::spawn` calls that use `ApiState` with a tracked spawn helper.
2. In `ServerHandle::stop`, cancel the shutdown token, close axum, then join
   the server loops and every tracked request task. A failed or non-terminating
   task must keep ownership live and return a visible failure, never a timeout
   that silently releases the database.
3. Give `AgentRelay`, `ImageGenerationService`, external ACP sessions and
   Android Runtime processes explicit stop-and-join barriers. Record ambiguous
   outcomes durably; never replay a paid or remote call.
4. Add shutdown tests that start one representative of each task class, cancel
   while it is in flight, and assert `stop()` does not return until all handles
   are joined. Real device tests must separately cover Runtime/ACP descendants.
5. Only after those tests and platform fences pass may the Android/Desktop
   production lifecycle claim that an old Core instance and its workers cannot
   write after shutdown.
