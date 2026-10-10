/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//! Bakes the project's shared version into this crate.
//!
//! The whole project ships ONE version scheme: the semantic version in the
//! repository-root `VERSION` file as the version name, plus the git commit
//! count as the version code — the same two values Gradle derives for the
//! Android, Desktop and Web clients. Cargo cannot read a version out of an
//! external file, so the values are injected here as `rustc-env` variables and
//! re-exported through [`messenger_core::version`]. Everything that reports a
//! version (the TUI banner and `--version`, the FFI's `core_version`) reads it
//! from there, so a Rust client never claims a different version than the
//! Kotlin one it was built alongside — which is what `CARGO_PKG_VERSION`
//! (the internal workspace crate version) would have done.

use std::path::{Path, PathBuf};
use std::process::Command;

fn main() {
    let root = repository_root();
    let version_file = root.join("VERSION");
    println!("cargo:rerun-if-changed={}", version_file.display());

    // A new commit changes neither the VERSION file nor any source in this
    // package, so watch the git reflog too: without it a dev build would keep
    // reporting the commit count it was first compiled at. Absent in an
    // exported tarball, where the count is not meaningful anyway.
    let reflog = root.join(".git/logs/HEAD");
    if reflog.is_file() {
        println!("cargo:rerun-if-changed={}", reflog.display());
    }

    let version = read_version(&version_file).unwrap_or_else(|| {
        // Keep the build going, but make the fallback impossible to mistake
        // for a real release version.
        println!(
            "cargo:warning=no VERSION file at {}; reporting 0.0.0",
            version_file.display()
        );
        "0.0.0".to_string()
    });
    println!("cargo:rustc-env=MESSENGER_VERSION={version}");
    // The path this build actually read, so tests can assert against the real
    // file instead of re-deriving the repository layout (which drifted once
    // already: a hand-written relative path pointed one directory too high).
    println!("cargo:rustc-env=MESSENGER_VERSION_FILE={}", version_file.display());
    println!(
        "cargo:rustc-env=MESSENGER_VERSION_CODE={}",
        commit_count(&root)
    );
}

/// The repository root: the git top level when there is one, otherwise the
/// nearest ancestor of this crate that holds a `VERSION` file.
///
/// The git top level wins even when `VERSION` is missing from it, so the
/// "no VERSION file" warning names the directory the file belongs in rather
/// than this crate's own directory.
fn repository_root() -> PathBuf {
    if let Some(toplevel) = git_output(&crate_dir(), &["rev-parse", "--show-toplevel"]) {
        return PathBuf::from(toplevel);
    }
    let mut dir = crate_dir();
    loop {
        if dir.join("VERSION").is_file() {
            return dir;
        }
        if !dir.pop() {
            return crate_dir();
        }
    }
}

fn crate_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn read_version(version_file: &Path) -> Option<String> {
    let text = std::fs::read_to_string(version_file).ok()?;
    let version = text.trim();
    (!version.is_empty()).then(|| version.to_string())
}

/// Commits reachable from HEAD; 0 when git is unavailable (exported tarball,
/// no repository), which the UI renders as an unknown build rather than
/// inventing a number.
fn commit_count(root: &Path) -> u64 {
    git_output(root, &["rev-list", "--count", "HEAD"])
        .and_then(|count| count.trim().parse().ok())
        .unwrap_or(0)
}

fn git_output(dir: &Path, args: &[&str]) -> Option<String> {
    let output = Command::new("git")
        .arg("-C")
        .arg(dir)
        .args(args)
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    String::from_utf8(output.stdout)
        .ok()
        .map(|text| trim_git_output(&text))
}

/// git terminates its output with a newline. An untrimmed
/// `rev-parse --show-toplevel` yields a path whose trailing `\n` makes every
/// `join("VERSION")` miss — the file was present and the build still reported
/// "no VERSION file", with the newline breaking the warning across two lines.
///
/// No `#[cfg(test)]` module here: cargo compiles a build script in script mode
/// and never builds a test harness for it, so tests in this file would be dead
/// code that silently never runs. `version.rs` covers the baked-in values
/// instead (and fails loudly when this trim is removed).
fn trim_git_output(raw: &str) -> String {
    raw.trim().to_string()
}
