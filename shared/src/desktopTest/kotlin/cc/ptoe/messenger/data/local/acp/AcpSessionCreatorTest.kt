/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcpSessionCreatorTest {
    private class Fixture(private val mode: String = "load") : AcpLineTransport {
        private lateinit var process: Process
        private lateinit var input: java.io.BufferedWriter
        private lateinit var output: java.io.BufferedReader
        override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) {
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("core/rust/crates/messenger-http/tests/fixtures/acp_agent.py")) }
            process = ProcessBuilder("python3", root.resolve("core/rust/crates/messenger-http/tests/fixtures/acp_agent.py").toString(), mode)
                .directory(Path.of(binding.workspace).toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            input = process.outputStream.bufferedWriter(); output = process.inputStream.bufferedReader()
        }
        override suspend fun readLine() = withContext(Dispatchers.IO) { output.readLine() }
        override suspend fun sendLine(line: String) = withContext(Dispatchers.IO) { input.write(line); input.newLine(); input.flush() }
        override suspend fun stopAndAwait(): Unit = withContext(Dispatchers.IO) { process.destroyForcibly(); check(process.waitFor(5, TimeUnit.SECONDS)); Unit }
    }

    @Test fun newSessionIsPersistedAndKeepsTheSameWorkerForItsFirstPrompt(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-create")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val workspace = Files.createDirectory(dir.resolve("project")).toString()
            db.rows().upsertProjects(listOf(ProjectRow("project", "Project", workspace, "device", 1, 1)))
            db.rows().upsertValues(listOf(KeyValueRow("device_id", "device"), KeyValueRow("device_control_config",
                """{"agents":[{"id":"agent","name":"Agent","enabled":true,"command":"python3","args":[],"env":{}}]}""")))
            val backend = object : AcpGatewayBackend {
                override suspend fun liveTools() = emptyList<AcpGatewayTool>()
                override suspend fun execute(turnId: String, name: String, arguments: JsonObject) = ToolExecutionResult("unavailable", isError = true)
            }
            lateinit var manager: AcpWorkerManager
            val guard = object : AcpActiveTurnGuard {
                override suspend fun <T> withActiveTurn(sessionId: String, turnId: String, action: suspend () -> T): T = manager.withActiveTurn(sessionId, turnId, action)
            }
            val coordinator = AcpTurnCoordinator(RoomAcpSessionJournal(db, guard)) { 1000 }
            var starts = 0
            val factory = recordingAcpWorkerFactory(coordinator, { starts++; Fixture("mcp-load") }, LoopbackAcpGatewayEndpointFactory) { null }
            manager = AcpWorkerManager(factory, RoomAcpWorkspaceAuthority(db), backend, { false })
            val store = RoomAcpSessionStore(db, manager)
            val catalogs = listOf(
                cc.ptoe.messenger.domain.mcp.McpServerConfig("one", "test-mcp", cc.ptoe.messenger.domain.mcp.McpTransportType.STDIO,
                    command = "test", env = mapOf("TOKEN" to "secret")),
                cc.ptoe.messenger.domain.mcp.McpServerConfig("off", "disabled", cc.ptoe.messenger.domain.mcp.McpTransportType.STDIO,
                    isEnabled = false, command = "ignored"),
            )
            val authority = RoomAcpNewSessionAuthority(db, AcpPublishedMcpCatalog { catalogs })
            val creator = AcpSessionCreator(manager, store, authority) { 10 }
            val draft = AcpWorkerBinding("acp:one", "agent", "project", workspace, "", false)
            val config = AcpWorkerConfiguration("python3", emptyList(), emptyMap(), acpMcpSnapshot(catalogs), false, emptySet())
            assertEquals(1, config.mcpServers.size)
            val binding = creator.create(draft, config, "Agent")
            assertEquals("fixture-session", binding.agentSessionId)
            val saved = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject["acp:one"]!!.jsonObject
            assertEquals("fixture-session", saved["agent_session"]!!.jsonPrimitive.content)
            val run = async { manager.prompt(binding, config, JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "hello") }))) }
            val turnId = withTimeout(10_000) { coordinator.activeTurns.first { "acp:one" in it }.getValue("acp:one") }
            withTimeout(10_000) { coordinator.changes(turnId).first { coordinator.snapshot(turnId, 0).pendingPermission != null } }
            val pending = coordinator.snapshot(turnId, 0).pendingPermission!!
            assertTrue(coordinator.decide(turnId, pending.options.single().id))
            assertEquals("Finished", withTimeout(10_000) { run.await() }["type"]!!.jsonPrimitive.content)
            assertEquals(1, starts)
            val messages = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject["acp:one"]!!.jsonObject["messages"]!!.jsonArray
            assertEquals(listOf("hello", "selected"), messages.map { it.jsonObject["text"]!!.jsonPrimitive.content })
            manager.withInactiveSession("acp:one", true) {}
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun failedPersistenceStopsTheUnadoptedWorker(): Unit = runBlocking {
        var stopped = false
        val process = object : AcpWorkerProcess, AcpNewSessionBinding {
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore) = AcpWorkerReady(true, "new-session")
            override fun bindCreatedSession(binding: AcpWorkerBinding) {}
            override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject = error("unexpected")
            override suspend fun cancelAndConfirm() = false
            override suspend fun stopAndAwait() { stopped = true }
        }
        val backend = object : AcpGatewayBackend {
            override suspend fun liveTools() = emptyList<AcpGatewayTool>()
            override suspend fun execute(turnId: String, name: String, arguments: JsonObject) = ToolExecutionResult("unavailable", isError = true)
        }
        val manager = AcpWorkerManager(AcpWorkerFactory { process }, AcpWorkspaceAuthority {}, backend, { false })
        val draft = AcpWorkerBinding("acp:one", "agent", "project", "/tmp", "", false)
        val config = AcpWorkerConfiguration("agent", emptyList(), emptyMap(), JsonArray(emptyList()), false, emptySet())
        kotlin.test.assertFailsWith<IllegalStateException> { manager.createNew(draft, config, AcpNewSessionAuthority { _, _ -> }) { error("storage failure") } }
        assertTrue(stopped)
        assertFalse(manager.cancel("acp:one"))
        manager.withInactiveSession("acp:one", true) {}
    }
}
