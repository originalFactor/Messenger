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

//! `@` file mentions: the picker's index, its filtering, and the send-time
//! expansion that turns `@path` into the file's contents.
//!
//! ## Why the contents are inlined rather than left as a path
//!
//! Mentioning a file is only useful if the model can see it. A bare `@path` in
//! the message is *text* — the agent reads it with its own file tools, which
//! are off unless the agent was configured to have them, and absent entirely in
//! read-only mode for writes. Worse, if only the path were stored, the second
//! turn of a conversation would re-send a history message that says `@src/x.rs`
//! and the file's contents would have fallen out of context.
//!
//! So the mention is expanded at send time and the EXPANDED text is what gets
//! persisted. History then replays exactly what the model saw.
//!
//! The expansion is bounded — see [`MAX_INLINE_BYTES`] — and everything that
//! cannot be inlined safely (too large, not a file, not UTF-8, outside the
//! workspace) stays as the literal `@path` the user typed, with a note
//! explaining why. Silently dropping the mention or inlining half a file would
//! both be worse than saying so.

use std::path::{Path, PathBuf};

use walkdir::WalkDir;

/// The largest file inlined into a message. Beyond this the mention stays a
/// path: the point is to hand the model readable context, not to fill the
/// window with one file.
pub const MAX_INLINE_BYTES: u64 = 64 * 1024;

/// How many files the picker will index. The workspace walk is capped so a
/// stray `node_modules`-sized tree cannot stall the UI thread: the picker is
/// opened by a keystroke, and it must feel like one.
pub const MAX_INDEXED_FILES: usize = 5_000;

/// Directories never worth mentioning: build output, dependency trees and VCS
/// internals are noise in a file picker and dominate the index otherwise.
const SKIP_DIRS: &[&str] = &[
    ".git",
    "node_modules",
    "target",
    "dist",
    "build",
    ".venv",
    "venv",
    "__pycache__",
    ".idea",
    ".gradle",
];

/// A workspace file the picker can offer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MentionItem {
    /// Path relative to the workspace root, with forward slashes — the same
    /// spelling the tools use, so the user can paste it into a command.
    pub path: String,
    /// `path`'s final segment, precomputed for ranking.
    pub name: String,
}

/// The workspace's files, indexed once when the picker opens.
#[derive(Debug, Default, Clone)]
pub struct MentionIndex {
    root: PathBuf,
    items: Vec<MentionItem>,
}

