/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.repository.room.AcpSessionMutationGate
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
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
    private class Fixture(private val directory: Path) : AcpLineTransport {
        private lateinit var process: Process
        private lateinit var input: java.io.BufferedWriter
        private lateinit var output: java.io.BufferedReader
        override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) {
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("core/rust/crates/messenger-http/tests/fixtures/acp_agent.py")) }
            process = ProcessBuilder("python3", root.resolve("core/rust/crates/messenger-http/tests/fixtures/acp_agent.py").toString(), "load")
                .directory(Path.of(binding.workspace).toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            input = process.outputStream.bufferedWriter(); output = process.inputStream.bufferedReader()
        }
        override suspend fun readLine() = withContext(Dispatchers.IO) { output.readLine() }
        override suspend fun sendLine(line: String) = withContext(Dispatchers.IO) { input.write(line); input.newLine(); input.flush() }
        override suspend fun stopAndAwait(): Unit = withContext(Dispatchers.IO) { process.destroyForcibly(); check(process.waitFor(5, TimeUnit.SECONDS)); Unit }
    }

    @Test fun restoredWorkerPersistsReplayAndResumesPermissionAfterObserverDetaches(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-coordinator")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val workspace = Files.createDirectory(dir.resolve("project")).toString()
            db.rows().upsertValues(listOf(KeyValueRow("device_id", "device")))
            db.rows().upsertProjects(listOf(ProjectRow("project", "Project", workspace, "device", 1, 1)))
            val gate = object : AcpSessionMutationGate { override suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T) = action() }
            val binding = AcpWorkerBinding("acp:one", "agent", "project", workspace, "fixture-session", false)
            RoomAcpSessionStore(db, gate).create(binding, "Agent", 1)
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
                val peer = AcpStdioWorker(Fixture(dir), AcpGatewayEndpointFactory { _, _ -> error("unexpected gateway") }, { null }, coordinator.eventsFor(bound))
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
            assertFalse(coordinator.decide(turnId, pending.options.single().id))
            val saved = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject["acp:one"]!!.jsonObject
            val messages = saved["messages"]!!.jsonArray
            assertEquals(listOf("replayed question", "replayed answer", "hello", "selected"), messages.map { it.jsonObject["text"]!!.jsonPrimitive.content })
            assertTrue(messages.last().jsonObject["events"]!!.jsonArray.isNotEmpty())
            manager.withInactiveSession("acp:one", true) {}
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
