//! Terminal command execution for the native ToolHost.
//!
//! Mirrors `ShellExecutor.desktop.kt`: Windows runs PowerShell with the
//! console output encoding forced to UTF-8, every other platform `/bin/sh -c`.
//! Both the timeout and turn cancellation TERMINATE the process — a live
//! child holds its stdout pipe open, so merely dropping the future would
//! leave the reader (and the turn) hanging.

use std::path::Path;
use std::process::Stdio;
use std::time::Duration;

use messenger_tools::{BuiltinTool, ToolExecutionResult};
use tokio::io::{AsyncRead, AsyncReadExt};
use tokio_util::sync::CancellationToken;

/// How much trailing output is retained while draining (Kotlin keeps the
/// last ~1 M chars so a chatty command cannot exhaust memory).
const DRAIN_KEEP_CHARS: usize = 1_000_000;

#[derive(Debug, Clone)]
pub struct ShellConfig {
    pub timeout_ms: u64,
    pub windows: bool,
}

impl Default for ShellConfig {
    fn default() -> Self {
        Self {
            timeout_ms: BuiltinTool::DEFAULT_TIMEOUT_MS,
            windows: cfg!(windows),
        }
    }
}

/// Run one command in the workspace, killing the process on timeout or
/// cancellation.
pub async fn run(
    command: &str,
    cfg: &ShellConfig,
    workspace: &Path,
    cancel: &CancellationToken,
) -> ToolExecutionResult {
    let _ = std::fs::create_dir_all(workspace);
    let mut child = match spawn(command, cfg, workspace) {
        Ok(child) => child,
        Err(error) => {
            return ToolExecutionResult {
                output: format!("Failed to execute command: {error}"),
                is_error: true,
            }
        }
    };

    let (stdout, stderr) = (child.stdout.take(), child.stderr.take());
    let stdout_task = tokio::spawn(drain(stdout));
    let stderr_task = tokio::spawn(drain(stderr));

    let timeout = Duration::from_millis(cfg.timeout_ms.max(1));
    let outcome = tokio::select! {
        status = child.wait() => Some(status),
        _ = tokio::time::sleep(timeout) => None,
        _ = cancel.cancelled() => None,
    };

    let timed_out = outcome.is_none();
    if timed_out {
        // Killing closes the pipes, which is what unblocks the readers.
        let _ = child.start_kill();
        let _ = child.wait().await;
    }
    let stdout = stdout_task.await.unwrap_or_default();
    let stderr = stderr_task.await.unwrap_or_default();

    if timed_out {
        return ToolExecutionResult {
            output: format!(
                "Command timed out after {} seconds and was terminated.",
                cfg.timeout_ms / 1000
            ),
            is_error: true,
        };
    }

    // `redirectErrorStream(true)` equivalent: stderr is appended after stdout.
    let mut combined = stdout;
    if !stderr.trim().is_empty() {
        combined.push('\n');
        combined.push_str(&stderr);
    }
    ToolExecutionResult {
        output: BuiltinTool::truncate_output(&combined, BuiltinTool::MAX_OUTPUT_CHARS),
        is_error: false,
    }
}

fn spawn(
    command: &str,
    cfg: &ShellConfig,
    workspace: &Path,
) -> std::io::Result<tokio::process::Child> {
    let mut process = if cfg.windows {
        let mut process = tokio::process::Command::new("powershell.exe");
        process.args([
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            &format!("[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; {command}"),
        ]);
        process
    } else {
        let mut process = tokio::process::Command::new("/bin/sh");
        process.args(["-c", command]);
        process
    };
    process
        .current_dir(workspace)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        process.as_std_mut().creation_flags(0x0800_0000); // CREATE_NO_WINDOW
    }
    process.spawn()
}

/// Read a pipe to EOF, keeping only the tail (`DRAIN_KEEP_CHARS`).
async fn drain<R: AsyncRead + Unpin>(pipe: Option<R>) -> String {
    let Some(mut pipe) = pipe else {
        return String::new();
    };
    let mut raw: Vec<u8> = Vec::new();
    let mut buffer = [0u8; 8192];
    loop {
        match pipe.read(&mut buffer).await {
            Ok(0) => break,
            Ok(n) => raw.extend_from_slice(&buffer[..n]),
            // A killed process closes the pipe mid-read; keep what we have.
            Err(_) => break,
        }
        if raw.len() > 2 * DRAIN_KEEP_CHARS {
            let cut = raw.len() - DRAIN_KEEP_CHARS;
            // Trim to a UTF-8 boundary so the decode below cannot mojibake.
            let cut = (cut..raw.len()).find(|i| raw[*i] & 0b1100_0000 != 0b1000_0000).unwrap_or(raw.len());
            raw.drain(..cut);
        }
    }
    String::from_utf8_lossy(&raw).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg(timeout_ms: u64) -> ShellConfig {
        ShellConfig {
            timeout_ms,
            windows: cfg!(windows),
        }
    }

    #[tokio::test]
    async fn runs_a_command_and_captures_output() {
        let dir = tempfile::tempdir().unwrap();
        let command = if cfg!(windows) { "Write-Output hi" } else { "echo hi" };
        let result = run(command, &ShellConfig::default(), dir.path(), &CancellationToken::new()).await;
        assert!(!result.is_error, "{}", result.output);
        assert!(result.output.contains("hi"), "{}", result.output);
    }

    #[tokio::test]
    async fn timeout_kills_the_process_and_reports_it() {
        let dir = tempfile::tempdir().unwrap();
        let command = if cfg!(windows) {
            "Start-Sleep -Seconds 30"
        } else {
            "sleep 30"
        };
        let started = std::time::Instant::now();
        let result = run(command, &cfg(300), dir.path(), &CancellationToken::new()).await;
        assert!(result.is_error);
        assert_eq!(result.output, "Command timed out after 0 seconds and was terminated.");
        assert!(started.elapsed() < Duration::from_secs(10), "kill must not block");
    }

    #[tokio::test]
    async fn cancellation_kills_the_process() {
        let dir = tempfile::tempdir().unwrap();
        let command = if cfg!(windows) {
            "Start-Sleep -Seconds 30"
        } else {
            "sleep 30"
        };
        let token = CancellationToken::new();
        let cancel = token.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(200)).await;
            cancel.cancel();
        });
        let started = std::time::Instant::now();
        let _ = run(command, &cfg(30_000), dir.path(), &token).await;
        assert!(started.elapsed() < Duration::from_secs(10), "cancel must kill");
    }

    #[tokio::test]
    async fn missing_executable_reports_the_failure() {
        let dir = tempfile::tempdir().unwrap();
        let result = run(
            "definitely-not-a-real-command-xyz",
            &ShellConfig { timeout_ms: 5_000, windows: true },
            dir.path(),
            &CancellationToken::new(),
        )
        .await;
        // PowerShell itself exists, so the failure surfaces as a non-zero
        // exit with the "not recognized" text rather than a spawn error.
        assert!(!result.output.is_empty());
    }
}