impl MentionIndex {
    /// Walk `root`, collecting up to [`MAX_INDEXED_FILES`] regular files.
    ///
    /// Hidden files are indexed (a dotfile is often exactly what is wanted) but
    /// [`SKIP_DIRS`] is pruned wholesale. Symlinks are not followed: a link
    /// could escape the workspace or loop.
    pub fn build(root: &Path) -> Self {
        let root = root.to_path_buf();
        let mut items = Vec::new();
        let walker = WalkDir::new(&root)
            .follow_links(false)
            .into_iter()
            .filter_entry(|entry| !is_skipped_dir(entry.path()));
        for entry in walker.filter_map(Result::ok) {
            if items.len() >= MAX_INDEXED_FILES {
                break;
            }
            if !entry.file_type().is_file() {
                continue;
            }
            let Ok(relative) = entry.path().strip_prefix(&root) else {
                continue;
            };
            let Some(path) = relative_path_string(relative) else {
                continue;
            };
            let name = relative
                .file_name()
                .map(|name| name.to_string_lossy().to_string())
                .unwrap_or_else(|| path.clone());
            items.push(MentionItem { path, name });
        }
        items.sort_by(|a, b| a.path.cmp(&b.path));
        Self { root, items }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    pub fn len(&self) -> usize {
        self.items.len()
    }

    /// The matches for a `@` query, best first.
    ///
    /// Ranking is deliberately simple and predictable: a filename that starts
    /// with the query beats one that merely contains it, which beats a path
    /// match. Within a tier the shortest path wins — the shallowest file is
    /// almost always the intended one.
    pub fn search(&self, query: &str) -> Vec<MentionItem> {
        let needle = query.trim().to_lowercase();
        if needle.is_empty() {
            return self.items.iter().take(50).cloned().collect();
        }
        let mut scored: Vec<(u8, usize, &MentionItem)> = self
            .items
            .iter()
            .filter_map(|item| {
                let name = item.name.to_lowercase();
                let path = item.path.to_lowercase();
                let tier = if name.starts_with(&needle) {
                    0
                } else if name.contains(&needle) {
                    1
                } else if path.contains(&needle) {
                    2
                } else {
                    return None;
                };
                Some((tier, item.path.len(), item))
            })
            .collect();
        scored.sort_by(|a, b| a.0.cmp(&b.0).then(a.1.cmp(&b.1)).then(a.2.path.cmp(&b.2.path)));
        scored.into_iter().take(50).map(|(_, _, item)| item.clone()).collect()
    }

    /// Resolve a mention's path to a file inside the workspace.
    ///
    /// Accepts the workspace-relative spelling the picker inserts and absolute
    /// paths. Anything that resolves outside the workspace is rejected: the
    /// mention syntax should not become a way to pull arbitrary files into a
    /// cloud-synced conversation.
    pub fn resolve(&self, raw: &str) -> Option<PathBuf> {
        let candidate = Path::new(raw);
        let joined = if candidate.is_absolute() {
            candidate.to_path_buf()
        } else {
            self.root.join(candidate)
        };
        let canonical_root = std::fs::canonicalize(&self.root).ok()?;
        let canonical = std::fs::canonicalize(&joined).ok()?;
        canonical.starts_with(&canonical_root).then_some(canonical)
    }
}

/// Does this path sit inside a directory the index prunes?
fn is_skipped_dir(path: &Path) -> bool {
    path.file_name()
        .and_then(|name| name.to_str())
        .map(|name| SKIP_DIRS.contains(&name))
        .unwrap_or(false)
}

/// The file part of `path` spelled with forward slashes, or `None` when it is
/// not valid UTF-8 (nothing to show, and mentions are text).
fn relative_path_string(path: &Path) -> Option<String> {
    let mut parts: Vec<String> = Vec::new();
    for component in path.components() {
        let std::path::Component::Normal(part) = component else {
            // A `.` or `..` component would make the mention ambiguous.
            return None;
        };
        parts.push(part.to_str()?.to_string());
    }
    (!parts.is_empty()).then(|| parts.join("/"))
}

/// One `@mention` found in a message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Mention {
    /// Byte range of the whole `@path` token in the message.
    pub start: usize,
    pub end: usize,
    /// The path as typed, without the `@`.
    pub path: String,
}

/// Find every `@path` token in `text`.
///
/// A mention starts at an `@` that begins the text or follows whitespace, and
/// runs to the next whitespace. The boundary rule matters: `me@example.com`
/// must not become a mention, and neither must `@` in the middle of a word.
pub fn parse_mentions(text: &str) -> Vec<Mention> {
    let mut mentions = Vec::new();
    let bytes = text.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] != b'@' {
            index += 1;
            continue;
        }
        let at_start = index == 0
            || text[..index]
                .chars()
                .next_back()
                .map(char::is_whitespace)
                .unwrap_or(true);
        if !at_start {
            index += 1;
            continue;
        }
        let start = index;
        let mut end = index + 1;
        while end < bytes.len() {
            let ch = text[end..].chars().next().unwrap_or(' ');
            if ch.is_whitespace() {
                break;
            }
            end += ch.len_utf8();
        }
        let path = text[start + 1..end].to_string();
        if !path.is_empty() {
            mentions.push(Mention { start, end, path });
        }
        index = end.max(index + 1);
    }
    mentions
}

