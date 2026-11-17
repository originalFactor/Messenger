/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.repository.room.AcpSessionMutationGate
import cc.ptoe.messenger.data.repository.projectAcpHistory
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import cc.ptoe.messenger.presentation.ui.chat.acpChatMessages
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpTurnCoordinatorTest {
    private class Fixture(private val directory: Path, private val mode: String = "load") : AcpLineTransport {
        private lateinit var process: Process
        private lateinit var input: java.io.BufferedWriter
        private lateinit var output: java.io.BufferedReader
        override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) {
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("shared/desktopTest/fixtures/acp_agent.py")) }
            process = ProcessBuilder("python3", root.resolve("shared/desktopTest/fixtures/acp_agent.py").toString(), mode)
                .directory(Path.of(binding.workspace).toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            input = process.outputStream.bufferedWriter(); output = process.inputStream.bufferedReader()
        }
        override suspend fun readLine() = withContext(Dispatchers.IO) { output.readLine() }
        override suspend fun sendLine(line: String) = withContext(Dispatchers.IO) { input.write(line); input.newLine(); input.flush() }
        override suspend fun stopAndAwait(): Unit = withContext(Dispatchers.IO) { process.destroyForcibly(); check(process.waitFor(5, TimeUnit.SECONDS)); Unit }
    }

    @Test fun restoredWorkerPersistsReplayAndResumesPermissionAfterObserverDetaches(): Unit = runBlocking {
        checkRestoredWorker(preserveHistory = false)
    }

    @Test fun restoredWorkerPreservesExistingAttachmentsAndErrorsInsteadOfReplacingHistory(): Unit = runBlocking {
        checkRestoredWorker(preserveHistory = true)
    }

    @Test fun emptyLocalHistoryRetainsSessionLoadMediaAndResources(): Unit = runBlocking {
        checkRestoredWorker(preserveHistory = false, media = true)
    }

    @Test fun replayedMediaDoesNotReplaceExistingLocalHistory(): Unit = runBlocking {
        checkRestoredWorker(preserveHistory = true, media = true)
    }

    @Test fun liveMediaIsVisibleAndPersistsAcrossReattachment(): Unit = runBlocking {
        checkRestoredWorker(preserveHistory = false, liveMedia = true)
    }

    private suspend fun checkRestoredWorker(preserveHistory: Boolean, media: Boolean = false, liveMedia: Boolean = false) = coroutineScope {
        val dir = Files.createTempDirectory("acp-coordinator")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val workspace = Files.createDirectory(dir.resolve("project")).toString()
            db.rows().upsertValues(listOf(KeyValueRow("device_id", "device")))
            db.rows().upsertProjects(listOf(ProjectRow("project", "Project", workspace, "device", 1, 1)))
            val gate = object : AcpSessionMutationGate { override suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T) = action() }
            val binding = AcpWorkerBinding("acp:one", "agent", "project", workspace, "fixture-session", false)
            RoomAcpSessionStore(db, gate).create(binding, "Agent", 1)
            val original = JsonArray(listOf(
                buildJsonObject {
                    put("id", "original-user"); put("role", "user"); put("text", "local question"); put("status", "sent"); put("timestamp", 1)
                    put("content", JsonArray(listOf(buildJsonObject { put("type", "image"); put("data", "original-image") })))
                },
                buildJsonObject { put("id", "original-error"); put("role", "assistant"); put("text", "original error"); put("status", "error"); put("timestamp", 2) },
            ))
            if (preserveHistory) {
                val root = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject
                val record = root.getValue(binding.conversationId).jsonObject
                db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", JsonObject(root +
                    (binding.conversationId to JsonObject(record + ("messages" to original)))).toString())))
            }
            val backend = object : AcpGatewayBackend {
                override suspend fun liveTools() = emptyList<AcpGatewayTool>()
                override suspend fun execute(turnId: String, name: String, arguments: JsonObject) = ToolExecutionResult("unavailable", isError = true)
            }
            lateinit var manager: AcpWorkerManager
            val guard = object : AcpActiveTurnGuard {
                override suspend fun <T> withActiveTurn(sessionId: String, turnId: String, action: suspend () -> T): T = manager.withActiveTurn(sessionId, turnId, action)
            }
            val coordinator = AcpTurnCoordinator(RoomAcpSessionJournal(db, guard)) { 1000 }
            manager = AcpWorkerManager(AcpWorkerFactory { bound ->
                val peer = AcpStdioWorker(Fixture(dir, if (liveMedia) "load-live-media" else if (media) "load-media" else "load"), AcpGatewayEndpointFactory { _, _ -> error("unexpected gateway") }, { null }, coordinator.eventsFor(bound))
                RecordingAcpWorker(bound, peer, coordinator)
            }, RoomAcpWorkspaceAuthority(db), backend, { false })
            val configuration = AcpWorkerConfiguration("python3", emptyList(), emptyMap(), JsonArray(emptyList()), false, emptySet())
            val content = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "hello") }))
            val run = async { manager.prompt(binding, configuration, content) }
            val turnId = withTimeout(10_000) { coordinator.activeTurns.first { "acp:one" in it }.getValue("acp:one") }
            val changes = coordinator.changes(turnId)
            withTimeout(10_000) { changes.first { coordinator.snapshot(turnId, 0).pendingPermission != null } }
            val pending = coordinator.snapshot(turnId, 0).pendingPermission!!
            val bridge = AcpUiObservationBridge(coordinator, manager)
            val attached = bridge.bySession("acp:one")!!
            assertEquals(turnId, attached["requestId"]!!.jsonPrimitive.content)
            assertTrue(attached["events"]!!.jsonArray.any { it.jsonObject["type"]!!.jsonPrimitive.content == "Permission" })
            assertFalse(bridge.decide("wrong-turn", pending.options.single().id))
            assertFalse(bridge.cancel("wrong-turn"))
            assertTrue(bridge.decide(turnId, pending.options.single().id))
            assertEquals("Finished", withTimeout(10_000) { run.await() }["type"]!!.jsonPrimitive.content)
            val snapshot = coordinator.snapshot(turnId, 0)
            assertTrue(snapshot.completed)
            assertEquals("TurnStarted", snapshot.events.first().event["type"]!!.jsonPrimitive.content)
            assertEquals(true, bridge.events(turnId, 0)["done"]!!.jsonPrimitive.boolean)
            assertEquals(null, bridge.bySession("acp:one"))
            assertEquals(null, snapshot.pendingPermission)
            val snapshots = RoomAcpReplaySnapshots(db, gate)
            val loaded = snapshots.list(binding.conversationId).single()
            val raw = snapshots.page(binding.conversationId, loaded.id)
            assertEquals("fixture-session", raw.source.getValue("agentSessionId").jsonPrimitive.content)
            assertEquals("replayed question", raw.events.first().jsonObject.getValue("content").jsonObject.getValue("text").jsonPrimitive.content)
            assertTrue(raw.events.any { it.jsonObject["sessionUpdate"]?.jsonPrimitive?.content == "agent_message_chunk" })
            if (media) assertTrue(raw.events.any { (it.jsonObject["content"] as? JsonObject)?.get("type")?.jsonPrimitive?.content == "image" })
            assertFalse(coordinator.decide(turnId, pending.options.single().id))
            val saved = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject["acp:one"]!!.jsonObject
            val messages = saved["messages"]!!.jsonArray
            if (preserveHistory) {
                assertEquals(original, JsonArray(messages.take(original.size)))
                assertEquals(listOf("local question", "original error", "hello", "selected"), messages.map { it.jsonObject["text"]!!.jsonPrimitive.content })
            } else if (media) {
                assertEquals(listOf("replayed question", "", "replayed answer", "", "hello", "selected"),
                    messages.map { it.jsonObject["text"]!!.jsonPrimitive.content })
                val userBlocks = messages[1].jsonObject.getValue("content").jsonArray
                assertEquals(listOf("image", "resource"), userBlocks.map { it.jsonObject.getValue("type").jsonPrimitive.content })
                assertEquals("aW1hZ2U=", userBlocks[0].jsonObject.getValue("data").jsonPrimitive.content)
                assertEquals("notes", userBlocks[1].jsonObject.getValue("resource").jsonObject.getValue("text").jsonPrimitive.content)
                val assistantBlocks = messages[3].jsonObject.getValue("content").jsonArray
                assertEquals(listOf("audio", "resource_link"), assistantBlocks.map { it.jsonObject.getValue("type").jsonPrimitive.content })
                assertEquals("YXVkaW8=", assistantBlocks[0].jsonObject.getValue("data").jsonPrimitive.content)
                assertEquals("file:///result.txt", assistantBlocks[1].jsonObject.getValue("uri").jsonPrimitive.content)
                val projected = projectAcpHistory("acp:one", JsonArray(messages))
                assertEquals("[图片]", projected[1].content)
                assertEquals("[音频]", projected[3].content)
                val chat = acpChatMessages("acp:one", messages.map { it.jsonObject })
                assertEquals("[Attachment]", chat[1].content)
                assertEquals("[Audio]\n[Attachment]\n", chat[3].content)
            } else if (liveMedia) {
                assertEquals(listOf("replayed question", "replayed answer", "hello", "selectedcontinued"), messages.map { it.jsonObject["text"]!!.jsonPrimitive.content })
                val savedAnswer = messages.last().jsonObject
                assertEquals(listOf("image", "audio", "resource", "resource_link"), savedAnswer.getValue("content").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
                assertEquals("aW1hZ2U=", savedAnswer.getValue("content").jsonArray.first().jsonObject.getValue("data").jsonPrimitive.content)
                val streamed = buildJsonObject {
                    put("id", "live"); put("role", "assistant"); put("text", "selectedcontinued"); put("status", "sending")
                    put("events", JsonArray(snapshot.events.map { it.event }))
                }
                val liveRows = acpChatMessages("acp:one", listOf(streamed))
                val savedRows = acpChatMessages("acp:one", listOf(savedAnswer))
                assertEquals("selected\n[Image]\n", liveRows.first().content)
                assertEquals("[Audio]\n[Attachment]\n[Attachment]\ncontinued", liveRows.last().content)
                assertEquals(liveRows.map { it.content }, savedRows.map { it.content })
                assertFalse(snapshot.events.any { it.event.toString().contains("aW1hZ2U=") })
            } else {
                assertEquals(listOf("replayed question", "replayed answer", "hello", "selected"), messages.map { it.jsonObject["text"]!!.jsonPrimitive.content })
            }
            assertTrue(messages.last().jsonObject["events"]!!.jsonArray.isNotEmpty())
            manager.withInactiveSession("acp:one", true) {}
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
