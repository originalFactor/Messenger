# Messenger terminal client

A keyboard-first chat client backed by the local Rust Core. Bring a provider
key, choose a model, and keep conversations in a trusted project or in standalone
chat. The Codex-inspired interface uses a neutral palette, a borderless `›`
prompt and flat menus. The prompt grows for multiline drafts; model/tool context
and short key hints stay beneath it. User turns start with `›`, assistant turns
with `•`. Status appears only for ongoing work, notices or errors.

## Run

Requires Python 3.11+, Poetry 2, Rust, and a UTF-8 interactive terminal.
Run from the repository root:

```sh
poetry -C tui install
poetry -C tui run python workflow.py dev
```

Use `/providers` to add a provider, then `/models` to choose a model. Ctrl+P
searches all commands. `/connect` offers a guided provider/model setup.
`/project` opens a trusted workspace; `/tools` enables tools for its conversation.
Local tools run with your user's permissions. Inspection mode omits edit/create
and guides terminal use; it is not an operating-system sandbox. File search
requires `rg` on PATH.

## Keyboard

| Where | Keys | Action |
|---|---|---|
| Anywhere | F1 | Open keyboard guide; Esc returns to the previous control |
| Anywhere | Ctrl+C | Exit |
| Chat | Enter | Send |
| Chat | Ctrl+O, Shift+Enter | New line; Ctrl+O is the portable fallback |
| Chat | Ctrl+P | Search commands without losing the draft |
| Chat | F2 / F3 | New session / saved sessions |
| Chat | Ctrl+X, then N/L/M/A/Q | New / sessions / models / agents / quit |
| Chat | PgUp / PgDn | Scroll history; PgUp at the top loads older messages |
| Chat | Ctrl+Home / Ctrl+End | First / latest loaded message |
| Chat | Esc | Dismiss suggestions, or cancel the active turn |
| Suggestions | Up / Down, Tab, Enter | Select, complete, run |
| Menus | Type, Up / Down, PgUp / PgDn | Filter and navigate while keeping search focused |
| Menus | Ctrl+Home / Ctrl+End | First / last result |
| Menus | Enter / Esc | Open / back |
| Forms | Tab / Shift+Tab | Next / previous control; scrolls focused fields into view |
| Provider/model forms | Enter | Next control |
| Forms | F4 | Primary action such as Save or Add; disabled during pending work |
| Model manager | F5 | Fetch or sync models |
| `/connect` | Enter / Shift+Tab | Next / previous step; preserves entered values |

Delete confirmation starts on **Cancel**. Enter cancels unless you move to
the delete button. F4 never confirms deletion. Esc closes an idle dialog;
pending saves must finish before the dialog closes.

## Appearance and terminal support

Use at least 40 columns × 10 rows; 80×24 or larger is recommended. Below the
minimum, a resize message replaces the conversation without clearing its draft.
Narrow dialogs stack their actions and scroll. `/theme` switches dark/light
appearance for the current run. `NO_COLOR=1` uses monochrome rendering with
visible focus and textual error markers.

Cursor addressing and a TTY are required. `TERM=dumb` exits before starting
Core with a plain explanation. `--help` and `--version` work outside fullscreen.
Truecolor and mouse input are optional. Terminals that intercept function keys
can use slash commands and Tab/Enter instead. CJK, emoji and combining characters
remain unchanged; terminal/font support determines their appearance. Arabic and
mixed text stay in logical order; Textual does not provide application-level
BiDi shaping. Screen-reader support is limited by the terminal UI.

## Profile and troubleshooting

The profile defaults to `~/.messenger/tui.db` and includes provider credentials.
Keep it separate from Desktop's active database. For a separate profile or
startup workspace:

```sh
poetry -C tui run python workflow.py dev -- --db ./test-chat.db --workspace /path/to/project
```

The development workflow resolves relative profile paths under `tui/`.
`--core PATH` selects a custom Core executable. Errors stay visible in status
and conversation output; retry deliberately with `/retry`. If a terminal was
interrupted outside normal shutdown, use `reset` or `stty sane` in the shell.

## Development and verification

```sh
poetry -C tui run python workflow.py build
poetry -C tui run python workflow.py test
poetry -C tui run python workflow.py release
```

The test workflow checks the Rust API, Python/Core integration and headless
keyboard flows. Interface coverage includes a fixed 84×24 welcome snapshot,
resize recovery, dialog focus, paste, Unicode and appearance. Also exercise the
changed keyboard flows in a real PTY before shipping. Release archives include
the locked Python runtime dependencies and the optimized Core.
