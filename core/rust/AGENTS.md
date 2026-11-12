# core — Rust Agent Core and local API

`core/rust` is a Cargo workspace (resolver 2, release LTO + strip) with 9 crates. `core/bindings` and `core/bindings-desktop` build the native library and generate the lifecycle-only UniFFI surface used by Android/Desktop. Android, Desktop, and the Python TUI use the loopback `/api/v1` REST/SSE service in `messenger-http`.

## Crates

- `messenger-core` — agent loop (`run_chat_turn`), parts codec, context math, title helpers and conversation policy.
- `messenger-llm` — four wire protocols (`chat`/`responses`/`messages`/`interactions`), streaming parser, tool-call accumulation, usage stashing and request builder.
- `messenger-tools` — built-in terminal/workspace declarations and per-tool policy.
- `messenger-store` — SQLite schema v6 (model metadata and overrides), typed CRUD, store events, sync/kv bookkeeping and legacy Room import.
- `messenger-sync` — paged delta sync, cloud auth/session handling, avatar caching, market/card APIs and model discovery.
- `messenger-mcp` — JSON-RPC MCP client, configuration codec, pluggable stdio / SSE+HTTP transports and tool lifecycle. Kotlin also retains its platform MCP manager; do not assume this crate replaces the native UI inventory.
- `messenger-remote` — cross-device project routing: `RemoteHub` holds the per-device cloud relay SSE connection (`/api/remote/*`), tracks which devices are online, forwards workspace tool calls to the project's owner and validates/executes inbound ones. Contract: `crates/messenger-http/docs/REMOTE.md`.
- `messenger-http` — axum loopback REST/SSE server covering CRUD, pagination, chat turns, tool results, cloud sync and cancellation. Its `messenger-core-server` binary is the TUI’s lifecycle bridge. It opens a dedicated SQLite store under an advisory lock, ensures the default Agent exists before serving, writes the loopback URL and bearer token to the child’s stdout pipe, and shuts down on stdin EOF.
- `messenger-ffi` — lifecycle-only `CoreHandle.open`, `start_http_server`, `HttpServerHandle.local_addr/token/stop` and `CoreError`.

The Rust core does not contain a Document Engine, Markdown parser, LaTeX renderer, syntax highlighter, browser boundary or TUI crate. Android/Desktop render Markdown, code and LaTeX in Kotlin with `cc.ptoe:llm-typewriter`.

## Local API boundary

- `messenger-http` owns request validation, default-Agent protection, model-required checks and cloud error mapping.
- `/cloud/builtin-models/sync` provisions Messenger Cloud AI from the saved account before fetching its catalog. Clearing the Cloud session removes that provider and its models. Public Provider/model CRUD (including enabled and reparenting) rejects the built-in catalog with 409 `builtin_catalog_read_only`; internal provisioning/sync writes remain allowed. Manual native Sync uses this endpoint, importing authoritative Messenger Extended Models Protocol `/v1/models` metadata and rates without local overrides, stale-rate retention or models.dev enrichment.
- `/cloud/conversations/push` uploads standalone TUI conversations without applying Cloud data. It stores the account/server binding and last uploaded digest/version, checks for newer Cloud changes before updates, and maps local-only Agent IDs to the Cloud default. Keep provider credentials and unrelated entities out of these uploads.
- Model rows persist capability metadata separately from explicit overrides. Provider discovery reads Messenger extensions from `/models`. TUI discovery opts out of models.dev; Python `tui/messenger_tui/model_metadata.py` fills missing capabilities from pi-catalog. Built-in models persist only Cloud-supplied capabilities.
- Tool policy stays in Rust. Kotlin and the TUI execute platform tools and post results to `/turns/tools/{callId}/result`. The TUI's `/tools` command persists conversation overrides; its local executor uses the trusted project's workspace.
- Dropping the chat SSE cancels the turn token, wakes a pending tool host and finalizes partial rows.
- **`ServerHandle::stop` must close the endless streams**: axum's graceful shutdown resolves only after every in-flight response ends, and `/events` never ends on its own — so `ServerHandle`'s `CancellationToken` is handed to `ApiState`, whose `/events` handler wraps the feed in `ShutdownStream`. Without that, `stop()` never returns and the desktop app freezes on window close. Any future long-lived (SSE/websocket) route needs the same treatment.
- **`ApiState` is `Clone`, so its cleanup cannot be in `Drop`**: axum clones the state per request, and the first dropped clone used to `unsubscribe` the store listener, silently killing the change feed for every later subscriber. The registration lives in `StoreFeed`, held through an `Arc`, so it ends with the last clone.
- Contracts: `crates/messenger-http/openapi.yaml` and `crates/messenger-http/docs/SSE.md`.

