//! Untrusted public repositories: HTTPS only, no checkout, hooks, processes or credentials.
use super::*;
use futures_util::StreamExt;
use reqwest::{Client, Url};
use std::{sync::atomic::AtomicBool, time::Duration};

const NETWORK_LIMIT: usize = 32 * 1024 * 1024;
const TREE_LIMIT: usize = 20_000;
const DEPTH_LIMIT: usize = 32;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Request {
    source: String,
    path: Option<String>,
    #[serde(rename = "ref")]
    revision: Option<String>,
}
#[derive(Debug)]
struct Source { url: String, github: Option<String>, tail: Option<String>, blob: bool }
#[derive(Serialize)]
pub(crate) struct Candidate { path: String, name: String, description: String }
#[derive(Serialize)]
pub(crate) struct Warning { path: String, message: String }
#[derive(Serialize)]
pub(crate) struct Discovery {
    source: String,
    #[serde(rename = "ref")]
    revision: String,
    items: Vec<Candidate>,
    warnings: Vec<Warning>,
}
#[derive(Clone)]
struct Entry { path: String, mode: String, id: String }
struct Snapshot {
    source: Source,
    revision: String,
    root: String,
    entries: Vec<Entry>,
    repository: Option<tempfile::TempDir>,
}
fn failure(error: impl std::fmt::Display) -> ApiError { bad(format!("remote skill import: {error}")) }
fn checked_path(path: &str) -> Result<(), ApiError> {
    if !path.is_empty() && (!valid_path(path) || path.split('/').count() > DEPTH_LIMIT || path.split('/').any(|p| p.eq_ignore_ascii_case(".git"))) {
        return Err(bad("invalid repository-relative skill path"));
    }
    Ok(())
}
fn parse(source: &str) -> Result<Source, ApiError> {
    let source = source.trim();
    if source.is_empty() || source.len() > 2048 || source.chars().any(char::is_control) { return Err(bad("invalid remote repository source")); }
    let normalized = if let Some(repo) = source.strip_prefix("git@github.com:") {
        format!("https://github.com/{repo}")
    } else if !source.contains(":") && source.split('/').count() == 2 {
        format!("https://github.com/{source}")
    } else { source.to_owned() };
    // URL parsers normalize dot segments; reject them before that normalization.
    let decoded = percent_encoding::percent_decode_str(&normalized).decode_utf8().map_err(failure)?;
    if decoded.split('/').any(|part| part == "." || part == "..") { return Err(bad("invalid repository URL path")); }
    let url = Url::parse(&normalized).map_err(failure)?;
    if url.scheme() != "https" { return Err(bad("only public HTTPS Git repositories are supported; GitHub SSH URLs may use git@github.com:owner/repo.git")); }
    if !url.username().is_empty() || url.password().is_some() || url.query().is_some() || url.fragment().is_some() { return Err(bad("repository URL must not contain credentials, query or fragment")); }
    let path = percent_encoding::percent_decode_str(url.path()).decode_utf8().map_err(failure)?.trim_matches('/').to_string();
    checked_path(&path)?;
    if url.host_str() == Some("github.com") {
        if url.port().is_some() { return Err(bad("GitHub URLs must use the default HTTPS port")); }
        let parts: Vec<_> = path.split('/').collect();
        if parts.len() < 2 || parts[0].is_empty() || parts[1].is_empty() { return Err(bad("GitHub source must contain owner/repository")); }
        let repo = parts[1].strip_suffix(".git").unwrap_or(parts[1]);
        if repo.is_empty() || !parts[0].bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-') || !repo.bytes().all(|b| b.is_ascii_alphanumeric() || b"-_.".contains(&b)) { return Err(bad("invalid GitHub owner/repository")); }
        let tail = if parts.len() > 2 {
            if parts.len() < 4 || !matches!(parts[2], "tree" | "blob") { return Err(bad("expected a GitHub repository, tree or blob URL")); }
            Some(parts[3..].join("/"))
        } else { None };
        let github = format!("{}/{repo}", parts[0]);
        Ok(Source { url: format!("https://github.com/{github}.git"), github: Some(github), tail, blob: parts.get(2) == Some(&"blob") })
    } else {
        if path.is_empty() { return Err(bad("Git URL must contain a repository path")); }
        Ok(Source { url: url.to_string().trim_end_matches('/').to_owned(), github: None, tail: None, blob: false })
    }
}
fn client() -> Result<Client, ApiError> {
    Client::builder().https_only(true).redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(10)).timeout(Duration::from_secs(45))
        .user_agent("Messenger-Skills-Import").build().map_err(failure)
}
async fn bytes(request: reqwest::RequestBuilder, limit: usize) -> Result<Vec<u8>, ApiError> {
    let response = request.send().await.map_err(failure)?;
    if !response.status().is_success() {
        let status = response.status();
        let mut detail = Vec::new();
        let mut stream = response.bytes_stream();
        while let Some(Ok(chunk)) = stream.next().await {
            let remaining = 4096 - detail.len();
            detail.extend_from_slice(&chunk[..chunk.len().min(remaining)]);
            if detail.len() == 4096 { break; }
        }
        let text = String::from_utf8_lossy(&detail);
        let value = serde_json::from_slice::<serde_json::Value>(&detail).ok();
        let message = value.as_ref().and_then(|value| value["message"].as_str()).unwrap_or(text.trim());
        return Err(failure(format!("HTTP {status}: {message}")));
    }
    if response.content_length().is_some_and(|size| size > limit as u64) { return Err(bad("remote repository response exceeds size limit")); }
    let mut output = Vec::new();
    let mut stream = response.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(failure)?;
        if output.len() + chunk.len() > limit { return Err(bad("remote repository response exceeds size limit")); }
        output.extend_from_slice(&chunk);
    }
    Ok(output)
}
fn packets(data: &[u8]) -> Result<Vec<&[u8]>, ApiError> {
    let mut result = Vec::new();
    let mut offset = 0;
    while offset < data.len() {
        let header = data.get(offset..offset + 4).ok_or_else(|| bad("truncated Git packet"))?;
        let size = usize::from_str_radix(std::str::from_utf8(header).map_err(failure)?, 16).map_err(failure)?;
        offset += 4;
        if size == 0 { continue; }
        if size < 4 { return Err(bad("invalid Git packet length")); }
        let packet = data.get(offset..offset + size - 4).ok_or_else(|| bad("truncated Git packet"))?;
        result.push(packet);
        offset += size - 4;
    }
    Ok(result)
}
fn oid(value: &str) -> bool { value.len() == 40 && value.bytes().all(|b| b.is_ascii_hexdigit()) }
fn advertised(data: &[u8]) -> Result<BTreeMap<String, String>, ApiError> {
    let mut refs = BTreeMap::new();
    for packet in packets(data)? {
        let text = std::str::from_utf8(packet).map_err(failure)?;
        if text.starts_with("ERR ") { return Err(failure(text)); }
        let line = text.split('\0').next().unwrap_or("").trim_end_matches('\n');
        if let Some((id, name)) = line.split_once(' ') {
            if oid(id) { refs.insert(name.to_owned(), id.to_ascii_lowercase()); }
        }
    }
    if refs.is_empty() { return Err(bad("repository has no advertised commits or does not support Git smart HTTPS")); }
    Ok(refs)
}
fn resolve(source: &Source, request: &Request, refs: &BTreeMap<String, String>) -> Result<(String, String), ApiError> {
    let find = |name: &str| -> Option<String> {
        if oid(name) { return Some(name.to_ascii_lowercase()); }
        [name.to_owned(), format!("refs/heads/{name}"), format!("refs/tags/{name}^{{}}"), format!("refs/tags/{name}")].iter().find_map(|key| refs.get(key).cloned())
    };
    let mut root = String::new();
    let revision = if let Some(tail) = &source.tail {
        // Longest advertised branch/tag wins, including branch names containing '/'.
        let parts: Vec<_> = tail.split('/').collect();
        let found = (1..=parts.len()).rev().find_map(|length| {
            let name = parts[..length].join("/");
            find(&name).map(|id| (id, parts[length..].join("/")))
        }).ok_or_else(|| bad("GitHub URL does not contain a known branch, tag or commit"))?;
        root = found.1;
        if source.blob {
            root = if root == "SKILL.md" { String::new() }
                else { root.strip_suffix("/SKILL.md").ok_or_else(|| bad("GitHub blob URL must point to SKILL.md"))?.to_owned() };
        }
        found.0
    } else { find(request.revision.as_deref().unwrap_or("HEAD")).ok_or_else(|| bad("unknown repository ref"))? };
    // Explicit ref overrides the URL ref while retaining its selected subtree.
    let revision = if let Some(name) = &request.revision { find(name).ok_or_else(|| bad("unknown repository ref"))? } else { revision };
    if let Some(path) = &request.path { root = path.clone(); }
    checked_path(&root)?;
    Ok((revision, root))
}
fn packet(text: &str) -> String { format!("{:04x}{text}", text.len() + 4) }
async fn snapshot(client: &Client, request: &Request) -> Result<Snapshot, ApiError> {
    let source = parse(&request.source)?;
    snapshot_source(client, request, source).await
}
async fn snapshot_source(client: &Client, request: &Request, source: Source) -> Result<Snapshot, ApiError> {
    if request.revision.as_ref().is_some_and(|s| s.is_empty() || s.len() > 512 || s.chars().any(char::is_control)) { return Err(bad("invalid repository ref")); }
    if let Some(path) = &request.path { checked_path(path)?; }
    let refs = advertised(&bytes(client.get(format!("{}/info/refs?service=git-upload-pack", source.url)), 2 * 1024 * 1024).await?)?;
    let (revision, root) = resolve(&source, request, &refs)?;
    if let Some(github) = &source.github {
        let entries = github_entries(client, &format!("https://api.github.com/repos/{github}/git/trees"), &revision, &root).await?;
        return Ok(Snapshot { source, revision, root, entries, repository: None });
    }
    let body = format!("{}{}0000{}", packet(&format!("want {revision} side-band-64k ofs-delta\n")), packet("deepen 1\n"), packet("done\n"));
    let response = bytes(client.post(format!("{}/git-upload-pack", source.url)).header("content-type", "application/x-git-upload-pack-request").body(body), NETWORK_LIMIT).await?;
    let mut pack = Vec::new();
    for payload in packets(&response)? {
        match payload.first() {
            Some(1) => pack.extend_from_slice(&payload[1..]),
            Some(3) => return Err(failure(String::from_utf8_lossy(&payload[1..]))),
            _ => {}
        }
    }
    if pack.len() < 12 || &pack[..4] != b"PACK" { return Err(bad("server did not return a Git pack")); }
    let count = u32::from_be_bytes(pack[8..12].try_into().unwrap());
    if count > TREE_LIMIT as u32 { return Err(bad("repository snapshot exceeds 20000 objects")); }
    let pinned = revision.clone();
    // Cancelling the HTTP request (including its deadline) interrupts pack work too.
    struct Interrupt(Arc<AtomicBool>);
    impl Drop for Interrupt { fn drop(&mut self) { self.0.store(true, std::sync::atomic::Ordering::Relaxed); } }
    let interrupt = Interrupt(Arc::new(AtomicBool::new(false)));
    let worker_interrupt = interrupt.0.clone();
    let (directory, entries) = tokio::task::spawn_blocking(move || -> Result<_, ApiError> {
        let directory = tempfile::tempdir().map_err(failure)?;
        let repository = gix::ThreadSafeRepository::init_opts(directory.path(), gix::create::Kind::Bare, gix::create::Options::default(), gix::open::Options::isolated()).map_err(failure)?.to_thread_local();
        let target = directory.path().join("objects/pack");
        std::fs::create_dir_all(&target).map_err(failure)?;
        gix_pack::Bundle::write_to_directory(&mut std::io::Cursor::new(pack), Some(&target), &mut gix::progress::Discard, &worker_interrupt, None::<&gix::Repository>, gix::hash::Kind::Sha1,
            gix_pack::bundle::write::Options { thread_limit: Some(1), alloc_limit_bytes: Some(8 * 1024 * 1024), ..Default::default() }).map_err(failure)?;
        let object = repository.find_object(gix::ObjectId::from_hex(pinned.as_bytes()).map_err(failure)?).map_err(failure)?;
        let tree = object.peel_to_kind(gix::object::Kind::Tree).map_err(failure)?.into_tree();
        let mut entries = Vec::new();
        walk(&tree, "", 0, &mut entries)?;
        drop(tree);
        Ok((directory, entries))
    }).await.map_err(failure)??;
    Ok(Snapshot { source, revision, root, entries, repository: Some(directory) })
}
async fn github_tree(client: &Client, url: String) -> Result<Vec<serde_json::Value>, ApiError> {
    let value: serde_json::Value = serde_json::from_slice(&bytes(client.get(url), 8 * 1024 * 1024).await?).map_err(failure)?;
    if value["truncated"].as_bool() != Some(false) { return Err(bad("GitHub tree is truncated; select a narrower subtree")); }
    let tree = value["tree"].as_array().ok_or_else(|| bad("invalid GitHub tree response"))?;
    if tree.len() > TREE_LIMIT { return Err(bad("selected repository tree exceeds 20000 entries")); }
    Ok(tree.clone())
}
async fn github_entries(client: &Client, endpoint: &str, revision: &str, root: &str) -> Result<Vec<Entry>, ApiError> {
    checked_path(root)?;
    let mut tree_id = revision.to_owned();
    // Resolve each directory using immediate children only. Unselected siblings can
    // contain arbitrarily large recursive trees without affecting this selection.
    for component in root.split('/').filter(|part| !part.is_empty()) {
        let tree = github_tree(client, format!("{endpoint}/{tree_id}")).await?;
        let selected = tree.iter().find(|entry| entry["path"] == component)
            .ok_or_else(|| bad("selected GitHub subtree does not exist"))?;
        if selected["type"] != "tree" || selected["mode"] != "040000" { return Err(bad("selected GitHub path must be a directory, not a symlink or submodule")); }
        tree_id = selected["sha"].as_str().filter(|s| oid(s)).ok_or_else(|| bad("invalid GitHub tree object ID"))?.to_owned();
    }
    let tree = github_tree(client, format!("{endpoint}/{tree_id}?recursive=1")).await?;
    tree.iter().filter(|entry| entry["type"] != "tree").map(|entry| {
        let relative = entry["path"].as_str().ok_or_else(|| bad("invalid GitHub path"))?;
        checked_path(relative)?;
        let path = if root.is_empty() { relative.to_owned() } else { format!("{root}/{relative}") };
        checked_path(&path)?;
        Ok(Entry { path, mode: entry["mode"].as_str().unwrap_or("").to_owned(), id: entry["sha"].as_str().filter(|s| oid(s)).ok_or_else(|| bad("invalid GitHub object ID"))?.to_owned() })
    }).collect()
}
fn walk(tree: &gix::Tree<'_>, prefix: &str, depth: usize, output: &mut Vec<Entry>) -> Result<(), ApiError> {
    if depth > DEPTH_LIMIT { return Err(bad("repository tree exceeds 32 levels")); }
    for entry in tree.iter() {
        let entry = entry.map_err(failure)?;
        let name = std::str::from_utf8(entry.filename()).map_err(failure)?;
        if name.eq_ignore_ascii_case(".git") { continue; }
        let path = if prefix.is_empty() { name.to_owned() } else { format!("{prefix}/{name}") };
        if output.len() >= TREE_LIMIT { return Err(bad("repository exceeds 20000 files")); }
        if entry.mode().is_tree() { walk(&entry.object().map_err(failure)?.into_tree(), &path, depth + 1, output)?; }
        else { output.push(Entry { path, mode: if entry.mode().is_blob() { "100644" } else { "unsupported" }.into(), id: entry.id().to_string() }); }
    }
    Ok(())
}
impl Snapshot {
    async fn read(&self, client: &Client, entry: &Entry) -> Result<Vec<u8>, ApiError> {
        checked_path(&entry.path)?;
        if !matches!(entry.mode.as_str(), "100644" | "100755") { return Err(bad("symlinks and submodules are unsupported in skill packages")); }
        if let Some(github) = &self.source.github {
            return bytes(client.get(format!("https://api.github.com/repos/{github}/git/blobs/{}", entry.id)).header("accept", "application/vnd.github.raw+json"), FILE_LIMIT).await;
        }
        let repo = gix::open::Options::isolated().open(self.repository.as_ref().unwrap().path()).map_err(failure)?.to_thread_local();
        let object = repo.find_object(gix::ObjectId::from_hex(entry.id.as_bytes()).map_err(failure)?).map_err(failure)?;
        if object.data.len() > FILE_LIMIT { return Err(bad("skill file exceeds 1 MiB")); }
        Ok(object.data.clone())
    }
    fn under_root(&self, path: &str) -> bool { self.root.is_empty() || path.starts_with(&format!("{}/", self.root)) }
    fn roots(&self) -> Vec<String> {
        self.entries.iter().filter(|entry| self.under_root(&entry.path) && (entry.path == "SKILL.md" || entry.path.ends_with("/SKILL.md")))
            .map(|entry| entry.path.strip_suffix("SKILL.md").unwrap().trim_end_matches('/').to_owned()).collect()
    }
}
async fn discover_inner(request: Request) -> Result<Discovery, ApiError> {
    let client = client()?;
    let snapshot = snapshot(&client, &request).await?;
    discover_snapshot(&client, &snapshot).await
}
async fn discover_snapshot(client: &Client, snapshot: &Snapshot) -> Result<Discovery, ApiError> {
    let roots = snapshot.roots();
    if roots.is_empty() { return Err(bad("no SKILL.md found in selected repository subtree")); }
    if roots.len() > 128 { return Err(bad("selected repository subtree exceeds 128 skills; specify a narrower path")); }
    let mut items = Vec::new();
    let mut warnings = Vec::new();
    for root in roots {
        let path = if root.is_empty() { "SKILL.md".to_owned() } else { format!("{root}/SKILL.md") };
        let entry = snapshot.entries.iter().find(|entry| entry.path == path).unwrap();
        let document = snapshot.read(&client, entry).await?;
        let metadata = (|| {
            let document = std::str::from_utf8(&document).map_err(failure)?;
            let frontmatter = parse_frontmatter(document)?;
            // A malformed sibling stays visible as a warning without hiding valid skills.
            validate(&frontmatter.name, &Package { files: BTreeMap::from([("SKILL.md".into(), document.into())]), binary_files: BTreeMap::new(), enabled: true })
        })();
        match metadata {
            Ok(frontmatter) => items.push(Candidate { path: root, name: frontmatter.name, description: frontmatter.description }),
            Err(error) => warnings.push(Warning { path: root, message: format!("{error:?}") }),
        }
    }
    Ok(Discovery { source: snapshot.source.url.clone(), revision: snapshot.revision.clone(), items, warnings })
}
async fn preview_inner(request: Request) -> Result<Detail, ApiError> {
    let client = client()?;
    let snapshot = snapshot(&client, &request).await?;
    preview_snapshot(&client, &snapshot).await
}
async fn preview_snapshot(client: &Client, snapshot: &Snapshot) -> Result<Detail, ApiError> {
    let roots = snapshot.roots();
    let root = if roots.iter().any(|root| root == &snapshot.root) { snapshot.root.clone() }
        else if roots.len() == 1 { roots[0].clone() }
        else { return Err(bad("select one discovered skill path before previewing")); };
    let prefix = if root.is_empty() { String::new() } else { format!("{root}/") };
    let mut package = Package { files: BTreeMap::new(), binary_files: BTreeMap::new(), enabled: true };
    let mut total = 0;
    for entry in &snapshot.entries {
        let Some(relative) = entry.path.strip_prefix(&prefix) else { continue; };
        // A nested skill owns its own assets; do not silently bundle another package.
        if roots.iter().any(|nested| nested != &root && nested.starts_with(&prefix) && entry.path.starts_with(&format!("{nested}/"))) { continue; }
        if relative.split('/').any(|part| part.eq_ignore_ascii_case(".git")) { continue; }
        checked_path(relative)?;
        if package.files.len() + package.binary_files.len() >= 128 { return Err(bad("a skill must contain 1–128 files")); }
        let content = snapshot.read(&client, entry).await?;
        total += content.len();
        if total > PACKAGE_LIMIT { return Err(bad("skill package exceeds 4 MiB")); }
        match String::from_utf8(content) {
            Ok(text) if !text.contains('\0') => { package.files.insert(relative.to_owned(), text); }
            Ok(text) => { package.binary_files.insert(relative.to_owned(), STANDARD.encode(text.as_bytes())); }
            Err(error) => { package.binary_files.insert(relative.to_owned(), STANDARD.encode(error.into_bytes())); }
        }
    }
    let frontmatter = parse_frontmatter(package.files.get("SKILL.md").ok_or_else(|| bad("SKILL.md is required"))?)?;
    let frontmatter = validate(&frontmatter.name, &package)?;
    Ok(Detail { summary: Summary { frontmatter, enabled: true }, files: package.files, binary_files: package.binary_files })
}
#[cfg(test)]
mod tests {
    use super::*;
    use axum::{body::Body, http::Request as HttpRequest};
    use tower::ServiceExt;

