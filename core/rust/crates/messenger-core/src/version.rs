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

//! The project's single version scheme, shared by every client.
//!
//! - **Name** — the semantic version (`MAJOR.MINOR.PATCH`) in the repository-root
//!   `VERSION` file: what the user sees in Settings, the TUI banner and
//!   `--version`.
//! - **Code** — the git commit count the build was made from: a monotonically
//!   increasing integer. Android needs it for `versionCode`; elsewhere it
//!   identifies the exact build behind a version name.
//!
//! The values arrive as `rustc-env` variables from this crate's `build.rs`
//! rather than from `CARGO_PKG_VERSION`, which is the *internal* crate version
//! of this Cargo workspace (`0.1.0` for every crate) and would have made a Rust
//! client claim a different version than the Kotlin build it shipped with.

/// Semantic version name from the repository-root `VERSION` file
/// (`0.0.0` when the build could not read it).
pub const NAME: &str = env!("MESSENGER_VERSION");

/// Version code: the git commit count this binary was built from, or `0` when
/// git was unavailable at build time (an exported tarball, a packaged source
/// release) — callers should render `0` as an unknown build rather than as a
/// real number.
pub const CODE: u64 = parse_code(env!("MESSENGER_VERSION_CODE"));

const fn parse_code(raw: &str) -> u64 {
    let bytes = raw.as_bytes();
    let mut value = 0u64;
    let mut i = 0;
    while i < bytes.len() {
        let digit = bytes[i];
        if digit < b'0' || digit > b'9' {
            return 0;
        }
        value = value * 10 + (digit - b'0') as u64;
        i += 1;
    }
    value
}

/// `"1.2.3 (456)"`, or just the name when the commit count is unknown.
pub fn full() -> String {
    if CODE == 0 {
        NAME.to_string()
    } else {
        format!("{NAME} ({CODE})")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_version_name_is_a_semantic_version() {
        let parts: Vec<&str> = NAME.split('.').collect();
        assert_eq!(parts.len(), 3, "VERSION must be MAJOR.MINOR.PATCH: {NAME}");
        for part in parts {
            assert!(
                !part.is_empty() && part.bytes().all(|b| b.is_ascii_digit()),
                "each VERSION component must be numeric: {NAME}"
            );
        }
    }

    #[test]
    fn the_baked_in_name_matches_the_version_file() {
        // The strongest form of the check above: the build script reads the
        // repository-root VERSION file, so the constant must be that file's
        // contents verbatim. Catches a broken repository-root lookup (a
        // git-output trim regression once made the build silently fall back to
        // 0.0.0 while the file sat right there) and an unreadable/empty file.
        // The path comes from the build script itself, so this does not
        // re-derive the repository layout.
        let version_file = env!("MESSENGER_VERSION_FILE");
        let expected = std::fs::read_to_string(version_file)
            .unwrap_or_else(|e| panic!("cannot read {version_file}: {e}"));
        assert_eq!(NAME, expected.trim(), "baked-in version disagrees with {version_file}");
    }

    #[test]
    fn the_version_code_is_the_commit_count() {
        // The build script reads `git rev-list --count HEAD`; a checked-out
        // build must therefore carry a positive code. Zero only appears in an
        // exported tree, which a test build never is.
        assert!(CODE > 0, "expected a git commit count, got {CODE}");
    }

    #[test]
    fn full_combines_name_and_code() {
        let text = full();
        assert!(text.starts_with(NAME), "{text}");
        if CODE > 0 {
            assert!(text.ends_with(&format!("({CODE})")), "{text}");
        }
    }
}
