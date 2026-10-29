# core �� Rust Agent Core and local API

`core/rust` is a Cargo workspace (resolver 2, release LTO + strip) with 11 crates. `core/bindings` and `core/bindings-desktop` build the native library and generate the **lifecycle-only** UniFFI surface used by Android/Desktop. Android and Desktop business calls use the loopback `/api/v1` REST/SSE service in `messenger-http`; Web keeps the existing wasm-bindgen boundary for this phase.

## Crates

- `messenger-core` �� agent loop (`run_chat_turn`), parts codec, context math (80% summarization, orphan-tool trimming), title helpers, conversation policy and `version`. `AgentEvent::UsageRecorded` reports the whole turn summed over rounds; `record_usage` stores the final round only. `cached_tokens` comes from `prompt_tokens_details.cached_tokens`.
- `messenger-llm` �� four wire protocols (`chat`/`responses`/`messages`/`interactions`), streaming parser, tool-call accumulation, usage stashing, request builder and reqwest+rustls client. `ChatEventStream` enforces a `has_finished` sentinel.
- `messenger-tools` �� built-in terminal/workspace declarations and per-tool config, writable filtering and workspace gating. Arguments are not pre-screened; execution is confined by the platform sandbox.
- `messenger-store` �� SQLite schema v4, typed CRUD, `StoreEvent`, sync/kv bookkeeping and one-shot legacy Room import. The default-Agent invariant is enforced in the normal store mutation path; `clear_agents_for_reinit` is the documented reset escape hatch.
- `messenger-sync` �� paged delta sync, cursor protection, builtin guards, avatar ETag caching, market/card APIs and builtin-model sync. `projects` apply before conversations. Pull acknowledgement always uses the STORED cursor. `replace_local` re-reads from zero. An authenticated `401` becomes `CloudError::SessionExpired`, drops the cookie/user and is never retried; password-change/delete-account keep their credential-specific `401` behavior.
- `messenger-http` �� axum server. `/api/v1` covers CRUD/pagination, chat send/retry/regenerate/continue/cancel, core events SSE, cloud auth/sync/market/cards/models, legacy import and local reset. A fresh bearer token is generated per `ServerHandle`; non-loopback binds are rejected. Tracks in-flight turns and remote tool calls.
- `messenger-ffi` �� only `CoreHandle.open`, `start_http_server`, `HttpServerHandle.local_addr/token/stop` and `CoreError`. Do not add business methods here.
- `messenger-wasm` �� browser business boundary, still mirroring the Web surface. Native business FFI is not a parallel requirement anymore.
- `messenger-document` / `messenger-markdown` / `messenger-highlight` �� transitional Web Markdown/document support only. Android/Desktop no longer link these through FFI and render with `cc.ptoe:llm-typewriter`.
- `messenger-mcp` �� JSON-RPC 2.0 client for stdio and SSE/HTTP MCP transports.

## Local API boundary

- `messenger-http` owns request validation, default-Agent protection, model-required checks and cloud error mapping.
- Tool policy stays in Rust (`tools_enabled`, `tools_config`, writable/workspace rules). A turn emits `ToolCallStarted` then `ToolCallRequested`; the Kotlin client executes through the runtime/MCP host and posts `{output,isError}` to `/turns/tools/{callId}/result`.
- Dropping the chat SSE cancels the turn token, wakes a pending tool host and finalizes partial rows.
- Contracts: `crates/messenger-http/openapi.yaml` and `crates/messenger-http/docs/SSE.md`. Update them with every route/event change.
- `core/bindings(+-desktop)` runs `cargoBuildHost` �� `generateUniFFIBindings`; Android also runs cargo-ndk once per build type into its own `rustJniLibs/<buildType>` directory. Generated code and libraries stay under `build/`.

## Versioning

`messenger-core`'s `build.rs` reads the repo-root `VERSION` and injects `MESSENGER_VERSION`/`MESSENGER_VERSION_CODE` (commit count). Rust reads only `messenger_core::version::{NAME, CODE, full()}`, never `CARGO_PKG_VERSION`. Rust-only checkouts degrade to `0.0.0` with a warning; Gradle fails loudly on an invalid VERSION.

## Rendering

Android/Desktop Markdown, syntax-highlighted code and LaTeX are Kotlin-rendered by `llm-typewriter`. `ChatBlock.RawMarkdown` is the boundary for raw assistant Markdown in tool-group layouts. Do not add a native grammar or math renderer back to those clients. Web's `ChatMarkdownText.wasmJs.kt` remains the intentional fallback until it can consume the same renderer.

## Testing / CI

- `cargo test --workspace` is the workspace verification; `cargo test -p messenger-http` runs the API contract suite (auth, loopback enforcement, CRUD pagination, default Agent, model-required abort, SSE chat, tool request/result, cancellation and cloud session expiry).
- `:shared:allTests` includes the Desktop HTTP integration test. Android/Wear/Runtime/Desktop debug compile checks are run through `gradlew-cli`.
- Web builds need `rustup target add wasm32-unknown-unknown` + `cargo install wasm-bindgen-cli` at the `Cargo.lock` version. CI has Android, Wear, Desktop, and Web workflows; the TUI workflow and crate were removed.
- Paged message queries use chronological `(timestamp,rowid)` order so equal timestamps preserve insertion order.
