# Retirement-only background task inventory

This document records the ownership gap that prevents a production Kotlin/Room
cutover. It is **not** a plan to keep improving Rust Core. The product direction
is to migrate business behavior to Kotlin, prove a platform lifetime boundary
around the old process, then delete this crate and its bindings in one commit.
Do not add new Rust lifecycle features here.

`ServerHandle::stop()` cancels the per-start shutdown token and waits for the
axum listener. Endless `/events` streams are closed through `ShutdownStream`, so
the HTTP listener can return. A successful HTTP stop is not evidence that old
Core tasks, SQLite descriptors, assets, ACP workers, Runtime children, provider
calls or remote side effects have stopped.

## What the production cutover must prove instead

- Exclude every older Core instance, source SQLite file, WAL/sidecar, asset
  directory and competing Room writer for the entire ownership interval.
- Establish a platform authority boundary for Runtime and ACP process trees,
  including detached descendants, prior process generations and old companion
  versions. A Binder death, direct-child exit, in-process `JoinHandle` or
  cooperative marker is not such a proof.
- Prevent new work during the handoff and retain ambiguous outcomes without
  automatic replay. Preserve originals, backups and credentials privately.
- Keep the proof valid through cancellation, shutdown, process death and
  recovery. A user acknowledgement records uncertainty; it does not prove a
  detached or remote operation stopped.
- Verify on supported Android versions/OEMs and real Desktop platforms before
  switching Android/Desktop startup or deleting Rust Core.

## Known request-spawned work in the retired implementation

These are reasons the old binary cannot be fenced by an HTTP listener alone;
they are not todos to fix inside Rust:

| Source | Work started | Cutover concern |
| --- | --- | --- |
| `lib.rs::start_turn_inner` | legacy/native chat turn worker | provider/tool calls can outlive or have remote side effects. |
| `background_turn.rs` | replay/event fan-out and SSE event iterators | detached observers and event streams can survive a listener shutdown. |
| `control.rs` | local/background command dispatch, task logging, queued Cloud sync | network work and ACP commands may remain ambiguous. |
| `remote_client.rs` | remote control client lane | a remote host command may already have executed. |
| `scheduled_tasks.rs` | one task execution per tick | durable claims and externally dispatched work need recovery. |
| `image_generation.rs` | one provider job per admitted image request | paid/remote generation or charge may remain unknown. |
| `delegation.rs` | ACP delegation event drain | external ACP descendants are separate from the delegating request. |
| `external_acp.rs`, `android_acp.rs`, `acp_mcp.rs` | ACP readers/writers, per-worker MCP servers, RPC/message tasks | direct child exit does not prove OS descendants or Android Runtime children stopped. |
| `agent_relay.rs` | model relay listener and SSE conversion workers | upstream/provider streams and conversion tasks are external side effects. |

Only after Kotlin parity and a real platform fence are proven may the final
separate commit delete Rust Core, the native bindings, and their CI/build
wiring.
