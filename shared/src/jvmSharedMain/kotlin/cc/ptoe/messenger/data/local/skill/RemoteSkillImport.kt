/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.skill

import cc.ptoe.messenger.data.local.image.isPublicAddress
import cc.ptoe.messenger.domain.model.RemoteSkillCandidate
import cc.ptoe.messenger.domain.model.RemoteSkillCatalog
import cc.ptoe.messenger.domain.model.RemoteSkillRequest
import cc.ptoe.messenger.domain.model.RemoteSkillWarning
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private const val MAX_NETWORK = 32 * 1024 * 1024
private const val MAX_FILE = 1024 * 1024
private const val MAX_PACKAGE = 4 * 1024 * 1024
private const val MAX_TREE = 20_000
private const val MAX_DEPTH = 32
private const val MAX_REQUESTS = 256

internal class RemoteSkillPreview internal constructor(val source: String, val revision: String, val path: String, detail: SkillDetail) {
    // Keep the bytes that were reviewed separate from the maps exposed to the UI.
    private val reviewed = detail.packageData.copy(files = detail.packageData.files.toMap(), binaryFiles = detail.packageData.binaryFiles.toMap())
    val detail = detail.copy(packageData = reviewed.copy(files = reviewed.files.toMap(), binaryFiles = reviewed.binaryFiles.toMap()))
    internal fun reviewedPackage() = reviewed
}
internal fun interface RemoteSkillHttp { suspend fun get(url: HttpUrl, limit: Int): ByteArray }
private data class GithubSource(val owner: String, val repo: String, val ref: String?, val root: String) {
    val canonical: String get() = "https://github.com/$owner/$repo.git"
}
private data class GitEntry(val path: String, val mode: String, val id: String)
private data class Snapshot(val source: GithubSource, val revision: String, val root: String, val entries: List<GitEntry>) {
    val roots: List<String> get() = entries.asSequence().map { it.path }
        .filter { it == "SKILL.md" || it.endsWith("/SKILL.md") }
        .map { it.removeSuffix("SKILL.md").trimEnd('/') }.sorted().toList()
}

/** GitHub REST only. No clone, checkout, scripts, credentials, redirects, or ambient proxy. */
internal class RemoteSkillImport(private val http: RemoteSkillHttp = GithubHttp) {
    companion object {
        private val operations = Semaphore(2)
        private val starts = ArrayDeque<Long>()
        @Synchronized private fun admitRate() {
            val now = System.nanoTime()
            while (starts.isNotEmpty() && now - starts.first >= TimeUnit.MINUTES.toNanos(1)) starts.removeFirst()
            check(starts.size < 20) { "Remote skill operation rate exceeded" }
            starts.addLast(now)
        }
    }

    suspend fun discover(request: RemoteSkillRequest): RemoteSkillCatalog = limited {
        val budget = Budget(http)
        val snapshot = snapshot(request, budget, requirePinned = false)
        val roots = snapshot.roots
        require(roots.isNotEmpty() && roots.size <= 128) { "Select a subtree containing 1–128 skills" }
        val items = mutableListOf<RemoteSkillCandidate>()
        val warnings = mutableListOf<RemoteSkillWarning>()
        for (root in roots) {
            val path = if (root.isEmpty()) "SKILL.md" else "$root/SKILL.md"
            val bytes = readBlob(snapshot, snapshot.entries.single { it.path == path }, budget)
            try {
                val document = decodeUtf8(bytes)
                val metadata = parseSkillFrontmatter(document)
                validateSkill(metadata.name, StoredSkill(mapOf("SKILL.md" to document), enabled = true))
                items += RemoteSkillCandidate(metadata.name, metadata.description, root)
            } catch (_: Exception) {
                warnings += RemoteSkillWarning(root, "Invalid skill frontmatter")
            }
        }
        RemoteSkillCatalog(snapshot.source.canonical, snapshot.revision, items, warnings)
    }

