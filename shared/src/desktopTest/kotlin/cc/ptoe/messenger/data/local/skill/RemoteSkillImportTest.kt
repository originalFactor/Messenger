/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.skill

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.model.RemoteSkillRequest
import java.nio.file.Files
import java.io.IOException
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.Callback
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteSkillImportTest {
    @Test fun cancelledHttpCallWaitsForCallbackCleanup(): Unit = runBlocking {
        val callback = CompletableDeferred<Callback>()
        val cancelled = CompletableDeferred<Unit>()
        val call = Call::class.java.cast(Proxy.newProxyInstance(Call::class.java.classLoader, arrayOf(Call::class.java)) { _, method, args ->
            when (method.name) {
                "enqueue" -> { callback.complete(args[0] as Callback); null }
                "cancel" -> { cancelled.complete(Unit); null }
                else -> error("Unexpected call: ${method.name}")
            }
        })
        val request = async { awaitRemoteSkillCall(call, 1024) }
        val registered = withTimeout(5_000) { callback.await() }
        request.cancel()
        withTimeout(5_000) { cancelled.await() }
        assertFalse(request.isCompleted)
        registered.onFailure(call, IOException("cancelled"))
        withTimeout(5_000) { request.join() }
        assertTrue(request.isCancelled)
    }
    private val commit = "a".repeat(40)
    private val skillsTree = "b".repeat(40)
    private val rootTree = "c".repeat(40)
    private val doc = "---\nname: review\ndescription: Review files\n---\nInstructions".toByteArray()
    private val nested = "---\nname: nested\ndescription: Other skill\n---\nother".toByteArray()
    private val broken = "---\nname: broken\ndescription: [invalid\n---\nbody".toByteArray()
    private val script = "touch NEVER".toByteArray()
    private val binary = byteArrayOf(0, -1, -128, 42)

    private fun id(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").run {
        update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun tree(vararg entries: JsonObject, truncated: Boolean = false): ByteArray =
        JsonObject(mapOf("truncated" to JsonPrimitive(truncated), "tree" to JsonArray(entries.toList()))).toString().toByteArray()
    private fun entry(path: String, type: String, mode: String, sha: String): JsonObject = JsonObject(mapOf(
        "path" to JsonPrimitive(path), "type" to JsonPrimitive(type), "mode" to JsonPrimitive(mode), "sha" to JsonPrimitive(sha),
    ))
    private class Http : RemoteSkillHttp {
        val calls = mutableListOf<String>()
        val responses = mutableMapOf<String, ByteArray>()
        val failures = mutableMapOf<String, Int>()
        override suspend fun get(url: HttpUrl, limit: Int): ByteArray {
            assertEquals("api.github.com", url.host)
            val key = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            calls += key
            failures[key]?.let { throw RemoteSkillHttpFailure(it) }
            if ("/git/ref/" in key && key !in responses) throw RemoteSkillHttpFailure(404)
            return responses[key] ?: error("Unexpected request $key")
        }
    }
    private fun ref(kind: String, name: String, id: String, type: String = "commit"): ByteArray =
        """{"ref":"refs/$kind/$name","object":{"type":"$type","sha":"$id"}}""".toByteArray()
    private fun fixture(): Http = Http().apply {
        val prefix = "/repos/owner/repo"
        responses["$prefix/commits/main"] = """{"sha":"$commit"}""".toByteArray()
        responses["$prefix/commits/$commit"] = """{"sha":"$commit"}""".toByteArray()
        responses["$prefix/git/trees/$commit"] = tree(entry("skills", "tree", "040000", skillsTree))
        responses["$prefix/git/trees/$skillsTree"] = tree(entry("review", "tree", "040000", rootTree))
        responses["$prefix/git/trees/$rootTree?recursive=1"] = tree(
            entry("SKILL.md", "blob", "100644", id(doc)),
            entry("scripts/example.sh", "blob", "100755", id(script)),
            entry("assets/icon.bin", "blob", "100644", id(binary)),
            entry("nested/SKILL.md", "blob", "100644", id(nested)),
            entry("nested/private.txt", "blob", "100644", id(script)),
            entry("broken/SKILL.md", "blob", "100644", id(broken)),
        )
        for (bytes in listOf(doc, nested, broken, script, binary)) responses["$prefix/git/blobs/${id(bytes)}"] = bytes
    }

    @Test fun discoveryPinsCommitPreviewKeepsOnlySelectedPackageAndImportNeverOverwrites(): Unit = runBlocking {
        val fake = fixture()
        val remote = RemoteSkillImport(fake, RemoteSkillLimiter())
        val discovery = remote.discover(RemoteSkillRequest("owner/repo", "skills/review", "main"))
        assertEquals(commit, discovery.revision)
        assertEquals("https://github.com/owner/repo.git", discovery.source)
        assertEquals(setOf("review", "nested"), discovery.items.map { it.name }.toSet())
        assertEquals(listOf("skills/review/broken"), discovery.warnings.map { it.path })
        fake.calls.clear()
        val pinned = RemoteSkillRequest(discovery.source, "skills/review", discovery.revision)
        val preview = remote.preview(pinned)
        assertTrue(fake.calls.first().endsWith("/commits/$commit"))
        assertFalse(fake.calls.any { it.endsWith("/commits/main") })
        assertEquals("review", preview.detail.summary.metadata.name)
        assertEquals("touch NEVER", preview.detail.packageData.files["scripts/example.sh"])
        assertContentEquals(binary, java.util.Base64.getDecoder().decode(preview.detail.packageData.binaryFiles["assets/icon.bin"]))
        assertFalse(preview.detail.packageData.files.keys.any { it.startsWith("nested/") || it.startsWith("broken/") })
        val approved = preview.detail.packageData.copy(files = preview.detail.packageData.files.toMap())
        (preview.detail.packageData.files as MutableMap)["scripts/example.sh"] = "changed after preview"
        val dir = Files.createTempDirectory("remote-skill-import")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val store = RoomSkillStore(db)
            remote.install(preview, store)
            assertEquals(approved, store.get("review").packageData)
            assertFailsWith<IllegalStateException> { remote.install(preview, store) }
            assertEquals(approved, store.get("review").packageData)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun unsafeSourcesMovingPreviewAndUnsupportedGitFailBeforeNetwork(): Unit = runBlocking {
        val fake = fixture()
        val remote = RemoteSkillImport(fake, RemoteSkillLimiter())
        for (source in listOf("http://github.com/a/b", "https://user:pass@github.com/a/b", "https://github.com/a/b?token=x",
            "https://github.com/a/b/tree/main/%2e%2e/x", "https://github.com/a/b/tree/main/a%5Cb", "file:///tmp/repo",
            "https://example.com/group/repo.git", "https://127.0.0.1/repo.git",
            "https://[::1]/repo.git", "https://10.0.0.1/repo.git", "https://169.254.169.254/latest/meta-data",
            "https://localhost/repo.git", "https://github.com.example.com/a/b",
            "https://github.com@127.0.0.1/a/b", "https://github.com:8443/a/b",
            "https://github.com/a/.git")) {
            assertFails(source) { remote.discover(RemoteSkillRequest(source)) }
        }
        assertFails { remote.preview(RemoteSkillRequest("owner/repo", "skills/review", "main")) }
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun githubUrlUsesLongestExactBranchAndPinsPreview(): Unit = runBlocking {
        val fake = fixture()
        val prefix = "/repos/owner/repo/git/ref"
        fake.responses["$prefix/heads/feature"] = ref("heads", "feature", "d".repeat(40))
        fake.responses["$prefix/heads/feature/slash"] = ref("heads", "feature/slash", commit)
        fake.responses["$prefix/tags/feature/slash"] = ref("tags", "feature/slash", "e".repeat(40))
        val remote = RemoteSkillImport(fake, RemoteSkillLimiter())
        val catalog = remote.discover(RemoteSkillRequest("https://github.com/owner/repo/tree/feature%2Fslash/skills/review"))
        assertEquals(commit, catalog.revision)
        assertEquals("skills/review", catalog.items.first { it.name == "review" }.path)
        assertTrue("$prefix/heads/feature/slash/skills/review" in fake.calls)
        assertTrue("$prefix/heads/feature/slash" in fake.calls)
        assertFalse("$prefix/tags/feature/slash" in fake.calls)
        fake.calls.clear()
        val preview = remote.preview(RemoteSkillRequest(catalog.source, "skills/review", catalog.revision))
        assertContentEquals(binary, java.util.Base64.getDecoder().decode(preview.detail.packageData.binaryFiles["assets/icon.bin"]))
        assertTrue(fake.calls.first().endsWith("/commits/$commit"))
        assertFalse(fake.calls.any { "/git/ref/" in it })
    }

    @Test fun githubBlobUrlPeelsAnnotatedSlashedTagAndExplicitSelectionSkipsRefSearch(): Unit = runBlocking {
        val fake = fixture()
        val tagId = "d".repeat(40)
        fake.responses["/repos/owner/repo/git/ref/tags/v1/release"] = ref("tags", "v1/release", tagId, "tag")
        fake.responses["/repos/owner/repo/git/tags/$tagId"] = """{"sha":"$tagId","object":{"type":"commit","sha":"$commit"}}""".toByteArray()
        val remote = RemoteSkillImport(fake, RemoteSkillLimiter())
        val url = "https://github.com/owner/repo/blob/v1/release/skills/review/SKILL.md"
        val catalog = remote.discover(RemoteSkillRequest(url))
        assertEquals(commit, catalog.revision)
        assertEquals("skills/review", catalog.items.first { it.name == "review" }.path)
        assertTrue("/repos/owner/repo/git/tags/$tagId" in fake.calls)
        fake.calls.clear()
        val explicit = remote.discover(RemoteSkillRequest(url, "skills/review", "main"))
        assertEquals(commit, explicit.revision)
        assertFalse(fake.calls.any { "/git/ref/" in it || "/git/tags/" in it })
        assertTrue(fake.calls.first().endsWith("/commits/main"))
    }

    @Test fun githubRefLookupFailureAndMalformedTagFailWithoutChoosingShorterRef(): Unit = runBlocking {
        val fake = fixture()
        val prefix = "/repos/owner/repo/git/ref"
        fake.responses["$prefix/heads/feature"] = ref("heads", "feature", commit)
        fake.failures["$prefix/heads/feature/slash/skills/review"] = 403
        val remote = RemoteSkillImport(fake, RemoteSkillLimiter())
        assertFailsWith<RemoteSkillHttpFailure> {
            remote.discover(RemoteSkillRequest("https://github.com/owner/repo/tree/feature/slash/skills/review"))
        }
        assertFalse("$prefix/heads/feature" in fake.calls)
        fake.failures.clear()
        fake.calls.clear()
        val tagId = "d".repeat(40)
        fake.responses["$prefix/tags/v1"] = ref("tags", "v1", tagId, "tag")
        fake.responses["/repos/owner/repo/git/tags/$tagId"] = """{"sha":"$tagId","object":{"type":"tag","sha":"$tagId"}}""".toByteArray()
        assertFails { remote.discover(RemoteSkillRequest("https://github.com/owner/repo/tree/v1/skills/review")) }
        assertFalse(fake.calls.any { "/git/trees/" in it })
    }

    @Test fun rejectsTruncatedTreesSymlinksTraversalTamperedBlobsAndUnboundedResponses(): Unit = runBlocking {
        val fake = fixture()
        val remote = RemoteSkillImport(fake, RemoteSkillLimiter())
        val path = "/repos/owner/repo/git/trees/$rootTree?recursive=1"
        val original = fake.responses.getValue(path)
        val request = RemoteSkillRequest("owner/repo", "skills/review", commit)
        fake.responses[path] = tree(truncated = true)
        assertFails { remote.preview(request) }
        fake.responses[path] = tree(entry("../SKILL.md", "blob", "100644", id(doc)))
        assertFails { remote.preview(request) }
        fake.responses[path] = tree(entry("SKILL.md", "blob", "120000", id(doc)))
        assertFails { remote.preview(request) }
        fake.responses[path] = tree(entry("SKILL.md", "blob", "100644", id(doc)), entry("assets/link", "commit", "160000", id(binary)))
        assertFails { remote.preview(request) }
        fake.responses[path] = original
        fake.responses["/repos/owner/repo/git/blobs/${id(doc)}"] = "different".toByteArray()
        assertFails { remote.preview(request) }
        fake.responses["/repos/owner/repo/git/blobs/${id(doc)}"] = ByteArray(1024 * 1024 + 1)
        assertFails { remote.preview(request) }
        fake.responses["/repos/owner/repo/git/blobs/${id(doc)}"] = doc
        val largeFiles = (0..3).map { n -> ByteArray(1024 * 1024) { n.toByte() } }
        fake.responses[path] = tree(*(listOf(entry("SKILL.md", "blob", "100644", id(doc))) + largeFiles.mapIndexed { n, bytes ->
            fake.responses["/repos/owner/repo/git/blobs/${id(bytes)}"] = bytes
            entry("asset$n", "blob", "100644", id(bytes))
        }).toTypedArray())
        assertFails { remote.preview(request) }
    }
}