## Remote projects

- A project's `deviceId` names the device that owns its workspace. Its conversations' workspace tools (terminal/glob/grep/read/edit/create) execute on that device; only tool calls cross devices, the turn itself stays on the initiator.
- A remote-owned project is *unavailable* while its owner (or this device) is not connected to the relay: `POST /conversations/{id}/messages` returns 409 `project_unavailable` before any row changes. Project responses carry `owner` + `available`.
- The owner's `messenger-remote` hub validates inbound calls (owned project, built-in workspace tool, writable conversation for `edit`/`create`) and hands them to the local platform client through the `/events` feed (`remoteTool`/`remoteToolCancelled`); the client posts results to `/turns/tools/{callId}/result`.
- The cloud server (`server/`, `lib/remote-hub.ts` + `/api/remote/*`) is the relay and persists `deviceId` on project documents. Its online set is in-process memory, so the server must run as a single instance; signed out or unreachable relay = hub idles/backs off and remote-owned projects stay unavailable.

## Versioning

`messenger-core` reads the repository-root `VERSION` and exposes the semantic name plus git commit count. The TUI packaging workflow reads the version from `messenger-core-server --version`. Rust never uses `CARGO_PKG_VERSION` as the product version. Unlike Gradle, the current build script accepts non-empty version text without a semantic-version check and warns/falls back to `0.0.0` when VERSION is missing/empty; its commit count falls back to 0 without git. TUI release packaging validates the reported semantic version; release tags must still match root VERSION.

## Native bindings

- `:core-bindings` (`core/bindings`) builds host `messenger-ffi` + `uniffi-bindgen` with profile `bindgen` for lifecycle binding generation. Its per-build-type cargo-ndk tasks package the selected Android ABIs using Rust `--release` even for an Android debug variant.
- `:core-bindings-desktop` (`core/bindings-desktop`) generates the same surface and packages the host cdylib from profile `bindgen` as a JNA resource. This profile is shipped on Desktop: `strip = "none"`, LTO off, 16 codegen units. Keep metadata symbols intact for UniFFI on ELF; it cannot generate bindings from a stripped release library.
- Both binding modules share the Cargo workspace, with Gradle task ordering to avoid concurrent host-output mutation. Maintain their generated-source and native-resource wiring when changing build tasks.

## Testing / CI

- `cargo test --workspace` is the workspace verification; `cargo test -p messenger-http` runs the API contract suite.
- The current GitHub workflows compile/package native clients; they do not run `cargo test` or the TUI test workflow automatically. Run the relevant tests locally.
- Native client builds use cargo-ndk for Android and host Cargo for Desktop. There is no Web/Wasm build.

## ACP and device control

- The stable ACP v1 Agent and Client adapters live in `messenger-http`. Desktop exposes the local bearer-protected ACP socket through the `messenger-core-server acp --connect` stdio adapter. The product-facing commands reuse Core turn admission and persisted conversation rows.
- Cloud device control only accepts sessions in workspaces owned by the host. Local Messenger commands still support projectless sessions; creating third-party ACP sessions requires a local-owned project. The catalog includes each session’s project ID and update time, including device-local ACP sessions. Device-owned Messenger turns and native chat share one per-conversation reservation. Send, retry, and regenerate check model and project availability before mutation. Third-party sessions keep a device-local transcript and resume by advertised ACP capability.
- `messenger-remote` carries control requests over the existing account relay. Device opt-in is scoped to the cloud server and account. Disconnect or revocation cancels the owning control request.
- Protocol and setup: `crates/messenger-http/docs/ACP.md`, `REMOTE.md`, `SSE.md`, and `openapi.yaml`.