/// The outcome of expanding the mentions in a message.
#[derive(Debug, PartialEq, Eq)]
pub struct Expansion {
    pub text: String,
    /// Mentions that could not be inlined, with the reason — surfaced as notes
    /// so the user learns why their file did not appear.
    pub skipped: Vec<String>,
}

/// Replace each `@path` with a fenced block carrying the file's contents.
///
/// Mentions that cannot be inlined are left exactly as typed.
pub fn expand_mentions(text: &str, index: &MentionIndex) -> Expansion {
    let mentions = parse_mentions(text);
    if mentions.is_empty() {
        return Expansion {
            text: text.to_string(),
            skipped: Vec::new(),
        };
    }
    let mut out = String::with_capacity(text.len());
    let mut skipped = Vec::new();
    let mut cursor = 0;
    for mention in mentions {
        out.push_str(&text[cursor..mention.start]);
        match inline_file(&mention.path, index) {
            Ok(inlined) => out.push_str(&inlined),
            Err(reason) => {
                skipped.push(format!("@{} — {reason}", mention.path));
                out.push_str(&text[mention.start..mention.end]);
            }
        }
        cursor = mention.end;
    }
    out.push_str(&text[cursor..]);
    Expansion { text: out, skipped }
}

/// Read a mentioned file, or explain why it was not inlined.
fn inline_file(raw: &str, index: &MentionIndex) -> Result<String, String> {
    let Some(path) = index.resolve(raw) else {
        return Err("not a readable path inside the workspace".into());
    };
    let metadata = std::fs::metadata(&path).map_err(|error| error.to_string())?;
    if !metadata.is_file() {
        return Err("not a file".into());
    }
    if metadata.len() > MAX_INLINE_BYTES {
        return Err(format!(
            "too large to inline ({} bytes, limit {MAX_INLINE_BYTES})",
            metadata.len()
        ));
    }
    let bytes = std::fs::read(&path).map_err(|error| error.to_string())?;
    let content = String::from_utf8(bytes).map_err(|_| "not a UTF-8 text file".to_string())?;
    // The path as the user spelled it is what the model should see beside the
    // contents; it identifies where the code came from.
    Ok(fenced_block(raw, &content))
}

/// A fenced block labelled with the path.
///
/// The fence is one backtick longer than any run inside the file, so a file
/// that itself contains a fence cannot end its own block early — including the
/// common case of a markdown file whose content includes a ``` block.
fn fenced_block(path: &str, content: &str) -> String {
    let fence = "`".repeat(longest_fence(content).saturating_add(1).max(3));
    let content = content.strip_suffix('\n').unwrap_or(content);
    format!("\n{fence}{path}\n{content}\n{fence}\n")
}

