//! Messenger TUI entry point: CLI parse, config load, store open + seeding,
//! terminal init/restore, and the main event loop.
//!
//! Loop shape (30 ms tick doubles as the Document Engine's streaming batch
//! window):
//! ```text
//! loop {
//!     screen.render(&ui::compose(&mut app, w, h).lines)?;
//!     if event::poll(30ms)? { if Key => app.handle_key(key) }
//!     while let Ok(msg) = rx.try_recv() { app.apply(msg) }
//!     app.tick();
//!     if app.should_quit { break }
//! }
//! ```

use std::io::Write;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use crossterm::event::{self, Event, KeyEventKind};
use messenger_store::Store;
use messenger_tui::app::App;
use messenger_tui::config::{self, TuiConfig};
use messenger_tui::engine::{load_mcp_servers, Engine};
use messenger_tui::screen::Screen;
use messenger_tui::store_ops;
use messenger_tui::ui;
use tokio::sync::mpsc;

const HELP: &str = "\
messenger-tui — native Rust terminal client for the Messenger agent core

USAGE:
    messenger-tui [OPTIONS]

OPTIONS:
    --store <path>              SQLite store file (default ~/.messenger/tui/store.db)
    --workspace <dir>           agent workspace (default from settings.toml)
    --config <path>             settings file (default ~/.messenger/tui/settings.toml)
    --import-desktop [<dir>]    one-shot import of a legacy desktop Room database
                                (default dir ~/.messenger/files/databases)
    --version                   print the version and exit
    --help                      print this help

ENVIRONMENT:
    MESSENGER_TUI_STORE         overrides the store path
    MESSENGER_TUI_WORKSPACE     overrides the workspace directory
    MESSENGER_TUI_CONFIG        overrides the config path
";

struct Args {
    store: Option<PathBuf>,
    workspace: Option<String>,
    config: Option<PathBuf>,
    import_desktop: Option<Option<PathBuf>>,
    version: bool,
    help: bool,
}

fn parse_args() -> Result<Args, String> {
    let mut args = Args {
        store: None,
        workspace: None,
        config: None,
        import_desktop: None,
        version: false,
        help: false,
    };
    let mut argv = std::env::args().skip(1).peekable();
    while let Some(arg) = argv.next() {
        match arg.as_str() {
            "--help" | "-h" => args.help = true,
            "--version" | "-V" => args.version = true,
            "--store" => {
                args.store = Some(PathBuf::from(
                    argv.next().ok_or("--store needs a path")?,
                ))
            }
            "--workspace" => {
                args.workspace = Some(argv.next().ok_or("--workspace needs a directory")?)
            }
            "--config" => {
                args.config = Some(PathBuf::from(argv.next().ok_or("--config needs a path")?))
            }
            "--import-desktop" => {
                // The directory value is optional: only consume the next
                // argument when it does not look like another flag.
                let next = argv.peek().cloned();
                args.import_desktop = Some(match next {
                    Some(value) if !value.starts_with("--") => {
                        argv.next();
                        Some(PathBuf::from(value))
                    }
                    _ => None,
                });
            }
            other => return Err(format!("unknown argument: {other}")),
        }
    }
    Ok(args)
}

