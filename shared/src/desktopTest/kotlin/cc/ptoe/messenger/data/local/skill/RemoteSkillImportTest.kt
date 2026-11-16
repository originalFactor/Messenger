/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.skill

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.model.RemoteSkillRequest
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteSkillImportTest {
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
        override suspend fun get(url: HttpUrl, limit: Int): ByteArray {
            assertEquals("api.github.com", url.host)
            val key = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            calls += key
            return responses[key] ?: error("Unexpected request $key")
        }
    }
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
        val remote = RemoteSkillImport(fake)
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
        val remote = RemoteSkillImport(fake)
        for (source in listOf("http://github.com/a/b", "https://user:pass@github.com/a/b", "https://github.com/a/b?token=x",
            "https://github.com/a/b/tree/main/%2e%2e/x", "https://github.com/a/b/tree/main/a%5Cb", "file:///tmp/repo",
            "https://example.com/group/repo.git", "https://github.com/a/.git")) {
            assertFails(source) { remote.discover(RemoteSkillRequest(source)) }
        }
        assertFails { remote.preview(RemoteSkillRequest("owner/repo", "skills/review", "main")) }
        assertFails { remote.discover(RemoteSkillRequest("https://github.com/owner/repo/tree/feature/slash/skills/review")) }
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun rejectsTruncatedTreesSymlinksTraversalTamperedBlobsAndUnboundedResponses(): Unit = runBlocking {
        val fake = fixture()
        val remote = RemoteSkillImport(fake)
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