/// Length of the longest run of backticks in `content`, capped so a pathological
/// file cannot produce an absurd fence.
fn longest_fence(content: &str) -> usize {
    let mut longest = 0;
    let mut current = 0;
    for ch in content.chars() {
        if ch == '`' {
            current += 1;
            longest = longest.max(current);
        } else {
            current = 0;
        }
    }
    longest.min(16)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn workspace() -> (tempfile::TempDir, MentionIndex) {
        let dir = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(dir.path().join("src/deep")).unwrap();
        std::fs::create_dir_all(dir.path().join("node_modules/pkg")).unwrap();
        std::fs::create_dir_all(dir.path().join(".git")).unwrap();
        std::fs::write(dir.path().join("README.md"), "# hi\n").unwrap();
        std::fs::write(dir.path().join("src/main.rs"), "fn main() {}\n").unwrap();
        std::fs::write(dir.path().join("src/deep/notes.txt"), "note\n").unwrap();
        std::fs::write(dir.path().join("node_modules/pkg/index.js"), "x\n").unwrap();
        std::fs::write(dir.path().join(".git/HEAD"), "ref: refs/heads/main\n").unwrap();
        let index = MentionIndex::build(dir.path());
        (dir, index)
    }

    #[test]
    fn the_index_skips_dependency_and_vcs_trees() {
        let (_dir, index) = workspace();
        let found = index.search("");
        let paths: Vec<&str> = found.iter().map(|i| i.path.as_str()).collect();
        assert!(paths.contains(&"README.md"), "{paths:?}");
        assert!(paths.contains(&"src/main.rs"), "{paths:?}");
        assert!(!paths.iter().any(|p| p.contains("node_modules")), "{paths:?}");
        assert!(!paths.iter().any(|p| p.starts_with(".git")), "{paths:?}");
    }

    #[test]
    fn filenames_outrank_path_matches() {
        let (_dir, index) = workspace();
        // "notes" matches src/deep/notes.txt by filename; a path-only match
        // must rank below it.
        let found = index.search("notes");
        assert_eq!(found[0].path, "src/deep/notes.txt");
    }

    #[test]
    fn search_is_case_insensitive_and_matches_paths() {
        let (_dir, index) = workspace();
        assert_eq!(index.search("README")[0].path, "README.md");
        assert_eq!(index.search("deep")[0].path, "src/deep/notes.txt");
        assert!(index.search("nope-not-here").is_empty());
    }

    #[test]
    fn mentions_need_a_word_boundary() {
        let parsed = parse_mentions("look at @src/main.rs and me@example.com");
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].path, "src/main.rs");

        // At the very start of the message.
        let parsed = parse_mentions("@README.md please");
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].path, "README.md");
    }

    #[test]
    fn expansion_inlines_the_file_and_cites_the_path() {
        let (_dir, index) = workspace();
        let expanded = expand_mentions("read @README.md now", &index);
        assert!(expanded.skipped.is_empty());
        assert!(expanded.text.starts_with("read \n```README.md\n# hi\n```\n now"), "{:?}", expanded.text);
    }

    #[test]
    fn expansion_leaves_unsuitable_mentions_verbatim_and_explains() {
        let (dir, _index) = workspace();
        // Too large.
        let big = dir.path().join("big.txt");
        std::fs::write(&big, "x".repeat((MAX_INLINE_BYTES + 1) as usize)).unwrap();
        // Not UTF-8.
        std::fs::write(dir.path().join("bin.dat"), [0xff, 0xfe, 0xfd]).unwrap();
        let index = MentionIndex::build(dir.path());

        let expanded = expand_mentions("@big.txt @bin.dat @missing.txt", &index);
        assert_eq!(expanded.text, "@big.txt @bin.dat @missing.txt");
        assert_eq!(expanded.skipped.len(), 3);
        assert!(expanded.skipped[0].contains("too large"), "{:?}", expanded.skipped);
        assert!(expanded.skipped[1].contains("UTF-8"), "{:?}", expanded.skipped);
        assert!(expanded.skipped[2].contains("workspace"), "{:?}", expanded.skipped);
    }

    #[test]
    fn expansion_refuses_paths_outside_the_workspace() {
        let (_dir, index) = workspace();
        let expanded = expand_mentions("@../outside.txt", &index);
        assert_eq!(expanded.text, "@../outside.txt");
        assert_eq!(expanded.skipped.len(), 1);
    }

    #[test]
    fn a_file_containing_a_fence_cannot_close_its_own_block() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("doc.md"), "```\ninner\n```\n").unwrap();
        let index = MentionIndex::build(dir.path());
        let expanded = expand_mentions("@doc.md", &index);
        assert!(expanded.text.contains("````doc.md"), "{:?}", expanded.text);
        assert!(expanded.text.ends_with("````\n"), "{:?}", expanded.text);
    }

    #[test]
    fn a_message_without_mentions_is_untouched() {
        let (_dir, index) = workspace();
        let expanded = expand_mentions("no mentions here", &index);
        assert_eq!(expanded.text, "no mentions here");
        assert!(expanded.skipped.is_empty());
    }
}