    /** Preview accepts only the immutable commit returned by discovery, not a moving branch. */
    suspend fun preview(request: RemoteSkillRequest): RemoteSkillPreview = limited {
        val budget = Budget(http)
        val snapshot = snapshot(request, budget, requirePinned = true)
        val roots = snapshot.roots
        require(roots.size <= 128) { "Too many skills in selected subtree" }
        val root = when {
            snapshot.root in roots -> snapshot.root
            roots.size == 1 -> roots.single()
            else -> error("Select one discovered skill path")
        }
        val prefix = if (root.isEmpty()) "" else "$root/"
        val nested = roots.filter { it != root && it.startsWith(prefix) }.map { "$it/" }
        val selected = snapshot.entries.filter { it.path.startsWith(prefix) && nested.none(it.path::startsWith) }
        require(selected.size in 1..128) { "Skill file count exceeds limit" }
        val files = sortedMapOf<String, String>()
        val binary = sortedMapOf<String, String>()
        var total = 0L
        for (entry in selected) {
            val relative = entry.path.removePrefix(prefix)
            checkedPath(relative, allowEmpty = false)
            val bytes = readBlob(snapshot, entry, budget)
            total += bytes.size
            require(total <= MAX_PACKAGE) { "Skill package exceeds 4 MiB" }
            val text = try { decodeUtf8(bytes) } catch (_: Exception) { null }
            if (text != null && '\u0000' !in text) files[relative] = text
            else binary[relative] = Base64.getEncoder().encodeToString(bytes)
        }
        val document = files["SKILL.md"] ?: error("SKILL.md is required")
        val metadata = parseSkillFrontmatter(document)
        val data = StoredSkill(files, binary, enabled = true)
        val validated = validateSkill(metadata.name, data)
        RemoteSkillPreview(snapshot.source.canonical, snapshot.revision, root, SkillDetail(SkillSummary(validated, true), data))
    }

    /** Explicit local action after preview; conflicting names fail without modifying local data. */
    suspend fun install(preview: RemoteSkillPreview, store: RoomSkillStore): SkillDetail {
        val source = parseGithub(preview.source)
        require(source.ref == null && source.root.isEmpty() && oid(preview.revision)) { "Invalid preview identity" }
        checkedPath(preview.path)
        val data = preview.reviewedPackage()
        val name = parseSkillFrontmatter(data.files["SKILL.md"] ?: error("SKILL.md is required")).name
        return store.create(name, data)
    }

    private suspend fun <T> limited(block: suspend () -> T): T {
        check(operations.tryAcquire()) { "Too many remote skill operations" }
        return try { admitRate(); withTimeout(90_000) { block() } } finally { operations.release() }
    }

    private suspend fun snapshot(request: RemoteSkillRequest, budget: Budget, requirePinned: Boolean): Snapshot {
        val source = parseGithub(request.source)
        require(source.ref == null || oid(source.ref) || (request.revision != null && request.path != null)) {
            "Branch URLs are ambiguous; provide an explicit ref and path"
        }
        request.path?.let { checkedPath(it) }
        val requestedRef = request.revision ?: source.ref ?: "HEAD"
        require(requestedRef.isNotBlank() && requestedRef.toByteArray(Charsets.UTF_8).size <= 512 && requestedRef.none(Character::isISOControl)) { "Invalid Git ref" }
        if (requirePinned) require(oid(request.revision.orEmpty())) { "Preview requires a pinned commit" }
        val commit = budget.json(api(source, "commits", requestedRef), 256 * 1024).string("sha").lowercase()
        require(oid(commit) && (!oid(requestedRef) || commit == requestedRef.lowercase())) { "Invalid or changed commit" }
        val root = request.path ?: source.root
        checkedPath(root)
        var treeId = commit
        for (component in root.split('/').filter(String::isNotEmpty)) {
            val entries = tree(source, treeId, false, budget)
            val found = entries.singleOrNull { it.string("path") == component } ?: error("Selected directory missing or ambiguous")
            require(found.string("type") == "tree" && found.string("mode") == "040000") { "Selected path must be a directory" }
            treeId = found.string("sha").lowercase().also { require(oid(it)) { "Invalid tree object" } }
        }
        val entries = tree(source, treeId, true, budget)
        val paths = HashSet<String>()
        val output = mutableListOf<GitEntry>()
        for (entry in entries) {
            val relative = entry.string("path")
            checkedPath(relative, allowEmpty = false)
            val full = if (root.isEmpty()) relative else "$root/$relative"
            checkedPath(full, allowEmpty = false)
            require(paths.add(full)) { "Duplicate repository path" }
            val kind = entry.string("type")
            val mode = entry.string("mode")
            val id = entry.string("sha").lowercase()
            require(oid(id)) { "Invalid repository object ID" }
            if (kind == "tree") { require(mode == "040000") { "Invalid tree mode" }; continue }
            output += GitEntry(full, if (kind == "blob") mode else "unsupported", id)
        }
        return Snapshot(source, commit, root, output)
    }