- **Model rates (schema v7)**: `StoredModel.cache_write_rate` persists in nullable `models.cacheWriteRate`. Existing stores migrate with NULL (unknown); explicit zero remains free. Carry the fourth leg through Messenger `/models` DTOs, discovery, built-in Cloud sync and Cloud model document conversion. Kotlin maps it to `ChatModel.cacheWriteRate`.

- **ACP configuration and native chats**: control settings profiles have optional `description` and `enabled` (legacy default true). GET seeds disabled Codex / Claude Code / OpenCode stdio templates once using `acp_templates_seeded`; deleting a template is permanent. Local ACP transcripts project into `/conversations` and project lists without SQLite/Cloud sync rows. Rename/delete are supported; ACP agent/workspace bindings stay fixed, and deletion during a turn returns `turn_in_progress`. Native chat routes ACP IDs through the control command stream. `store {kind: acp|mcp}` loads authenticated Cloud `/api/integrations/catalog` through `SyncEngine::with_session`, including terminal session expiry. Remote control still requires host-owned workspaces and the account-scoped opt-in.

- **ACP session settings**: `sessionSettings` / `setSessionConfig` reserve an ACP session, read new/load/resume options and mode notifications, and forward Agent-confirmed grouped select/boolean config changes or legacy modes. Never infer Permissions or commit a requested value before the Agent confirms it. Cancellation/timeout discards ambiguous workers. Live and replayed tool/thought events are stored on local transcript rows; native history keeps full rows, while remote relay history remains bounded.

- **ACP composer protocol**: local control `prompt.content` carries ACP text/image/embedded-resource blocks. `steer` forwards `_session/steering` while a prompt is active, uses `promptRequired` on idle, and persists injected input in the owning turn. Session snapshots carry available commands and context usage. Never start an unowned fallback turn or silently retry a rejected steer.

- **ACP MCP setup**: the local platform catalog includes enabled MCP configurations in memory. Native Core waits for the first explicit MCP catalog before starting or restoring an ACP worker; device-owned Messenger turns with tools also wait for the initial `toolsReady` publication after enabled server connection attempts finish. Cancellation, shutdown and a timeout (10 seconds for configuration, 75 seconds for tool discovery) end the wait, while model-required remains immediate. Standalone Core starts with an empty ready catalog and does not wait for a native client. New, resumed and loaded ACP sessions receive enabled configurations through ACP `mcpServers`; unsupported SSE produces an error. Running workers retain their session configuration until restarted. The catalog may contain credentials and must not be persisted or exposed to remote control.
- **Agent delegation**: device-local `mcp_agent_delegation_enabled` defaults off; `/mcp/agent-delegation` controls the Core-owned `messenger_agents` tool for Messenger turns. Calls create a separate conversation, propagate cancellation and workspace mode, and child Messenger turns cannot delegate further. ACP delegates require a local project and writable mode because ACP agents control their own tools; permission prompts cannot be approved by the caller and are cancelled and reported.
- **ACP agent delegation**: when enabled at worker startup, Core injects a per-worker loopback MCP server with a random capability URL, using SSE or HTTP as advertised by the ACP agent. It accepts calls only during the owning ACP turn, cancels with the worker/turn, and checks the setting on every call. Agents without either transport fail session setup with a visible error. Delegated ACP sessions never receive this server, including after restoration. Disabling blocks subsequent calls; enabling requires a new or restarted worker.
- **Agent model relay**: `/control/agent-relay` starts/stops a separate loopback server with a per-start token; enabled models are addressed as `messenger/<local-model-id>` to disambiguate providers, and only same-format requests are forwarded. Built-in ACP profiles opt into per-process `providerTakeover` (default off); Codex uses Responses, Claude Code Messages and OpenCode Chat, with a matching enabled model required. Relay credentials go to the spawned process; provider credentials stay in Core, and user configuration files are not rewritten. Cross-protocol conversion is not yet implemented. OpenCode takeover probes the configured executable with --version through the platform process host and injects the appropriate V1 or V2 provider schema; failed or unsupported probes prevent startup. Existing workers retain their startup configuration.
