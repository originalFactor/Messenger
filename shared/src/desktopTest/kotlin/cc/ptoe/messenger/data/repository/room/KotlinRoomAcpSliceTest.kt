/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.chat.RuntimeWorkspaceFence
import cc.ptoe.messenger.data.local.acp.*
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProjectRow
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Project
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KotlinRoomAcpSliceTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(state.value).also { state.value = it }
    }
    private class Fence : AcpConfigurationFence {
        private val mutex = Mutex()
        override suspend fun acquire(binding: AcpWorkerBinding): AcpConfigurationLease {
            mutex.lock()
            return AcpConfigurationLease { mutex.unlock() }
        }
    }
    private class Transport : AcpLineTransport {
        private val output = Channel<String>(Channel.UNLIMITED)
        private var promptId: JsonElement? = null
        var stopped = false
        var loads = 0
        override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) = Unit
        override suspend fun readLine(): String? = output.receiveCatching().getOrNull()
        override suspend fun sendLine(line: String) {
            val packet = Json.parseToJsonElement(line).jsonObject
            val method = packet["method"]?.jsonPrimitive?.contentOrNull
            val id = packet["id"]
            suspend fun reply(result: JsonObject) {
                output.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", checkNotNull(id)); put("result", result) }.toString())
            }
            when (method) {
                "initialize" -> reply(buildJsonObject {
                    put("protocolVersion", 1)
                    put("agentCapabilities", buildJsonObject { put("loadSession", true); put("mcpCapabilities", JsonObject(emptyMap())) })
                })
                "session/new" -> reply(buildJsonObject { put("sessionId", "worker-session") })
                "session/load" -> { loads++; reply(JsonObject(emptyMap())) }
                "session/prompt" -> {
                    promptId = id
                    output.send(buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", "permission-1"); put("method", "session/request_permission")
                        put("params", buildJsonObject {
                            put("sessionId", "worker-session")
                            put("toolCall", buildJsonObject { put("title", "Read file") })
                            put("options", buildJsonArray { add(buildJsonObject { put("optionId", "allow"); put("name", "Allow"); put("kind", "allow_once") }) })
                        })
                    }.toString())
                }
                null -> if (id?.jsonPrimitive?.contentOrNull == "permission-1") {
                    output.send(buildJsonObject {
                        put("jsonrpc", "2.0"); put("method", "session/update")
                        put("params", buildJsonObject {
                            put("sessionId", "worker-session")
                            put("update", buildJsonObject { put("sessionUpdate", "agent_message_chunk"); put("content", buildJsonObject { put("type", "text"); put("text", "answer") }) })
                        })
                    }.toString())
                    output.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", checkNotNull(promptId)); put("result", buildJsonObject { put("stopReason", "end_turn") }) }.toString())
                }
                else -> Unit
            }
        }
        override suspend fun stopAndAwait() { stopped = true; output.close() }
    }

    @Test fun sliceCreatesPromptsApprovesAndReplaysAcpWithoutRust(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-acp-slice")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }; lifecycle.enter()
        val workspace = Files.createDirectory(dir.resolve("project")).toString()
        val assetRoot = Files.createDirectory(dir.resolve("assets"))
        val prefs = MemoryPreferences()
        val transports = Collections.synchronizedList(mutableListOf<Transport>())
        val workerConfig = AcpWorkerConfiguration("fake", emptyList(), emptyMap(), JsonArray(emptyList()), false, emptySet())
        try {
            val defaultAgent = RoomAgentRepository(db).ensureDefaultAgent(Agent("default", "Agent", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            db.rows().upsertProjects(listOf(ProjectRow("project", "Project", workspace, "device", 1, 1)))
            db.rows().upsertValues(listOf(KeyValueRow("device_id", "device"), KeyValueRow("device_control_config",
                """{"agents":[{"id":"agent","name":"Agent","enabled":true,"command":"fake","args":[],"env":{}}]}""")))
            val id = "acp:${UUID.randomUUID()}"
            val draft = AcpWorkerBinding(id, "agent", "project", workspace, "", false)
            val source = AcpSessionConfigurationSource { session ->
                val root = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject
                val record = root.getValue(session).jsonObject
                draft.copy(conversationId = session, agentSessionId = record.getValue("agent_session").jsonPrimitive.content) to workerConfig
            }
            val config = IsolatedAcpConfig(dir.resolve("room.db"), AcpPriorOwnerBarrier { }, InProcessAcpConfigurationFence(), source,
                AcpNewSessionSource { _, _ -> AcpNewSessionDraft(draft, workerConfig, "Agent") }, AcpPublishedMcpCatalog { emptyList() }, { Transport().also { transports.add(it) } })
            val backend = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs), assetRoot,
                dir.resolve("avatars"), dir.resolve("images"), acpConfig = config)
            val provider = Provider("provider", "Provider", "https://provider.test", "key", createdAt = 1, updatedAt = 1)
            val model = ChatModel("model", "provider", "model", "Model", true, createdAt = 1)
            backend.providers.insert(provider)
            backend.models.insert(model)
            val bridge = backend.bridge
            db.rows().upsertValues(listOf(
                KeyValueRow("kotlin_sync_session", "[\"account\",\"one\"]"),
                KeyValueRow("cloud_session", "messenger_session=first"),
                KeyValueRow("cloud_session_host", "https://cloud.example"),
                KeyValueRow("cloud_user_json", "{\"id\":\"account\"}"),
            ))
            val enableRemote = bridge.controlSettings()
            bridge.saveControlSettings(JsonObject(enableRemote + ("enabled" to JsonPrimitive(true))))
            assertTrue(db.rows().getValue("device_control_grant") != null)
            var catalog: JsonObject? = null
            bridge.controlCommand(UUID.randomUUID().toString(), buildJsonObject { put("action", "catalog") }) { catalog = it }
            assertTrue(checkNotNull(catalog).getValue("agents").jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == "acp:agent" })
            var created: JsonObject? = null
            bridge.controlCommand(UUID.randomUUID().toString(), buildJsonObject { put("action", "create"); put("agentId", "acp:agent"); put("projectId", "project") }) { created = it }
            assertEquals(id, checkNotNull(created).getValue("sessionId").jsonPrimitive.content)
            assertEquals(1, transports.size)
            assertTrue(transports.single().stopped)
            val requestId = UUID.randomUUID().toString()
            bridge.startBackgroundAcp(requestId, buildJsonObject { put("action", "prompt"); put("sessionId", id)
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "hello") }) }) })
            val permission = withTimeout(5_000) {
                var found: JsonObject? = null
                while (found == null) {
                    val batch = bridge.backgroundAcpEvents(requestId, 0)
                    found = batch.getValue("events").jsonArray.map { it.jsonObject }
                        .firstOrNull { it["type"]?.jsonPrimitive?.content == "Permission" }
                    check(found != null || !batch.getValue("done").jsonPrimitive.boolean) { "ACP prompt ended before requesting permission: $batch" }
                    if (found == null) delay(10)
                }
                checkNotNull(found)
            }
            assertEquals(2, transports.size)
            assertEquals(1, transports[1].loads)
            assertTrue(bridge.hasActiveTasks())
            assertTrue(bridge.supportsAcpReplaySnapshots)
            assertTrue(bridge.supportsAcpReplayDeletion)
            // Invalid unrelated edits cannot stop an explicit remote-control revocation.
            val invalidName = bridge.controlSettings()
            assertFailsWith<IllegalArgumentException> {
                bridge.saveControlSettings(JsonObject(invalidName + mapOf("enabled" to JsonPrimitive(false), "name" to JsonPrimitive("x".repeat(121)))))
            }
            assertEquals(null, db.rows().getValue("device_control_grant"))
            val restoreRemote = bridge.controlSettings()
            bridge.saveControlSettings(JsonObject(restoreRemote + ("enabled" to JsonPrimitive(true))))
            val invalidProfile = bridge.controlSettings()
            val invalidAgents = JsonArray(invalidProfile.getValue("agents").jsonArray.mapIndexed { index, profile ->
                if (index == 0) JsonObject(profile.jsonObject + ("command" to JsonPrimitive(""))) else profile
            })
            assertFailsWith<IllegalArgumentException> {
                bridge.saveControlSettings(JsonObject(invalidProfile + mapOf("enabled" to JsonPrimitive(false), "agents" to invalidAgents)))
            }
            assertEquals(null, db.rows().getValue("device_control_grant"))
            val enableAfterInvalid = bridge.controlSettings()
            bridge.saveControlSettings(JsonObject(enableAfterInvalid + ("enabled" to JsonPrimitive(true))))
            fun changedProfile(settings: JsonObject): JsonArray = JsonArray(settings.getValue("agents").jsonArray.mapIndexed { index, profile ->
                if (index == 0) JsonObject(profile.jsonObject + ("description" to JsonPrimitive("changed profile"))) else profile
            })
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { backend.providers.update(provider.copy(name = "cancelled")) }
            assertFalse(cancelled.isCompleted)
            cancelled.cancelAndJoin()
            assertEquals("Provider", db.rows().provider("provider")?.name)
            val afterCancellation = assertFailsWith<IllegalStateException> {
                checkNotNull(backend.acpOwner).start(UUID.randomUUID().toString(), id, buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "later") }) })
            }
            assertFalse(afterCancellation.message.orEmpty().contains("configuration is being changed"))
            val disabling = bridge.controlSettings()
            val originalProfiles = disabling.getValue("agents")
            val pendingDisable = async {
                runCatching { bridge.saveControlSettings(JsonObject(disabling + mapOf(
                    "enabled" to JsonPrimitive(false), "agents" to changedProfile(disabling)))) }
            }
            withTimeout(5_000) { while (db.rows().getValue("device_control_grant") != null) delay(10) }
            assertFalse(pendingDisable.isCompleted)
            assertEquals(originalProfiles, bridge.controlSettings().getValue("agents"))
            val reenable = bridge.controlSettings()
            bridge.saveControlSettings(JsonObject(reenable + ("enabled" to JsonPrimitive(true))))
            val enabling = bridge.controlSettings()
            val pendingEnable = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { bridge.saveControlSettings(JsonObject(enabling + ("agents" to changedProfile(enabling)))) }
            }
            assertFalse(pendingEnable.isCompleted)
            val disableAgain = bridge.controlSettings()
            bridge.saveControlSettings(JsonObject(disableAgain + ("enabled" to JsonPrimitive(false))))
            assertEquals(null, db.rows().getValue("device_control_grant"))
            val cancelledSettings = bridge.controlSettings()
            val cancelledProfile = async(start = CoroutineStart.UNDISPATCHED) {
                bridge.saveControlSettings(JsonObject(cancelledSettings + ("agents" to changedProfile(cancelledSettings))))
            }
            assertFalse(cancelledProfile.isCompleted)
            cancelledProfile.cancelAndJoin()
            // These are the real repositories exposed by the isolated slice, not direct fence calls.
            val providerWrite = async(start = CoroutineStart.UNDISPATCHED) { backend.providers.update(provider.copy(name = "updated")) }
            val modelWrite = async(start = CoroutineStart.UNDISPATCHED) { backend.models.setEnabled("model", false) }
            val agentWrite = async(start = CoroutineStart.UNDISPATCHED) { backend.agents.update(defaultAgent.copy(name = "updated agent")) }
            val projectWrite = async(start = CoroutineStart.UNDISPATCHED) { backend.projects.update(Project("project", "updated project", workspace, 1, 2)) }
            assertFalse(providerWrite.isCompleted)
            assertFalse(modelWrite.isCompleted)
            assertFalse(agentWrite.isCompleted)
            assertFalse(projectWrite.isCompleted)
            val blocked = assertFailsWith<IllegalStateException> {
                checkNotNull(backend.acpOwner).start(UUID.randomUUID().toString(), id, buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "later") }) })
            }
            assertEquals("ACP configuration is being changed", blocked.message)
            assertEquals("Provider", db.rows().provider("provider")?.name)
            assertTrue(checkNotNull(db.rows().model("model")).isEnabled)
            assertEquals("Agent", db.rows().agent("default")?.name)
            assertEquals("Project", db.rows().project("project")?.name)
            val loadedReplay = bridge.listAcpReplaySnapshots(id).single()
            assertEquals(0, bridge.readAcpReplaySnapshot(id, loadedReplay.id, 0).events.size)
            assertFailsWith<IllegalStateException> { bridge.deleteAcpReplaySnapshot(id, loadedReplay.id) }
            assertEquals(requestId, bridge.backgroundAcpBySession(id)?.get("requestId")?.jsonPrimitive?.content)
            bridge.controlPermission(requestId, permission.getValue("options").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
            withTimeout(5_000) { providerWrite.await(); modelWrite.await(); agentWrite.await(); projectWrite.await() }
            assertTrue(withTimeout(5_000) { pendingDisable.await() }.isFailure)
            assertTrue(withTimeout(5_000) { pendingEnable.await() }.isFailure)
            val finalControl = bridge.controlSettings()
            assertFalse(finalControl.getValue("enabled").jsonPrimitive.boolean)
            assertEquals(originalProfiles, finalControl.getValue("agents"))
            assertEquals("updated", db.rows().provider("provider")?.name)
            assertFalse(checkNotNull(db.rows().model("model")).isEnabled)
            assertEquals("updated agent", db.rows().agent("default")?.name)
            assertEquals("updated project", db.rows().project("project")?.name)
            assertTrue(transports.all { it.stopped })
            assertFailsWith<Exception> { backend.models.insert(model.copy(id = "invalid", providerId = "missing")) }
            backend.models.setEnabled("model", true)
            assertTrue(checkNotNull(db.rows().model("model")).isEnabled)
            // A workspace operation may hold its fence while two ACP-fenced writers queue.
            // Once it drains, both writers must finish without acquiring the locks in reverse.
            val workspaceEntered = CompletableDeferred<Unit>()
            val releaseWorkspace = CompletableDeferred<Unit>()
            val workspaceHolder = async(start = CoroutineStart.UNDISPATCHED) {
                RuntimeWorkspaceFence.forDatabase(db).mutate { workspaceEntered.complete(Unit); releaseWorkspace.await() }
            }
            workspaceEntered.await()
            try {
                val waitingProject = async(start = CoroutineStart.UNDISPATCHED) {
                    backend.projects.update(Project("project", "after workspace", workspace, 1, 3))
                }
                val waitingClone = async(start = CoroutineStart.UNDISPATCHED) { backend.agents.clone("default") }
                assertFalse(waitingProject.isCompleted)
                assertFalse(waitingClone.isCompleted)
                releaseWorkspace.complete(Unit)
                withTimeout(5_000) { workspaceHolder.await(); waitingProject.await(); assertTrue(waitingClone.await() != null) }
                assertEquals("after workspace", db.rows().project("project")?.name)
            } finally { releaseWorkspace.complete(Unit) }
            val done = withTimeout(5_000) {
                var batch = bridge.backgroundAcpEvents(requestId, 0)
                while (!batch.getValue("done").jsonPrimitive.boolean) { delay(10); batch = bridge.backgroundAcpEvents(requestId, 0) }
                batch
            }
            assertTrue(done.getValue("events").jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "Finished" })
            var history: JsonObject? = null
            bridge.controlCommand(UUID.randomUUID().toString(), buildJsonObject { put("action", "history"); put("sessionId", id) }) { history = it }
            assertEquals(listOf("hello", "answer"), checkNotNull(history).getValue("messages").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content })
            assertEquals(history, bridge.localAcpHistory(id, null))
            var nullCursor: JsonObject? = null
            bridge.controlCommand(UUID.randomUUID().toString(), buildJsonObject {
                put("action", "history"); put("sessionId", id); put("before", JsonNull)
            }) { nullCursor = it }
            assertEquals(history, nullCursor)
            assertFailsWith<IllegalStateException> {
                bridge.controlCommand(UUID.randomUUID().toString(), buildJsonObject {
                    put("action", "history"); put("sessionId", id); put("before", 3)
                }) { }
            }
            assertFalse(bridge.hasActiveTasks())
            assertTrue(transports.all { it.stopped })
            bridge.deleteAcpReplaySnapshot(id, loadedReplay.id)
            assertTrue(bridge.listAcpReplaySnapshots(id).isEmpty())
            // Shutdown cancels and joins a settings save waiting for a live ACP worker.
            val shutdownTurn = UUID.randomUUID().toString()
            bridge.startBackgroundAcp(shutdownTurn, buildJsonObject { put("action", "prompt"); put("sessionId", id)
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "wait") }) }) })
            withTimeout(5_000) {
                while (bridge.backgroundAcpEvents(shutdownTurn, 0).getValue("events").jsonArray.none {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "Permission"
                }) delay(10)
            }
            val beforeShutdown = bridge.controlSettings()
            val blockedSave = async(start = CoroutineStart.UNDISPATCHED) {
                bridge.saveControlSettings(JsonObject(beforeShutdown + ("agents" to changedProfile(beforeShutdown))))
            }
            assertFalse(blockedSave.isCompleted)
            withTimeout(5_000) { lifecycle.stopAndAwait() }
            assertTrue(blockedSave.isCancelled)
            assertEquals(originalProfiles, Json.parseToJsonElement(checkNotNull(db.rows().getValue("device_control_config")).value).jsonObject["agents"])
            assertFailsWith<IllegalStateException> { bridge.controlSettings() }
            assertFailsWith<IllegalStateException> { bridge.saveControlSettings(beforeShutdown) }
            assertFailsWith<IllegalStateException> { bridge.listAcpReplaySnapshots(id) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