    private suspend fun tree(source: GithubSource, id: String, recursive: Boolean, budget: Budget): List<JsonObject> {
        val url = api(source, "git", "trees", id).newBuilder().apply { if (recursive) addQueryParameter("recursive", "1") }.build()
        val result = budget.json(url, 8 * 1024 * 1024)
        require(result["truncated"]?.jsonPrimitive?.booleanOrNull == false) { "Repository tree is truncated" }
        val entries = result["tree"] as? JsonArray ?: error("Invalid repository tree")
        require(entries.size <= MAX_TREE) { "Repository tree exceeds entry limit" }
        return entries.map { it as? JsonObject ?: error("Invalid repository entry") }
    }

    private suspend fun readBlob(snapshot: Snapshot, entry: GitEntry, budget: Budget): ByteArray {
        require(entry.mode in setOf("100644", "100755")) { "Symlinks and submodules are unsupported" }
        val bytes = budget.get(api(snapshot.source, "git", "blobs", entry.id), MAX_FILE)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        val id = digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(id == entry.id) { "Blob content does not match pinned Git object" }
        return bytes
    }
}

private class Budget(private val http: RemoteSkillHttp) {
    private var bytes = 0L
    private var calls = 0
    suspend fun get(url: HttpUrl, limit: Int): ByteArray {
        require(url.isHttps && url.host == "api.github.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()) { "Invalid GitHub API URL" }
        require(++calls <= MAX_REQUESTS) { "Remote request count exceeds limit" }
        val content = http.get(url, minOf(limit, (MAX_NETWORK - bytes).toInt()))
        require(content.size <= limit && content.size.toLong() <= MAX_NETWORK - bytes) { "Remote data exceeds limit" }
        bytes += content.size
        return content
    }
    suspend fun json(url: HttpUrl, limit: Int): JsonObject = Json.parseToJsonElement(decodeUtf8(get(url, limit))) as? JsonObject ?: error("Invalid GitHub response")
}
private fun JsonObject.string(key: String): String {
    val value = this[key]?.jsonPrimitive ?: error("Missing GitHub field")
    require(value.isString) { "Invalid GitHub field" }
    return value.content
}
private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
private fun oid(value: String) = value.length == 40 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
private fun checkedPath(path: String, allowEmpty: Boolean = true) {
    if (path.isEmpty() && allowEmpty) return
    require(path.isNotEmpty() && path.toByteArray(Charsets.UTF_8).size <= 512 && path.none { it == '\\' || it == ':' || Character.isISOControl(it) } &&
        path.split('/').size <= MAX_DEPTH && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.equals(".git", true) }) { "Invalid repository-relative path" }
}
private fun api(source: GithubSource, vararg segments: String): HttpUrl = "https://api.github.com".toHttpUrl().newBuilder()
    .addPathSegment("repos").addPathSegment(source.owner).addPathSegment(source.repo).apply { segments.forEach { addPathSegment(it) } }.build()

