# core — Rust Agent Core, FFI/Wasm boundaries, native TUI

`core/rust` is a Cargo workspace (resolver 2, release LTO + strip) with 12 crates; `core/bindings` is the Gradle `:core-bindings` Android library that builds and wraps it via UniFFI. The repo is mid-migration to the TARGET.md architecture (Rust Agent Core + platform renderers); M0–M6 are delivered — the old stack stays buildable until each switch point.

## Crates

- `messenger-core` — agent loop (`run_chat_turn`), parts codec, context math (estimation, 80% summarization, orphan-tool trimming), title helpers, `version` module. `AgentEvent::UsageRecorded` reports the WHOLE TURN summed over rounds (each tool round re-sends a grown context and is billed); `record_usage` (the `contextTokens` the 80% gate reads) stores the FINAL round only. `cached_tokens` comes from `prompt_tokens_details.cached_tokens`.
- `messenger-llm` — streaming parser (think wrapping, tool-call accumulation, usage stashing), request builder (reasoning three-state, multipart images, tool specs), reqwest+rustls client, `ChatEventStream` with the mandatory has-finished error sentinel.
- `messenger-tools` — terminal/workspace declarations and `resolve_request_tools` (per-tool config, read-only/writable mode, workspace gate `workspace_required`). Arguments are never pre-screened; the sandbox confines execution.
- `messenger-store` — SQLite schema v2 (Room column names verbatim + `projects` table), typed CRUD + StoreEvent, sync_meta/kv tables, one-shot legacy import (WAL-aware, idempotent marker).
- `messenger-sync` — cloud sync engine port (paged delta pull/push, cursor protection, builtin guards, avatar ETag caching, market APIs, card redemption, builtin model auto-sync). `projects` are applied BEFORE conversations.
- `messenger-document` / `messenger-markdown` / `messenger-highlight` — Document AST (`Block` types with stable `BlockId`s + `DocumentDiff` streaming events), incremental streaming Markdown parser (pipe tables held one line of lookahead, GFM task lists, quotes with inlines, 20–50 ms batching in `StreamingSession`), syntect highlighting (`default-fancy`, no oniguruma; failures degrade to `"[]"`).
- `messenger-mcp` — JSON-RPC 2.0 client over stdio and SSE/HTTP transports.
- `messenger-ffi` / `messenger-wasm` — the two boundaries (below).
- `messenger-tui` — native terminal client (below).

## Boundaries

- `messenger-ffi` (UniFFI, proc-macro exports, no UDL → Kotlin package `cc.ptoe.messenger.core`) and `messenger-wasm` (wasm-bindgen, `#![cfg(target_arch = "wasm32")]`) expose the SAME `CoreHandle`/`DocumentHandle` surface (JSON in/out). A new method must be added to BOTH crates, to `CoreBridge`, and to `AndroidCoreBridge` + `WasmCoreBridge` in one change.
- `:core-bindings` runs three Exec tasks in `preBuild` (cargoBuildHost → generateUniFFIBindings → buildRustAndroid via cargo-ndk, 3 ABIs); all track the WHOLE workspace as inputs so sibling-crate edits rebuild. Generated code/native libs stay under `build/`, never committed. Kotlin-side regeneration is never done by hand.
- **Store-model serde contract**: the Kotlin bridge encodes with `encodeDefaults = false`, so DTO fields equal to their Kotlin default are omitted from upsert JSON. Every plain scalar column in `StoredConversation`/`StoredAgent`/`StoredModel` with a Kotlin-side default needs `#[serde(default)]` or upserts fail with `missing field`. `upsert_json_with_default_valued_fields_omitted_parses` pins this.
- Wasm store: `messenger-store` compiles SQLite to wasm (`sqlite-wasm-rs`), keeps the DB in memory, mirrors to OPFS after each write (`Store::snapshot` → `wasm::stage_snapshot`); await `prepare_store(name)` BEFORE `Store::open`; the store path on web is a logical database name.

## Versioning

`messenger-core`'s `build.rs` reads the repo-root `VERSION` and injects `MESSENGER_VERSION`/`MESSENGER_VERSION_CODE` (commit count); Rust reads ONLY `messenger_core::version::{NAME, CODE, full()}` — never `CARGO_PKG_VERSION` (the internal crate version). Rust-only checkouts degrade to `0.0.0` + a cargo warning; the Kotlin/Gradle side fails loudly on a missing/invalid `VERSION`.

## TUI (`crates/messenger-tui`, bin + lib)

Native Rust terminal client: `Rust Core → Document Model → Terminal Renderer → ANSI/VT`. Links the core crates directly (no UniFFI, no Kotlin). The lib exists so `tests/headless.rs` drives the real state machine and asserts on `ui::compose`'s frames.

