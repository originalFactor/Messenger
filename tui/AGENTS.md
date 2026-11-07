# tui — Node.js terminal client

- Node.js 22+ and Ink/React. Run from the repository root with `pnpm --prefix tui run dev`. The local workflow in `scripts/workflow.js` uses `pnpm-lock.yaml` and Cargo's reported executable path. `build` uses the debug profile. `test` runs the Rust API suite and the Node-to-Core integration test.
- Run the workflow with pnpm 12. It installs from the frozen pnpm lockfile and uses a hoisted layout so the production bundle can be moved after extraction.
- `pnpm --prefix tui run release` builds the optimized Core for the current host, tests it, and bundles the launcher, production dependencies, and Core in a versioned tarball. It reads the shared version from the compiled Core. Release output defaults to the ignored `dist/` directory and supports `-- --out PATH`. This workflow is local.
- `src/main.js` owns terminal startup and shutdown. `src/core.js` starts the sidecar with piped stdio, authenticates to its loopback API, and parses chat SSE. Keep the bearer token off command lines and out of logs.
- `src/chat.js` manages chat, model selection, and history through `/api/v1`. `src/app.js` owns the composer, keyboard shortcuts, and command picker. The Rust Core owns database access, provider protocols, and agent execution.
- The dedicated store defaults to `~/.messenger/tui.db`. Use `--db PATH` for a test or separate profile, and `--core PATH` to select a non-default build. The TUI reads the Core version from `/meta`.
- Retain the draft until Core accepts a turn. Keep chat errors visible in both the transcript and the status line, and never silently retry. Close the chat stream when exiting so Core can cancel the turn. For local `ToolCallRequested` events, post an error result to release the pending call.
- Test terminal input and resize behavior in a PTY. Use a temporary database and a local fake provider to verify streaming, cancellation, failures, and session reload without a real API key.