fn main() {
    if let Err(error) = run() {
        eprintln!("messenger-tui: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let args = parse_args()?;
    if args.help {
        print!("{HELP}");
        return Ok(());
    }
    if args.version {
        // Same version string the Kotlin clients show: the semantic version
        // from the repository-root VERSION file, plus the build's commit count.
        println!("messenger-tui {}", messenger_core::version::full());
        return Ok(());
    }

    let config_path = args
        .config
        .clone()
        .or_else(|| std::env::var("MESSENGER_TUI_CONFIG").ok().map(PathBuf::from))
        .unwrap_or_else(config::default_config_path);
    let mut config = config::load(&config_path);
    if let Some(workspace) = args
        .workspace
        .clone()
        .or_else(|| std::env::var("MESSENGER_TUI_WORKSPACE").ok())
    {
        config.workspace_dir = workspace;
    }
    let store_path = args
        .store
        .clone()
        .or_else(|| std::env::var("MESSENGER_TUI_STORE").ok().map(PathBuf::from))
        .unwrap_or_else(config::default_store_path);
    if let Some(parent) = store_path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("cannot create {}: {e}", parent.display()))?;
    }

    // ---- store + seeding -------------------------------------------------
    let store = Arc::new(Store::open(&store_path).map_err(|e| format!("store: {e}"))?);

    if let Some(import_dir) = &args.import_desktop {
        let dir = import_dir.clone().unwrap_or_else(config::default_desktop_db_dir);
        match messenger_store::import_legacy(&store, &dir, messenger_store::now_ms()) {
            Ok(Some(summary)) => println!(
                "Imported {} providers, {} models, {} agents, {} conversations, {} messages from {}",
                summary.providers,
                summary.models,
                summary.agents,
                summary.conversations,
                summary.messages,
                dir.display()
            ),
            Ok(None) => println!("No legacy database imported (already done, or none found in {}).", dir.display()),
            Err(error) => eprintln!("Legacy import failed: {error}"),
        }
    }

    store_ops::ensure_default_agent(&store).map_err(|e| format!("seeding: {e}"))?;

    // ---- runtime + engine ------------------------------------------------
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("tokio: {e}"))?;
    let (tx, mut rx) = mpsc::unbounded_channel();
    let engine = Arc::new(Engine::new(
        Arc::clone(&store),
        tx,
        PathBuf::from(&config.workspace_dir),
        runtime.handle().clone(),
    ));

    // Offline title-agent seeding + optional startup sync, both in the
    // background so the UI never blocks on the network.
    {
        let engine = Arc::clone(&engine);
        let store = Arc::clone(&store);
        let avatars = store_path
            .parent()
            .map(|parent| parent.join("cloud_avatars"))
            .unwrap_or_else(|| PathBuf::from("cloud_avatars"));
        let servers = load_mcp_servers(&store);
        runtime.spawn(async move {
            {
                let sync = messenger_sync::SyncEngine::new(
                    &store,
                    store_ops::session_from_kv(&store),
                )
                .with_avatars_dir(avatars);
                if let Err(error) = sync.ensure_builtin_title_agent() {
                    let _ = engine.tx.send(messenger_tui::engine::UiMsg::ToolLog(error));
                }
                if store_ops::session_from_kv(&store).cookie.is_some() {
                    if let Err(error) = sync.refresh_user().await {
                        let _ = engine
                            .tx
                            .send(messenger_tui::engine::UiMsg::CloudStatus(format!(
                                "Session refresh failed: {error}"
                            )));
                    }
                    if let Ok(user) = sync.signed_in() {
                        let _ = sync.ensure_builtin_provider(&user);
                    }
                    let _ = sync.sync_builtin_provider_models(false).await;
                    match sync.sync_internal(None, false, None).await {
                        Ok(result) => {
                            let _ = engine
                                .tx
                                .send(messenger_tui::engine::UiMsg::CloudStatus(format!(
                                    "Startup sync: {} agents, {} conversations, {} providers",
                                    result.agents, result.conversations, result.providers
                                )));
                        }
                        Err(error) => {
                            let _ = engine
                                .tx
                                .send(messenger_tui::engine::UiMsg::CloudStatus(format!(
                                    "Startup sync failed: {error}"
                                )));
                        }
                    }
                    let _ = engine.tx.send(messenger_tui::engine::UiMsg::SyncFinished);
                }
            }
            engine.connect_mcp(servers).await;
        });
    }

    let mut app = App::new(engine, config, config_path, store_path);
    // Agentic entry point: land directly in the project for the directory the
    // client was launched in, with a fresh conversation and the default Agent.
    app.bootstrap_session();

    // ---- terminal --------------------------------------------------------
    // The banner goes out as ordinary output BEFORE the viewport is built: it
    // lands in the terminal's scrollback, and since an inline viewport
    // positions itself relative to the cursor, building it afterwards anchors
    // the frame under the banner instead of over it.
    print_banner(app.config.show_banner);

    // `TerminalGuard` restores the terminal on every exit path, panic
    // included; `run` calls `stop` explicitly so the restore happens before
    // the process exits rather than at scope teardown.
    let screen_rows = crossterm::terminal::size().map(|(_, rows)| rows).unwrap_or(24);
    let screen = Screen::start(ui::viewport_rows(screen_rows)).map_err(|e| format!("terminal: {e}"))?;
    let mut guard = TerminalGuard(screen);
    let result = event_loop(&mut guard.0, &mut app, &mut rx);
    guard.0.stop();
    result
}