private fun parseGithub(input: String): GithubSource {
    require(input.toByteArray(Charsets.UTF_8).size <= 2048 && input.none(Character::isISOControl)) { "Invalid repository source" }
    val source = input.trim().let { when {
        it.startsWith("git@github.com:") -> "https://github.com/" + it.removePrefix("git@github.com:")
        ':' !in it && it.split('/').size == 2 -> "https://github.com/$it"
        else -> it
    } }
    val uri = URI(source)
    require(uri.scheme == "https" && uri.host.equals("github.com", true) && uri.port in setOf(-1, 443) &&
        uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Only public GitHub HTTPS repositories are supported" }
    // Inspect decoded path before any URL normalization, including encoded slashes and dot segments.
    val path = URLDecoder.decode((uri.rawPath ?: "").replace("+", "%2B"), Charsets.UTF_8.name()).trim('/')
    require('\ufffd' !in path) { "Invalid UTF-8 in repository URL" }
    checkedPath(path, allowEmpty = false)
    val parts = path.split('/')
    require(parts.size >= 2) { "GitHub owner and repository required" }
    val owner = parts[0]
    val repo = parts[1].removeSuffix(".git")
    require(owner.isNotEmpty() && owner.all { it.isAscii() && (it.isLetterOrDigit() || it == '-') } && repo.isNotEmpty() &&
        repo.all { it.isAscii() && (it.isLetterOrDigit() || it in "-_.") }) { "Invalid GitHub repository" }
    if (parts.size == 2) return GithubSource(owner, repo, null, "")
    require(parts.size >= 4 && parts[2] in setOf("tree", "blob")) { "Invalid GitHub repository URL" }
    val ref = parts[3]
    var root = parts.drop(4).joinToString("/")
    if (parts[2] == "blob") {
        require(root == "SKILL.md" || root.endsWith("/SKILL.md")) { "GitHub blob must refer to SKILL.md" }
        root = root.removeSuffix("SKILL.md").trimEnd('/')
    }
    checkedPath(root)
    return GithubSource(owner, repo, ref, root)
}
private fun Char.isAscii() = code < 128

/** Shared executor; every call validates and pins public DNS, and receives only bounded bytes. */
private object GithubHttp : RemoteSkillHttp {
    private val dispatcher = Dispatcher().apply { maxRequests = 2; maxRequestsPerHost = 2 }
    private val base = OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .addNetworkInterceptor { chain -> chain.proceed(chain.request()).let { if (it.code == 503) it.newBuilder().removeHeader("Retry-After").build() else it } }
        .connectTimeout(10, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    override suspend fun get(url: HttpUrl, limit: Int): ByteArray {
        val addresses = withTimeout(5_000) { withContext(Dispatchers.IO) { InetAddress.getAllByName("api.github.com").toList() } }
        require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) { "GitHub DNS returned non-public addresses" }
        val dns = Dns { host -> if (host == "api.github.com") addresses else throw UnknownHostException("Unexpected host") }
        val accept = if (url.pathSegments.size == 6 && url.pathSegments[3] == "git" && url.pathSegments[4] == "blobs") "application/vnd.github.raw+json" else "application/vnd.github+json"
        val call = base.newBuilder().dns(dns).build().newCall(Request.Builder().url(url)
            .header("User-Agent", "Messenger-Skills-Import").header("Accept", accept).get().build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val data = response.use {
                            check(it.isSuccessful && it.code !in 300..399) { "GitHub HTTP ${it.code}" }
                            val source = it.body.source()
                            source.request(limit.toLong() + 1)
                            require(source.buffer.size <= limit) { "Remote response exceeds size limit" }
                            source.readByteArray()
                        }
                        if (continuation.isActive) continuation.resume(data)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
}
