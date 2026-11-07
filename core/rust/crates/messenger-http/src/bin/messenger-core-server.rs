//! Stdio lifecycle bridge for the Node.js terminal client.

use std::{
    fs::OpenOptions,
    io::{self, Write},
    path::PathBuf,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

mod acp_stdio;

use messenger_http::ServerHandle;
use messenger_store::{Store, StoredAgent};
use tokio::sync::oneshot;
use uuid::Uuid;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args_os().skip(1).peekable();
    if args.peek().is_some_and(|arg| arg == "acp") {
        args.next();
        if args.next().is_none_or(|arg| arg != "--connect") {
            return Err("usage: messenger-core-server acp --connect <descriptor>".into());
        }
        let descriptor = args.next().filter(|_| args.next().is_none())
            .ok_or("usage: messenger-core-server acp --connect <descriptor>")?;
        // Explicit exit also releases a blocked Tokio stdin reader when the
        // Desktop connection closes before the editor closes its pipe.
        match acp_stdio::connect(&PathBuf::from(descriptor)).await {
            Ok(()) => std::process::exit(0),
            Err(error) => {
                eprintln!("messenger ACP: {error}");
                std::process::exit(1)
            }
        }
    }
    let path = args
        .next()
        .map(PathBuf::from)
        .filter(|_| args.next().is_none())
        .ok_or("usage: messenger-core-server <database path> | --version")?;
    if path.as_os_str() == "--version" {
        println!("{}", messenger_core::version::NAME);
        return Ok(());
    }

    // Keep API turn state in one process per database. Use a sidecar so this
    // lock is independent of SQLite's locks, and let the OS release it on exit.
    let mut options = OpenOptions::new();
    options.read(true).write(true).create(true).truncate(false);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(&path)?;
    let path = path.canonicalize()?;
    let mut lock_path = path.as_os_str().to_os_string();
    lock_path.push(".tui-lock");
    let lock = options.open(lock_path)?;
    lock.try_lock()
        .map_err(|error| format!("could not lock the TUI database: {error}"))?;

    let store = Arc::new(Store::open(&path)?);
    ensure_default_agent(&store)?;
    let server = ServerHandle::start_local(store).await?;

    // Keep the per-start bearer token on the private child-process pipe.
    {
        let mut stdout = io::stdout().lock();
        writeln!(
            stdout,
            "{}",
            serde_json::json!({
                "url": format!("http://{}", server.local_addr),
                "token": server.token,
            })
        )?;
        stdout.flush()?;
    }

    let (closed, shutdown) = oneshot::channel();
    std::thread::spawn(move || {
        let _ = io::copy(&mut io::stdin().lock(), &mut io::sink());
        let _ = closed.send(());
    });
    let _ = shutdown.await;
    tokio::time::timeout(Duration::from_secs(5), server.stop()).await??;
    Ok(())
}

fn ensure_default_agent(store: &Store) -> Result<(), Box<dyn std::error::Error>> {
    if store.get_default_agent()?.is_some() {
        return Ok(());
    }
    let now = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as i64;
    // Leave the model unconfigured until the user picks a provider and model.
    let agent = serde_json::from_value::<StoredAgent>(serde_json::json!({
        "id": Uuid::new_v4().to_string(),
        "name": "Default",
        "system_prompt": "",
        "is_default": true,
        "created_at": now,
        "updated_at": now,
    }))?;
    store.upsert_agent(&agent)?;
    Ok(())
}