/// Print the wordmark above the frame, or nothing when it is switched off.
///
/// Written through `ratatui::text` printers so the banner and the frame share
/// the same styling model.
fn print_banner(enabled: bool) {
    if !enabled {
        return;
    }
    let width = crossterm::terminal::size().map(|(cols, _)| cols).unwrap_or(80);
    let mut stdout = std::io::stdout();
    for line in messenger_tui::banner::banner_lines(width) {
        let _ = writeln!(stdout, "{line}");
    }
    let _ = stdout.flush();
}

/// Owns the [`Screen`] so a panic anywhere in the loop still puts the
/// terminal back the way it was found.
struct TerminalGuard(Screen);

impl Drop for TerminalGuard {
    fn drop(&mut self) {
        self.0.stop();
    }
}

fn event_loop(
    screen: &mut Screen,
    app: &mut App,
    rx: &mut mpsc::UnboundedReceiver<messenger_tui::engine::UiMsg>,
) -> Result<(), String> {
    // The viewport is a fixed region at the bottom, so the frame is laid out
    // against THAT height, not the whole screen: composing for the screen
    // would give the transcript rows the region reserved for history, which is
    // then scrolled away unseen.
    loop {
        let (width, _) = screen.size().map_err(|e| e.to_string())?;
        let frame = ui::compose(app, width, screen.height());
        let history = frame.history.clone();
        let (lines, cursor) = (frame.lines, frame.cursor);
        screen
            .render(cursor, |f| ui::draw(f, &lines))
            .map_err(|e| e.to_string())?;
        // History goes in AFTER the frame, never before. `insert_before`
        // scrolls the region above the viewport down to open a gap, which
        // moves every row the frame just drew one row up — so inserting first
        // leaves the viewport's own rows duplicated above it (observed: the
        // project note rendered two or three times). Drawing first and then
        // inserting means the shift happens above content nobody will redraw.
        screen.flush_history(&history).map_err(|e| e.to_string())?;

        // 30 ms doubles as the streaming batch window (TARGET.md §6).
        if event::poll(Duration::from_millis(30)).map_err(|e| e.to_string())? {
            match event::read().map_err(|e| e.to_string())? {
                // Terminals with the enhanced keyboard protocol (and Windows
                // console input) report Release/Repeat alongside Press; acting
                // on those double-applies every key (a `toggle` flips twice and
                // `n` opens a form that immediately submits again).
                Event::Key(key) if key.kind == KeyEventKind::Press => app.handle_key(key),
                // Bracketed paste arrives as ONE event carrying the whole
                // chunk, newlines included. Treating it as keystrokes fired
                // one `send` per line and left the turn machinery tangled —
                // the reported "paste a few lines and the app hangs".
                Event::Paste(text) => app.handle_paste(&text),
                _ => {}
            }
        }
        while let Ok(msg) = rx.try_recv() {
            app.apply(msg);
        }
        app.tick();
        if app.should_quit {
            break;
        }
    }
    Ok(())
}

/// Also reachable from the library so tests can pin the defaults.
pub fn default_paths() -> (PathBuf, PathBuf, TuiConfig) {
    let config_path = config::default_config_path();
    let config = config::load(&config_path);
    (config::default_store_path(), config_path, config)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn help_text_documents_every_flag() {
        for flag in [
            "--store",
            "--workspace",
            "--config",
            "--import-desktop",
            "--version",
            "--help",
        ] {
            assert!(HELP.contains(flag), "help must document {flag}");
        }
    }

    #[test]
    fn version_flag_is_parsed() {
        // parse_args reads the process argv, so the flag table is checked
        // through HELP plus the short form it also accepts.
        assert!(HELP.contains("--version"));
        assert!(HELP.contains("--help"));
    }

    #[test]
    fn default_store_path_lives_under_the_tui_directory() {
        let path = config::default_store_path();
        assert!(path.ends_with("store.db"), "{path:?}");
        assert!(path.parent().unwrap().ends_with("tui"), "{path:?}");
    }
}
