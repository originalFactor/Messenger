# core ? Rust Agent Core and local API

`core/rust` is a Cargo workspace (resolver 2, release LTO + strip) with 8 crates. `core/bindings` and `core/bindings-desktop` build the native library and generate the lifecycle-only UniFFI surface used by Android/Desktop. Android and Desktop business calls use the loopback `/api/v1` REST/SSE service in `messenger-http`.

## Crates

- `messenger-core` ? agent loop (`run_chat_turn`), parts codec, context math, title helpers and conversation policy.
- `messenger-llm` ? four wire protocols (`chat`/`responses`/`messages`/`interactions`), streaming parser, tool-call accumulation, usage stashing and request builder.
- `messenger-tools` ? built-in terminal/workspace declarations and per-tool policy.
- `messenger-store` ? SQLite schema v5 (`projects.deviceId`), typed CRUD, store events, sync/kv bookkeeping and legacy Room import.
- `messenger-sync` ? paged delta sync, cloud auth/session handling, avatar caching, market/card APIs and model discovery.
- `messenger-remote` ? cross-device project routing: `RemoteHub` holds the per-device cloud relay SSE connection (`/api/remote/*`), tracks which devices are online, forwards workspace tool calls to the project's owner and validates/executes inbound ones. Contract: `crates/messenger-http/docs/REMOTE.md`.
- `messenger-http` ? axum loopback REST/SSE server covering CRUD, pagination, chat turns, tool results, cloud sync and cancellation.
- `messenger-ffi` ? lifecycle-only `CoreHandle.open`, `start_http_server`, `HttpServerHandle.local_addr/token/stop` and `CoreError`.

The Rust core does not contain a Document Engine, Markdown parser, LaTeX renderer, syntax highlighter, browser boundary or TUI crate. Android/Desktop render Markdown, code and LaTeX in Kotlin with `cc.ptoe:llm-typewriter`.

## Local API boundary

- `messenger-http` owns request validation, default-Agent protection, model-required checks and cloud error mapping.
- Tool policy stays in Rust; Kotlin executes platform tools and posts results to `/turns/tools/{callId}/result`.
- Dropping the chat SSE cancels the turn token, wakes a pending tool host and finalizes partial rows.
- Contracts: `crates/messenger-http/openapi.yaml` and `crates/messenger-http/docs/SSE.md`.

## Remote projects

- A project's `deviceId` names the device that owns its workspace. Its conversations' workspace tools (terminal/glob/grep/read/edit/create) execute on that device; only tool calls cross devices, the turn itself stays on the initiator.
- A remote-owned project is *unavailable* while its owner (or this device) is not connected to the relay: `POST /conversations/{id}/messages` returns 409 `project_unavailable` before any row changes. Project responses carry `owner` + `available`.
- The owner's `messenger-remote` hub validates inbound calls (owned project, built-in workspace tool, writable conversation for `edit`/`create`) and hands them to the local platform client through the `/events` feed (`remoteTool`/`remoteToolCancelled`); the client posts results to `/turns/tools/{callId}/result`.
- The cloud server (`server/`) must implement the `/api/remote/*` relay and persist `deviceId` on project documents; until it does, the hub idles and remote-owned projects stay unavailable.

## Versioning

`messenger-core` reads the repository-root `VERSION` and exposes the semantic name plus git commit count. Rust never uses `CARGO_PKG_VERSION` as the product version.

## Testing / CI

- `cargo test --workspace` is the workspace verification; `cargo test -p messenger-http` runs the API contract suite.
- Native client builds use cargo-ndk for Android and host Cargo for Desktop. There is no Web/Wasm build.
