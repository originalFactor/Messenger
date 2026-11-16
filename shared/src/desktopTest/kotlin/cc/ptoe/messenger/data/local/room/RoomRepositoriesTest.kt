/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0. See https://www.apache.org/licenses/LICENSE-2.0
 */
package cc.ptoe.messenger.data.local.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.repository.room.*
import cc.ptoe.messenger.domain.model.*
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomRepositoriesTest {
    @Test fun agentEditsAndClonePreserveUnsetSamplingValues(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-agent-sampling")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val repo = RoomAgentRepository(db)
            val seed = Agent("default", "Default", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1)
            repo.ensureDefaultAgent(seed)
            val original = checkNotNull(db.rows().agent("default"))
            db.rows().upsertAgents(listOf(original.copy(temperature = null, topP = null)))
            val displayed = checkNotNull(repo.getById("default").first())
            assertEquals(0.7f, displayed.temperature)
            assertEquals(1f, displayed.topP)
            repo.update(displayed.copy(name = "Edited"))
            assertNull(db.rows().agent("default")!!.temperature)
            assertNull(db.rows().agent("default")!!.topP)
            val cloned = checkNotNull(repo.clone("default"))
            assertNull(db.rows().agent(cloned.id)!!.temperature)
            assertNull(db.rows().agent(cloned.id)!!.topP)
            repo.update(displayed.copy(name = "Changed", temperature = 0.4f, topP = 0.8f))
            assertEquals(0.4f.toDouble(), db.rows().agent("default")!!.temperature)
            assertEquals(0.8f.toDouble(), db.rows().agent("default")!!.topP)
            db.rows().upsertAgents(listOf(db.rows().agent("default")!!.copy(temperature = 0.123456789, topP = 0.987654321)))
            repo.update(checkNotNull(repo.getById("default").first()).copy(name = "Renamed again"))
            assertEquals(0.123456789, db.rows().agent("default")!!.temperature)
            assertEquals(0.987654321, db.rows().agent("default")!!.topP)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun defaultAgentIsAtomicAndCannotBeDeletedOrDemoted(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-repository")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val agents = RoomAgentRepository(db)
            fun agent(id: String, default: Boolean = false) = Agent(id, id, systemPrompt = "prompt", defaultModelId = null, temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = default, createdAt = 1, updatedAt = 1)
            val seeded = agents.ensureDefaultAgent(agent("one", true))
            assertEquals("one", seeded.id)
            assertEquals("one", agents.ensureDefaultAgent(agent("ignored", true)).id)
            assertEquals("one", agents.getDefaultAgent().first()?.id)
            agents.insert(agent("two", true))
            assertEquals(listOf("two"), agents.getAll().first().filter { it.isDefault }.map { it.id })
            assertFailsWith<IllegalArgumentException> { agents.update(agent("two")) }
            agents.delete("two")
            assertEquals("two", agents.getDefaultAgent().first()?.id)
            assertEquals(2, agents.getAll().first().size)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun preservesHiddenFieldsAndReferentialIntegrity(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-repository")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val rows = db.rows()
            val providers = RoomProviderRepository(db)
            val models = RoomModelRepository(db)
            val projects = RoomProjectRepository(db)
            val agents = RoomAgentRepository(db)
            val acp = object : AcpConversationStore {
                override fun observeAll() = flowOf(emptyList<Conversation>())
                override suspend fun update(conversation: Conversation) = error("No ACP sessions in this test")
                override suspend fun delete(id: String) = error("No ACP sessions in this test")
            }
            val conversations = RoomConversationRepository(db, acp)
            val messages = RoomMessageRepository(db)
            providers.insert(Provider("p", "Provider", "https://example.test", "secret", nativeWebSearch = true, createdAt = 1, updatedAt = 1))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 100, null, null, null, null, "text", "text", false, false, false, false, 1, "metadata", "overrides")))
            models.update(models.getById("m").first()!!.copy(displayName = "Renamed"))
            assertEquals("metadata", rows.model("m")?.metadata)
            assertEquals("overrides", rows.model("m")?.metadataOverrides)
            rows.upsertProjects(listOf(ProjectRow("project", "old", "/work", "remote-device", 1, 2)))
            projects.update(Project("project", "new", "/work", 1, 3))
            assertEquals("remote-device", rows.project("project")?.deviceId)
            projects.insert(Project("local", "local", "/local", 1, 1))
            assertEquals(rows.getValue("device_id")?.value, rows.project("local")?.deviceId)
            assertTrue(!rows.project("local")?.deviceId.isNullOrBlank())
            rows.upsertProjects(listOf(ProjectRow("unknown", "unknown", "/unknown", null, 1, 1)))
            projects.update(Project("unknown", "updated", "/unknown", 1, 2))
            assertNull(rows.project("unknown")?.deviceId)
            agents.insert(Agent("agent", "Agent", systemPrompt = "", defaultModelId = null, temperature = 0.7f, topP = 1f, maxTokens = null, createdAt = 1, updatedAt = 1))
            conversations.insert(Conversation("c", "Chat", "p", "agent", projectId = "project", createdAt = 1, updatedAt = 1, lastMessage = null))
            rows.upsertMessages(listOf(MessageRow("msg", "c", "user", "hello", """[{"type":"future","value":1}]""", 1, "sent", null)))
            messages.update(messages.getByConversationId("c").first().single().copy(content = "changed"))
            assertTrue(rows.message("msg")!!.partsJson!!.contains("future"))
            projects.delete("project")
            assertNull(conversations.getById("c").first()?.projectId)
            providers.delete("p")
            assertNull(models.getById("m").first())
            assertEquals("changed", messages.getByConversationId("c").first().single().content)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
    @Test fun offlineChangesRemainQueuedForTheBoundDatasetAfterSessionExpires(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-offline-queue")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val sync = db.cloudSync()
            val user = cc.ptoe.messenger.data.cloud.CloudUser(id = "account")
            val first = cc.ptoe.messenger.data.cloud.SyncSession("account", "one")
            val credentials = cc.ptoe.messenger.data.cloud.StoredCloudSession(first, "messenger_session=token", "https://example.test/a", user)
            sync.saveCredentials(credentials)
            val agents = RoomAgentRepository(db)
            agents.ensureDefaultAgent(Agent("default", "Default", systemPrompt = "", defaultModelId = null, temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            val providers = RoomProviderRepository(db)
            val provider = Provider("p", "Original", "https://provider.test", "key", createdAt = 1, updatedAt = 1)
            providers.insert(provider)
            sync.acknowledge(first, sync.pending(first).single())
            sync.clearCredentialsIfCurrent(first)
            providers.update(provider.copy(name = "Offline"))
            val other = credentials.copy(session = first.copy(generation = "other"), host = "https://example.test/b")
            assertFailsWith<IllegalStateException> { sync.saveCredentials(other) }
            val next = first.copy(generation = "two")
            sync.saveCredentials(credentials.copy(session = next))
            assertEquals("p", sync.pending(next).single().id)
            val cloudProvider = kotlinx.serialization.json.Json.parseToJsonElement("""{"_id":"p","name":"Remote","models":[]}""").jsonObject
            sync.applyPull(next, cc.ptoe.messenger.data.cloud.PulledCloudDelta(mapOf(cc.ptoe.messenger.data.cloud.SyncCollection.PROVIDER to listOf(cloudProvider)), 1), false)
            assertEquals("Offline", db.rows().provider("p")!!.name)
            sync.acknowledge(next, sync.pending(next).single())
            sync.clearCredentialsIfCurrent(next)
            providers.delete("p")
            val third = next.copy(generation = "three")
            sync.saveCredentials(credentials.copy(session = third))
            assertEquals(cc.ptoe.messenger.data.cloud.SyncMutation.DELETE, sync.pending(third).single().mutation)
            sync.applyPull(third, cc.ptoe.messenger.data.cloud.PulledCloudDelta(mapOf(cc.ptoe.messenger.data.cloud.SyncCollection.PROVIDER to listOf(cloudProvider)), 2), false)
            assertNull(db.rows().provider("p"))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

}

class RoomAcpConversationStoreTest {
    @Test fun preservesSessionFieldsAndGuardsActiveMutations(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-acp")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", """{"acp:one":{"id":"acp:one","title":"old","profile":"codex","project":"p","updated_at":4,"cwd":"/private","agent_session":"remote","unknown":42,"messages":[{"text":"hello"}]}}""")))
            var active = false
            val gate = object : AcpSessionMutationGate {
                override suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T): T {
                    check(!active) { "turn in progress" }
                    return action()
                }
            }
            val store = RoomAcpConversationStore(db, gate)
            val first = store.observeAll().first().single()
            assertEquals("acp:codex", first.agentId)
            assertEquals("hello", first.lastMessage)
            assertFailsWith<IllegalArgumentException> { store.update(first.copy(projectId = "other")) }
            active = true
            assertFailsWith<IllegalStateException> { store.delete(first.id) }
            active = false
            store.update(first.copy(title = "new"))
            assertEquals("new", store.observeAll().first().single().title)
            assertTrue(db.rows().getValue("local_acp_sessions")!!.value.contains("\"unknown\":42"))
            store.delete(first.id)
            assertTrue(store.observeAll().first().isEmpty())
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}

class RoomCloudAtomicityTest {
    @Test fun failedEnqueueRollsBackTheBusinessWrite(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-atomic")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val sync = db.cloudSync()
            val session = cc.ptoe.messenger.data.cloud.SyncSession("account", "generation")
            sync.activate(session)
            db.rows().upsertValues(listOf(KeyValueRow("kotlin_sync_revision", Long.MAX_VALUE.toString())))
            val repo = RoomProviderRepository(db)
            val provider = Provider("p", "Provider", "https://example.test", "key", createdAt = 1, updatedAt = 1)
            assertFailsWith<IllegalStateException> { repo.insert(provider) }
            assertNull(repo.getById("p").first())
            assertTrue(sync.pending(session).isEmpty())
            db.rows().upsertValues(listOf(KeyValueRow("kotlin_sync_revision", "0")))
            repo.insert(provider)
            assertEquals("p", repo.getById("p").first()?.id)
            val pending = sync.pending(session).single()
            assertEquals(cc.ptoe.messenger.data.cloud.SyncCollection.PROVIDER, pending.collection)
            assertEquals(cc.ptoe.messenger.data.cloud.SyncMutation.UPSERT, pending.mutation)
            assertEquals("p", pending.id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
