/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.artifact.ArtifactUiConsent
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class KotlinRoomArtifactSliceTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun trustedUiBindsTheSameRoomArtifactsAndStopsBeforeOwnerCloses(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-artifact-slice")
        val root = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        val prefs = MemoryPreferences()
        val consentCalls = mutableListOf<String>()
        val consent = object : ArtifactUiConsent {
            override suspend fun authorizeRevision(id: String, revision: Long) { consentCalls.add("run:$revision") }
            override suspend fun grantDomains(id: String, revision: Long, domains: List<String>) { consentCalls.add("network:$revision:${domains.joinToString()}") }
        }
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("default", "Default", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val backend = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs),
                root, dir.resolve("avatars"), dir.resolve("images"), "http://127.0.0.1:1", "http://127.0.0.1:1/models",
                artifactUi = IsolatedArtifactUiConfig(consent))
            val host = checkNotNull(backend.artifactUiHost)
            val created = Json.parseToJsonElement(backend.bridge.learningRequest("POST", "/artifacts",
                """{"title":"App","files":{"index.html":"same"},"requestedDomains":["example.com"]}""")).jsonObject
            val id = created.getValue("id").jsonPrimitive.content
            assertFalse(host.isRevisionAuthorized(id, 1))
            assertFailsWith<IllegalStateException> { host.open(id, 1) }
            host.authorizeRevision(id, 1)
            assertTrue(host.isRevisionAuthorized(id, 1))
            host.grantDomains(id, 1, listOf("example.com"))
            val old = host.open(id, 1)
            val reply = CompletableDeferred<String>()
            old.onMessage("""{"id":"request","method":"setState","params":{"state":"saved"}}""") { reply.complete(it) }
            val result = Json.parseToJsonElement(withTimeout(5_000) { reply.await() }).jsonObject
            assertEquals("saved", result.getValue("data").jsonObject.getValue("state").jsonPrimitive.content)
            val persisted = Json.parseToJsonElement(backend.bridge.learningRequest("GET", "/artifacts/$id")).jsonObject
            assertEquals("saved", persisted.getValue("state").jsonPrimitive.content)
            assertFailsWith<IllegalStateException> { host.resetState(id, 1, 999) }
            host.resetState(id, 1, 0)
            assertEquals(JsonNull, Json.parseToJsonElement(backend.bridge.learningRequest("GET", "/artifacts/$id")).jsonObject.getValue("state"))
            val next = host.restart(old)
            assertNotSame(old.identity, next.identity)
            assertFalse(old.active.value)
            assertTrue(next.active.value)
            assertEquals(listOf("run:1", "network:1:example.com"), consentCalls)
            lifecycle.stopAndAwait()
            assertFalse(next.active.value)
            assertFailsWith<IllegalStateException> { host.open(id, 1) }
            assertFailsWith<IllegalStateException> { backend.assetGate.withExclusiveAssets { } }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