- **Rendering**: ratatui with codex's scrollback model — `Viewport::Inline` on the MAIN screen (never the alternate screen), finalized transcript handed to `Terminal::insert_before` so history lives in the terminal's own scrollback. Load-bearing: the `scrolling-regions` feature must stay enabled; the viewport height is FIXED for the life of the process (rebuilding it scrolls inserted rows back off); the diff buffer is reset after each insert (or rows repaint). No `ratatui-textarea` (no public wrapped-height API).
- **Enter/leave contract**: `Screen::start` writes raw mode + bracketed paste + show and builds the Terminal once; `Screen::stop` writes its own leave sequence and never emits `Clear`/`LeaveAlternateScreen` (`ratatui::restore()` is deliberately not used — the conversation must stay on screen after exit). Byte assertions in `screen.rs`'s tests pin the "no clear / no 1049" properties. The caret is offset by the viewport origin (`absolute_cursor`).
- **Transcript** (`transcript.rs`): grouped into TURNS — one `Block::AgentTurn` holds all of a turn's rows; empty sending placeholders are skipped; the live streaming tail is appended by `ui.rs` and never reaches history. Turn statistics (`turn_stats.rs`, keyed by final assistant message id, stored in the store's kv table `tui_turn_stats`, newest 200) show agent/model/effort, tokens, duration, and `cost_units` — quota units using the server's formula, never dollars; only builtin-provider models carry rates.
- **Chrome**: bottom-pinned input line (wraps up to `MAX_EDITOR_ROWS`), a status rail of chips (spinner, agent, model+effort, project+git branch, context, cost, status, and the PINNED read-only/writable badge that is never dropped; other chips degrade whole-first), and a closing rule. Git branch is read by parsing `<workspace>/.git/HEAD` (no subprocess, no git lib). Boxes are hand-laid (`box_lines` returns exactly its row budget); space priority: editor > footer > context line > palette > transcript.
- **One surface + popups (pi-style)**: no view enum; everything else is a `Popup::{Commands, Select, Form, Confirm, Help}` on a stack; the topmost popup consumes input or the chat editor does. `SLASH_COMMANDS` is plain data (`commands.rs`); the `/` palette's buffer is a filter and the cursor is the selection (they never write to each other; Tab completes to the bare command name; the palette lives until Esc). `Popup::Select` + `SelectPurpose` backs every picker (`e`/`n`/`d` edit/new/delete).
- **Agentic entry**: `App::bootstrap_session` creates/finds the CWD project (canonicalized; `store_ops::strip_verbatim` normalizes Windows `\\?\` paths) and opens a fresh conversation there with the current/default Agent. `@` mentions expand to file CONTENTS at send time (bounded 64 KiB, UTF-8, inside the workspace; fence-length escaped); Ctrl-V clipboard paste prefers images (1568 px, PNG, `data:` URI + mandatory local copy under `~/.messenger/tui/chat_images/`).
- **Tools**: `NativeToolHost` — `terminal` → `shell.rs` (PowerShell UTF-8 / `/bin/sh`, 60 s timeout, killed on timeout AND turn cancellation), five workspace tools → `workspace.rs` (grep is walkdir+regex in-process — no bundled `rg` here), else MCP by name. Cwd = the resolved turn's project directory.
- **Async**: the UI loop runs OUTSIDE any runtime context — background work must spawn via `engine.spawner()` (a bare `tokio::spawn` from UI code panics).
- **Keys**: `Ctrl+C` quits (always restores the terminal), `Esc` closes one popup layer or cancels the turn, `Enter` sends, `Alt+Enter`/`Ctrl+J` newline, `Ctrl+S` submit form, `Ctrl+T` think blocks, `Ctrl+O` tool details, `Ctrl+V` paste, `?` help, `/` palette, `@` file picker. Bracketed paste is ONE insertion (long pastes collapse to a `[paste #N …]` marker restored on send); on Windows pastes arrive as key events.
- **CLI/config**: `messenger-tui [--store <path>] [--workspace <dir>] [--config <path>] [--import-desktop [<dir>]]`; defaults `~/.messenger/tui/{store.db,settings.toml}`. `settings.toml` keys: `workspace_dir`, `theme`, `show_think`, `show_tool_details`, `auto_scroll`, `show_banner`. `--import-desktop` runs the marker-guarded idempotent legacy Room import (accepts both Room file names).
- **Deliberate degradations**: math renders as LaTeX source in a box; `context_window` stays 0 (unlimited) unless the provider's `GET /models` reports it.

## Testing / CI

- `cargo test --workspace` in `core/rust` is the verification (there is no `cargo test` step in CI). `build-tui.yml` only builds the binary (Linux + Windows) and uploads it; Gradle has no TUI task.
- Web builds need `rustup target add wasm32-unknown-unknown` + `cargo install wasm-bindgen-cli` at the `Cargo.lock` version (see `webApp/AGENTS.md`).