    fn request(source: &str) -> Request { Request { source: source.into(), path: None, revision: None } }
    fn refs() -> BTreeMap<String, String> {
        [("HEAD", "1"), ("refs/heads/main", "1"), ("refs/heads/feature", "2"), ("refs/heads/feature/slash", "3"), ("refs/tags/v1", "4"), ("refs/tags/v1^{}", "5")]
            .into_iter().map(|(key, digit)| (key.to_owned(), digit.repeat(40))).collect()
    }
    #[test]
    fn sources_normalize_and_reject_unsafe_urls() {
        for source in ["owner/repo", "https://github.com/owner/repo", "https://github.com/owner/repo.git", "git@github.com:owner/repo.git"] {
            assert_eq!(parse(source).unwrap().url, "https://github.com/owner/repo.git");
        }
        assert_eq!(parse("https://git.example.com/group/repo.git").unwrap().github, None);
        for source in ["file:///tmp/repo", "ssh://git@example.com/repo", "git://example.com/repo", "http://example.com/repo", "https://user:pass@example.com/repo", "https://github.com/a/b?token=secret", "https://github.com/a/b/tree/main/%2e%2e/escape", "https://github.com/a/b/tree/main/%2Fescape", "https://github.com/a/b/tree/main/a%5Cb", "https://github.com/a/b/invalid/main", "https://github.com/a/.git"] {
            assert!(parse(source).is_err(), "{source}");
        }
    }
    #[test]
    fn github_tree_blob_percent_encoding_and_longest_ref_are_unambiguous() {
        let cases = [
            ("https://github.com/a/b/tree/feature/slash/skills/review", "3", "skills/review"),
            ("https://github.com/a/b/tree/feature%2Fslash/skills/review", "3", "skills/review"),
            ("https://github.com/a/b/blob/main/skills/review/SKILL.md", "1", "skills/review"),
            ("https://github.com/a/b/blob/main/SKILL.md", "1", ""),
            ("https://github.com/a/b/tree/v1/review", "5", "review"),
        ];
        for (url, digit, path) in cases {
            assert_eq!(resolve(&parse(url).unwrap(), &request(url), &refs()).unwrap(), (digit.repeat(40), path.to_owned()));
        }
        let mut selected = request("a/b");
        selected.revision = Some("a".repeat(40));
        selected.path = Some("skills/review".into());
        assert_eq!(resolve(&parse(&selected.source).unwrap(), &selected, &refs()).unwrap(), ("a".repeat(40), "skills/review".into()));
        selected.path = Some("../escape".into());
        assert!(resolve(&parse(&selected.source).unwrap(), &selected, &refs()).is_err());
        for path in ["README.md", "fooSKILL.md", "skills/FooSKILL.md"] {
            assert!(resolve(&parse(&format!("https://github.com/a/b/blob/main/{path}")).unwrap(), &request("a/b"), &refs()).is_err());
        }
    }
    #[test]
    fn smart_http_packets_are_bounded_and_refs_are_parsed() {
        let data = format!("{}0000{}0000", packet("# service=git-upload-pack\n"), packet(&format!("{} HEAD\0side-band-64k shallow\n", "a".repeat(40))));
        assert_eq!(advertised(data.as_bytes()).unwrap()["HEAD"], "a".repeat(40));
        for invalid in [b"0001".as_slice(), b"zzzz", b"0010short", b"000", b"0008ERR "] {
            assert!(advertised(invalid).is_err());
        }
    }
    fn fixture() -> Snapshot {
        let directory = tempfile::tempdir().unwrap();
        let repo = gix::ThreadSafeRepository::init_opts(directory.path(), gix::create::Kind::Bare, gix::create::Options::default(), gix::open::Options::isolated()).unwrap().to_thread_local();
        let skill = b"---\nname: review\ndescription: Review files\n---\nDo not execute scripts";
        let nested = b"---\nname: nested\ndescription: Other package\n---\nOther";
        let entries = [("skills/review/SKILL.md", skill.as_slice()), ("skills/review/assets/icon.bin", &[0,255,128]), ("skills/review/scripts/example.sh", b"touch NEVER"), ("skills/review/nested/SKILL.md", nested.as_slice()), ("skills/review/nested/private.txt", b"other package"), ("elsewhere/SKILL.md", nested.as_slice()), ("skills/review/assets/nul.bin", &[0,42])]
            .into_iter().map(|(path, bytes)| Entry { path: path.into(), mode: "100644".into(), id: repo.write_blob(bytes).unwrap().to_string() }).collect();
        Snapshot { source: parse("https://example.com/repo.git").unwrap(), root: "skills/review".into(), revision: "a".repeat(40), entries, repository: Some(directory) }
    }
    #[tokio::test]
    async fn preview_preserves_binary_rejects_links_and_excludes_nested_packages() {
        let mut snapshot = fixture();
        let client = client().unwrap();
        let preview = preview_snapshot(&client, &snapshot).await.unwrap();
        assert_eq!(preview.summary.frontmatter.name, "review");
        assert_eq!(preview.binary_files["assets/icon.bin"], STANDARD.encode([0,255,128]));
        assert_eq!(preview.binary_files["assets/nul.bin"], STANDARD.encode([0,42]));
        assert_eq!(preview.files["scripts/example.sh"], "touch NEVER");
        assert_eq!(preview.files.len(), 2);
        assert!(!snapshot.repository.as_ref().unwrap().path().join("NEVER").exists());
        snapshot.entries[1].mode = "120000".into();
        assert!(preview_snapshot(&client, &snapshot).await.is_err());
        snapshot.entries[1].mode = "160000".into();
        assert!(preview_snapshot(&client, &snapshot).await.is_err());
        snapshot.entries[1].mode = "100644".into();
        snapshot.entries[1].path = "skills/review/../escape".into();
        assert!(preview_snapshot(&client, &snapshot).await.is_err());
    }
    #[tokio::test]
    async fn malformed_sibling_is_reported_without_blocking_valid_candidates() {
        let mut snapshot = fixture();
        snapshot.root.clear();
        let repo = gix::open::Options::isolated().open(snapshot.repository.as_ref().unwrap().path()).unwrap().to_thread_local();
        let invalid = repo.write_blob(b"---\nname: broken\ndescription: 42\n---\ninvalid").unwrap().to_string();
        snapshot.entries.push(Entry { path: "skills/broken/SKILL.md".into(), mode: "100644".into(), id: invalid });
        let discovery = discover_snapshot(&client().unwrap(), &snapshot).await.unwrap();
        assert!(discovery.items.iter().any(|item| item.name == "review"));
        assert_eq!(discovery.warnings.len(), 1);
        assert_eq!(discovery.warnings[0].path, "skills/broken");
        assert!(discovery.warnings[0].message.contains("frontmatter"));
        snapshot.root = "skills/broken".into();
        let discovery = discover_snapshot(&client().unwrap(), &snapshot).await.unwrap();
        assert!(discovery.items.is_empty());
        assert_eq!(discovery.warnings.len(), 1);
        assert!(preview_snapshot(&client().unwrap(), &snapshot).await.is_err());
    }
    #[tokio::test]
    async fn remote_endpoints_require_bearer_and_never_install_failed_previews() {
        let store = Arc::new(Store::open_memory().unwrap());
        let state = ApiState::new(store.clone(), "token");
        for action in ["discover", "preview"] {
            let uri = format!("/api/v1/skills/remote/{action}");
            let response = router(state.clone()).oneshot(HttpRequest::builder().method("POST").uri(&uri).header("content-type", "application/json").body(Body::from(r#"{"source":"file:///tmp/repo"}"#)).unwrap()).await.unwrap();
            assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
            let response = router(state.clone()).oneshot(HttpRequest::builder().method("POST").uri(&uri).header("authorization", "Bearer token").header("content-type", "application/json").body(Body::from(r#"{"source":"file:///tmp/repo"}"#)).unwrap()).await.unwrap();
            assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        }
        assert!(catalog(&store).unwrap().is_empty());
        state.shutdown.cancel();
    }
    #[tokio::test]
    async fn github_selected_subtree_avoids_truncated_large_siblings() {
        use wiremock::{Mock, MockServer, ResponseTemplate, matchers::{path, query_param}};
        let server = MockServer::start().await;
        let commit = "a".repeat(40);
        let skills = "b".repeat(40);
        let selected = "c".repeat(40);
        let huge = "d".repeat(40);
        let root_path = format!("/trees/{commit}");
        Mock::given(path(root_path.clone())).and(query_param("recursive", "1"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"truncated":true,"tree":[]}))).expect(0).mount(&server).await;
        Mock::given(path(format!("/trees/{huge}"))).respond_with(ResponseTemplate::new(500)).expect(0).mount(&server).await;
        Mock::given(path(root_path)).respond_with(ResponseTemplate::new(200).set_body_json(json!({"truncated":false,"tree":[
            {"path":"skills","type":"tree","mode":"040000","sha":skills},
            {"path":"huge-sibling","type":"tree","mode":"040000","sha":huge}
        ]}))).expect(1).mount(&server).await;
        Mock::given(path(format!("/trees/{skills}"))).respond_with(ResponseTemplate::new(200).set_body_json(json!({"truncated":false,"tree":[
            {"path":"review","type":"tree","mode":"040000","sha":selected}
        ]}))).expect(1).mount(&server).await;
        Mock::given(path(format!("/trees/{selected}"))).and(query_param("recursive", "1"))
            .respond_with(ResponseTemplate::new(200).set_body_json(json!({"truncated":false,"tree":[
                {"path":"SKILL.md","type":"blob","mode":"100644","sha":"e".repeat(40)},
                {"path":"assets/icon.bin","type":"blob","mode":"100644","sha":"f".repeat(40)}
            ]}))).expect(1).mount(&server).await;
        let entries = github_entries(&Client::new(), &format!("{}/trees", server.uri()), &commit, "skills/review").await.unwrap();
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].path, "skills/review/SKILL.md");
        assert_eq!(entries[1].path, "skills/review/assets/icon.bin");
        let requests = server.received_requests().await.unwrap();
        assert_eq!(requests.len(), 3);
        assert!(requests[..2].iter().all(|request| request.url.query().is_none()));
        server.verify().await;
    }
    #[tokio::test]
    async fn network_responses_enforce_status_and_byte_limits() {
        use wiremock::{Mock, MockServer, ResponseTemplate, matchers::path};
        let server = MockServer::start().await;
        Mock::given(path("/large")).respond_with(ResponseTemplate::new(200).set_body_bytes(vec![0; 32])).mount(&server).await;
        Mock::given(path("/auth")).respond_with(ResponseTemplate::new(401).set_body_json(json!({"message":"API rate limit exceeded"}))).mount(&server).await;
        let client = Client::new();
        assert!(bytes(client.get(format!("{}/large", server.uri())), 8).await.is_err());
        let error = bytes(client.get(format!("{}/auth", server.uri())), 1024).await.unwrap_err();
        assert!(format!("{error:?}").contains("API rate limit exceeded"));
    }
    #[tokio::test]
    #[ignore = "explicit public Git smart-HTTPS network smoke test"]
    async fn git_live_shallow_snapshot_and_pinned_fetch() {
        let client = client().unwrap();
        let input = request("https://github.com/octocat/Hello-World.git");
        let generic = || Source { url: input.source.clone(), github: None, tail: None, blob: false };
        let first = snapshot_source(&client, &input, generic()).await.unwrap();
        assert!(first.entries.iter().any(|entry| entry.path == "README"));
        let selected = Request { source: input.source.clone(), revision: Some(first.revision.clone()), path: None };
        let pinned = snapshot_source(&client, &selected, generic()).await.unwrap();
        assert_eq!(first.revision, pinned.revision);
        let entry = pinned.entries.iter().find(|entry| entry.path == "README").unwrap();
        assert!(!pinned.read(&client, entry).await.unwrap().is_empty());
    }
    #[tokio::test]
    #[ignore = "explicit public GitHub network smoke test"]
    async fn github_live_discovery_and_preview() {
        let discovery = discover_inner(request("https://github.com/anthropics/skills/tree/main/skills/pdf")).await.unwrap();
        let selected = discovery.items.iter().find(|item| item.name == "pdf").unwrap();
        assert!(oid(&discovery.revision));
        let preview = preview_inner(Request { source: discovery.source, revision: Some(discovery.revision), path: Some(selected.path.clone()) }).await.unwrap();
        assert_eq!(preview.summary.frontmatter.name, "pdf");
        assert!(preview.files.contains_key("SKILL.md"));
    }
}

pub(crate) async fn discover(Json(request): Json<Request>) -> Result<Json<Discovery>, ApiError> {
    tokio::time::timeout(Duration::from_secs(90), discover_inner(request)).await.map_err(|_| bad("remote skill discovery timed out"))?.map(Json)
}
pub(crate) async fn preview(Json(request): Json<Request>) -> Result<Json<Detail>, ApiError> {
    tokio::time::timeout(Duration::from_secs(90), preview_inner(request)).await.map_err(|_| bad("remote skill preview timed out"))?.map(Json)
}
