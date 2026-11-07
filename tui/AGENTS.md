# tui — Node.js terminal client

- Node.js 22+ and Ink/React. Run from the repository root with `npm --prefix tui ci` and `node tui/src/main.js` after building the `messenger-core-server` binary.
- `src/main.js` owns terminal startup and shutdown. `src/core.js` starts the sidecar with piped stdio, authenticates to its loopback API, and parses chat SSE. Keep the bearer token off command lines and out of logs.
- `src/chat.js` manages chat, model selection, and history through `/api/v1`. `src/app.js` owns the composer, keyboard shortcuts, and command picker. The Rust Core owns database access, provider protocols, and agent execution.
- The dedicated store defaults to `~/.messenger/tui.db`. Use `--db PATH` for a test or separate profile, and `--core PATH` to select a non-default build. The TUI reads the Core version from `/meta`.
- Retain the draft until Core accepts a turn. Keep chat errors visible in both the transcript and the status line, and never silently retry. Close the chat stream when exiting so Core can cancel the turn. For local `ToolCallRequested` events, post an error result to release the pending call.
- Test terminal input and resize behavior in a PTY. Use a temporary database and a local fake provider to verify streaming, cancellation, failures, and session reload without a real API key.
