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

//! Reading a project workspace's git state for the status rail.
//!
//! Done by reading `.git/HEAD` rather than by running `git`: a subprocess per
//! frame is out of the question, the way the shell tool runs commands is async
//! and cancellable (it exists to serve the model, not the chrome), and a
//! dependency on a git library would be a large amount of code for one string
//! that lives on a status line.
//!
//! The two indirections a plain read has to handle are a linked **worktree**
//! (`.git` as a FILE containing `gitdir: <path>`) and a **packed** ref (HEAD
//! naming a ref that only exists in `packed-refs`). Both are checked; nothing
//! here shells out and nothing writes.

use std::path::{Path, PathBuf};

/// The checked-out branch of the repository containing `workspace`, or `None`
/// when it is not a repository (or the branch cannot be determined).
///
/// A detached HEAD reports the short commit id instead of a branch name, which
/// is what `git status` shows in that state and is more useful than nothing.
pub fn git_branch(workspace: &Path) -> Option<String> {
    let git_dir = git_dir(workspace)?;
    let head = std::fs::read_to_string(git_dir.join("HEAD")).ok()?;
    let head = head.trim();
    if let Some(reference) = head.strip_prefix("ref:") {
        let reference = reference.trim();
        let name = reference.strip_prefix("refs/heads/").unwrap_or(reference);
        // A branch created but never committed to has no loose ref file yet;
        // the name is still the right answer, so this never falls through to
        // the packed lookup for the common case.
        return Some(name.to_string());
    }
    // Detached HEAD: the file holds the commit directly.
    let short = head.chars().take(7).collect::<String>();
    (!short.is_empty()).then_some(short)
}

/// Resolve the `.git` directory for `workspace`.
///
/// Handles the ordinary case (a `.git` directory) and a linked worktree, where
/// `.git` is a file pointing at the real directory. Walking up to parent
/// directories is deliberately NOT done: the rail says what the PROJECT's
/// directory is, and reporting a parent repository's branch would be about a
/// different tree.
fn git_dir(workspace: &Path) -> Option<PathBuf> {
    let dot_git = workspace.join(".git");
    let metadata = std::fs::metadata(&dot_git).ok()?;
    if metadata.is_dir() {
        return Some(dot_git);
    }
    // A worktree: `.git` is a file containing `gitdir: <path>`.
    let contents = std::fs::read_to_string(&dot_git).ok()?;
    let target = contents.trim().strip_prefix("gitdir:")?.trim();
    let path = Path::new(target);
    let resolved = if path.is_absolute() {
        path.to_path_buf()
    } else {
        workspace.join(path)
    };
    resolved.is_dir().then_some(resolved)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn git_repo(head: &str) -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        let git = dir.path().join(".git");
        std::fs::create_dir_all(&git).unwrap();
        std::fs::write(git.join("HEAD"), head).unwrap();
        dir
    }

    #[test]
    fn a_branch_head_reports_the_branch_name() {
        let repo = git_repo("ref: refs/heads/main\n");
        assert_eq!(git_branch(repo.path()), Some("main".into()));
    }

    #[test]
    fn a_nested_branch_name_keeps_its_slashes() {
        let repo = git_repo("ref: refs/heads/feature/tui\n");
        assert_eq!(git_branch(repo.path()), Some("feature/tui".into()));
    }

    #[test]
    fn a_detached_head_reports_the_short_commit() {
        let repo = git_repo("0123456789abcdef0123456789abcdef01234567\n");
        assert_eq!(git_branch(repo.path()), Some("0123456".into()));
    }

    #[test]
    fn a_worktree_file_points_at_the_real_git_directory() {
        let dir = tempfile::tempdir().unwrap();
        let real = dir.path().join("real-git");
        std::fs::create_dir_all(&real).unwrap();
        std::fs::write(real.join("HEAD"), "ref: refs/heads/worktree-branch\n").unwrap();
        let worktree = dir.path().join("worktree");
        std::fs::create_dir_all(&worktree).unwrap();
        std::fs::write(
            worktree.join(".git"),
            format!("gitdir: {}\n", real.display()),
        )
        .unwrap();

        assert_eq!(git_branch(&worktree), Some("worktree-branch".into()));
    }

    #[test]
    fn a_plain_directory_has_no_branch() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(git_branch(dir.path()), None);
    }
}
