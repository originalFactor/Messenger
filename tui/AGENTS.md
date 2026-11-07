# tui — Python terminal client

- Use Python 3.11+, Textual and Poetry 2. Run `poetry -C tui install` to install the dependencies from `poetry.lock` into `.venv`.
- Run `poetry -C tui run python workflow.py dev` to build and launch. `build` builds the debug Core. `test` runs the Rust API suite, the Python-to-Core integration test, and Textual's headless keyboard test.
- The workflow uses Cargo's reported executable path and the regular build cache. `release` builds the optimized Core, tests it, and stages the locked Python runtime dependencies in an isolated Poetry environment. It creates a host tarball in `dist/` using the Core version and supports `--out PATH`.
- `messenger_tui/__main__.py` owns startup and shutdown. `core.py` owns the stdio handshake and authenticated REST/SSE transport. Keep the loopback bearer token off command lines and out of logs.
- `chat.py` owns turn state, persistence calls and history. `app.py` and `screens.py` own the Textual layout, composer, and dialogs. Slash completion shares the command catalog with the command palette.
- The dedicated store defaults to `~/.messenger/tui.db`. Use `--db PATH` for a test or separate profile and `--core PATH` for a custom executable. The TUI reads the product version from `/meta`.
- Retain the draft until Core accepts the turn. Show chat failures in the transcript and status line. Never silently retry. Close active chat streams on exit. Return an error result for local tool requests to release the pending call.
- Verify terminal input in a PTY and run chat integration against a temporary database and mock provider.
