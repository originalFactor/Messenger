/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.skill.RemoteSkillHttp
import cc.ptoe.messenger.data.local.skill.RemoteSkillImport
import cc.ptoe.messenger.data.local.skill.RemoteSkillLimiter
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinRoomRemoteSkillIntegrationTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private suspend fun <T> withBackend(remote: RemoteSkillImport, block: suspend (KotlinRoomBackendSlice, LocalDatabase, RoomManagedShutdown) -> T): T {
        val dir = Files.createTempDirectory("room-remote-skills")
        val assets = Files.createDirectory(dir.resolve("assets"))
        if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(assets, PosixFilePermissions.fromString("rwx------"))
        }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        val prefs = MemoryPreferences()
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val backend = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs),
                assets, dir.resolve("avatars"), dir.resolve("chat-images"), "http://127.0.0.1:1", remoteSkills = remote)
            return block(backend, db, lifecycle)
        } finally {
            if (lifecycle.state.value == RoomShutdownState.Running) lifecycle.stopAndAwait()
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    private fun gitBlob(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").run {
        update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test fun discoveryAndPreviewPinCommitBeforeExplicitRoomSave(): Unit = runBlocking {
        val commit = "a".repeat(40)
        val doc = "---\nname: study\ndescription: Local fixture\n---\nreviewed".encodeToByteArray()
        val binary = byteArrayOf(0, -1, 8, 42)
        fun entry(path: String, bytes: ByteArray) = JsonObject(mapOf(
            "path" to JsonPrimitive(path), "type" to JsonPrimitive("blob"), "mode" to JsonPrimitive("100644"), "sha" to JsonPrimitive(gitBlob(bytes))))
        val tree = JsonObject(mapOf("truncated" to JsonPrimitive(false), "tree" to JsonArray(listOf(entry("SKILL.md", doc), entry("icon.bin", binary)))))
        val calls = ConcurrentLinkedQueue<String>()
        val http = RemoteSkillHttp { url, _ ->
            val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            calls.add(path)
            when (path) {
                "/repos/owner/repo/commits/main", "/repos/owner/repo/commits/$commit" -> """{"sha":"$commit"}""".encodeToByteArray()
                "/repos/owner/repo/git/trees/$commit?recursive=1" -> tree.toString().encodeToByteArray()
                "/repos/owner/repo/git/blobs/${gitBlob(doc)}" -> doc
                "/repos/owner/repo/git/blobs/${gitBlob(binary)}" -> binary
                else -> error("Unexpected fixture URL: $path")
            }
        }
        withBackend(RemoteSkillImport(http, RemoteSkillLimiter())) { backend, _, lifecycle ->
            val catalog = Json.parseToJsonElement(backend.bridge.discoverRemoteSkills("""{"source":"owner/repo","ref":"main"}""")).jsonObject
            assertEquals(commit, catalog.getValue("ref").jsonPrimitive.content)
            assertFalse(backend.bridge.listSkills().contains("study"))
            val beforePreview = calls.size
            val preview = Json.parseToJsonElement(backend.bridge.previewRemoteSkill("""{"source":"owner/repo","path":"","ref":"$commit"}""")).jsonObject
            assertEquals("/repos/owner/repo/commits/$commit", calls.toList()[beforePreview])
            assertEquals("study", preview.getValue("name").jsonPrimitive.content)
            assertContentEquals(binary, Base64.getDecoder().decode(preview.getValue("binaryFiles").jsonObject.getValue("icon.bin").jsonPrimitive.content))
            assertFalse(backend.bridge.listSkills().contains("study"))
            val write = buildJsonObject { put("files", preview.getValue("files")); put("binaryFiles", preview.getValue("binaryFiles")); put("enabled", true) }
            backend.bridge.saveSkill("study", write.toString())
            assertEquals(preview.getValue("files"), Json.parseToJsonElement(backend.bridge.getSkill("study")).jsonObject.getValue("files"))
            assertEquals(preview.getValue("binaryFiles"), Json.parseToJsonElement(backend.bridge.getSkill("study")).jsonObject.getValue("binaryFiles"))
            val requests = calls.size
            assertFailsWith<IllegalArgumentException> { backend.bridge.discoverRemoteSkills("""{"source":"https://example.com/repo.git"}""") }
            assertEquals(requests, calls.size)
            lifecycle.stopAndAwait()
            assertFailsWith<IllegalStateException> { backend.bridge.discoverRemoteSkills("""{"source":"owner/repo"}""") }
        }
    }

    @Test fun shutdownCancelsDiscoveryAndWaitsForTransportCleanup(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val http = RemoteSkillHttp { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); releaseCleanup.await() } }
        }
        withBackend(RemoteSkillImport(http, RemoteSkillLimiter())) { backend, db, lifecycle ->
            try {
                val request = async { backend.bridge.discoverRemoteSkills("""{"source":"owner/repo"}""") }
                withTimeout(5_000) { entered.await() }
                val shutdown = async { lifecycle.stopAndAwait() }
                withTimeout(5_000) { cleanupEntered.await() }
                assertFalse(shutdown.isCompleted)
                assertEquals(1, db.rows().defaultAgents().size)
                releaseCleanup.complete(Unit)
                withTimeout(5_000) { shutdown.await() }
                assertFailsWith<CancellationException> { request.await() }
                assertTrue(lifecycle.state.value == RoomShutdownState.Stopped)
            } finally { releaseCleanup.complete(Unit) }
        }
    }
}
