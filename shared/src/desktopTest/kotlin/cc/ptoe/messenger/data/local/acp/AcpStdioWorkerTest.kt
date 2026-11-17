/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpStdioWorkerTest {
    internal class FixtureTransport(private val cwd: Path, private val mode: String = "load") : AcpLineTransport {
        private lateinit var process: Process
        private lateinit var input: BufferedWriter
        private lateinit var output: BufferedReader
        override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) {
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("core/rust/crates/messenger-http/tests/fixtures/acp_agent.py")) }
            process = ProcessBuilder("python3", root.resolve("core/rust/crates/messenger-http/tests/fixtures/acp_agent.py").toString(), mode)
                .directory(cwd.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            input = process.outputStream.bufferedWriter(); output = process.inputStream.bufferedReader()
        }
        override suspend fun readLine(): String? = withContext(Dispatchers.IO) { output.readLine() }
        override suspend fun sendLine(line: String) = withContext(Dispatchers.IO) { input.write(line); input.newLine(); input.flush() }
        override suspend fun stopAndAwait() = withContext(Dispatchers.IO) {
            if (::process.isInitialized) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "Fixture did not stop" }
            }
        }
    }
    private val backend = object : AcpGatewayBackend {
        override suspend fun liveTools() = emptyList<AcpGatewayTool>()
        override suspend fun execute(turnId: String, name: String, arguments: JsonObject) = ToolExecutionResult("unavailable", isError = true)
    }
    private fun configuration() = AcpWorkerConfiguration("python3", emptyList(), emptyMap(), JsonArray(emptyList()), false, emptySet())
    private fun prompt(text: String) = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", text) }))

    @Test fun settingsComeFromAgentRestoreAndLiveUpdates(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-stdio-settings")
        val updates = mutableListOf<JsonObject>()
        val worker = AcpStdioWorker(FixtureTransport(dir, "settings"), AcpGatewayEndpointFactory { _, _ -> error("unexpected gateway") }, { null }, object : AcpStdioEvents {
            override suspend fun settings(turnId: String?, settings: JsonObject) { if (turnId != null) updates.add(settings) }
        })
        val gateway = AcpGatewayCore(backend, false, emptySet())
        val binding = AcpWorkerBinding("acp:one", "agent", "project", dir.toString(), "fixture-session", false)
        try {
            worker.start(binding, configuration(), gateway)
            val initial = worker.sessionSettings()
            assertEquals("read-only", initial.getValue("configOptions").jsonArray.first().jsonObject.getValue("currentValue").jsonPrimitive.content)
            assertTrue(initial.getValue("steering").jsonObject.getValue("supported").jsonPrimitive.boolean)
            worker.prompt("turn", prompt("tools"), AcpPermissionBroker())
            assertEquals("workspace-write", worker.sessionSettings().getValue("configOptions").jsonArray.first().jsonObject.getValue("currentValue").jsonPrimitive.content)
            assertEquals(worker.sessionSettings(), updates.last())
        } finally { worker.stopAndAwait(); gateway.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun currentModeUpdateIsRetainedWhenAgentOmittedInitialModes(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-stdio-mode")
        val worker = AcpStdioWorker(FixtureTransport(dir, "settings-no-modes"), AcpGatewayEndpointFactory { _, _ -> error("unexpected gateway") }, { null }, object : AcpStdioEvents {})
        val gateway = AcpGatewayCore(backend, false, emptySet())
        val binding = AcpWorkerBinding("acp:one", "agent", "project", dir.toString(), "fixture-session", false)
        try {
            worker.start(binding, configuration(), gateway)
            assertEquals(null, worker.sessionSettings()["modes"])
            worker.prompt("turn", prompt("tools"), AcpPermissionBroker())
            assertEquals("code", worker.sessionSettings().getValue("modes").jsonObject.getValue("currentModeId").jsonPrimitive.content)
        } finally { worker.stopAndAwait(); gateway.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun laterModeNotificationWinsEvenWhenResponseAlsoContainsModes(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-mode-response-order")
        val updateSeen = CompletableDeferred<Unit>()
        val worker = AcpStdioWorker(FixtureTransport(dir, "settings-mode-after"), AcpGatewayEndpointFactory { _, _ -> error("unexpected gateway") }, { null }, object : AcpStdioEvents {
            override suspend fun settings(turnId: String?, settings: JsonObject) {
                if ((settings["modes"] as? JsonObject)?.get("currentModeId")?.jsonPrimitive?.content == "plan") updateSeen.complete(Unit)
            }
        }, beforeModeResultMerge = { withTimeout(5_000) { updateSeen.await() } })
        val gateway = AcpGatewayCore(backend, false, emptySet())
        val binding = AcpWorkerBinding("acp:one", "agent", "project", dir.toString(), "fixture-session", false)
        try {
            worker.start(binding, configuration(), gateway)
            val changed = worker.setMode("code") {}
            assertEquals("plan", changed.getValue("modes").jsonObject.getValue("currentModeId").jsonPrimitive.content)
        } finally { worker.stopAndAwait(); gateway.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun laterConfigNotificationWinsOverTheModeOptionResponse(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-config-response-order")
        val updateSeen = CompletableDeferred<Unit>()
        val worker = AcpStdioWorker(FixtureTransport(dir, "settings-mode-option-after"), AcpGatewayEndpointFactory { _, _ -> error("unexpected gateway") }, { null }, object : AcpStdioEvents {
            override suspend fun settings(turnId: String?, settings: JsonObject) {
                val current = (settings["configOptions"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("currentValue")?.jsonPrimitive?.content
                if (current == "read-only") updateSeen.complete(Unit)
            }
        }, beforeModeResultMerge = { withTimeout(5_000) { updateSeen.await() } })
        val gateway = AcpGatewayCore(backend, false, emptySet())
        val binding = AcpWorkerBinding("acp:one", "agent", "project", dir.toString(), "fixture-session", false)
        try {
            worker.start(binding, configuration(), gateway)
            val changed = worker.setModeOption("legacy-mode", "workspace-write") {}
            assertEquals("read-only", changed.getValue("configOptions").jsonArray.first().jsonObject.getValue("currentValue").jsonPrimitive.content)
        } finally { worker.stopAndAwait(); gateway.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun cancelledModeRequestWaitingForWireDoesNotObtainDispatchPermit(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val permits = java.util.concurrent.atomic.AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = object : AcpLineTransport {
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) = Unit
            override suspend fun readLine(): String? = awaitCancellation()
            override suspend fun sendLine(line: String) {
                writes.incrementAndGet(); entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            override suspend fun stopAndAwait() { release.complete(Unit) }
        }
        val peer = AcpJsonRpcPeer(transport, scope, { _, _ -> }, { _, _ -> JsonNull })
        val dir = Files.createTempDirectory("acp-write-permit")
        try {
            peer.start(AcpWorkerBinding("acp:one", "agent", "project", dir.toString(), "session", false), configuration())
            val first = async { peer.request("hold", JsonObject(emptyMap())) }
            withTimeout(5_000) { entered.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { peer.request("session/set_mode", JsonObject(emptyMap())) { permits.incrementAndGet() } }
            second.cancelAndJoin()
            assertEquals(0, permits.get())
            assertEquals(1, writes.get())
            release.complete(Unit)
            first.cancelAndJoin()
        } finally { release.complete(Unit); peer.closeAndAwait(); scope.cancel(); dir.toFile().deleteRecursively() }
    }

    @Test fun createsANewAgentSessionThroughTheRealFixture(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-new-session")
        val worker = AcpStdioWorker(FixtureTransport(dir), AcpGatewayEndpointFactory { _, _ -> error("unexpected gateway") }, { null }, object : AcpStdioEvents {})
        val gateway = AcpGatewayCore(backend, false, emptySet())
        try {
            val binding = AcpWorkerBinding("acp:one", "agent", "project", dir.toString(), "", false)
            assertEquals("fixture-session", worker.start(binding, configuration(), gateway).agentSessionId)
        } finally { worker.stopAndAwait(); gateway.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun restoresRealFixtureAndRoutesOneUsePermission(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-stdio")
        val updates = mutableListOf<String>()
        val broker = AcpPermissionBroker()
        val events = object : AcpStdioEvents {
            override suspend fun update(turnId: String?, update: JsonObject) { updates.add((update["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.content ?: "") }
            override suspend fun permission(turnId: String, prompt: AcpPermissionPrompt?) {
                if (prompt != null) assertTrue(broker.choose(turnId, prompt.options.single().id))
            }
        }
        val worker = AcpStdioWorker(FixtureTransport(dir), AcpGatewayEndpointFactory { _, _ -> error("unexpected gateway") }, { null }, events)
        val gateway = AcpGatewayCore(backend, false, emptySet())
        val binding = AcpWorkerBinding("acp:one", "agent", "project", dir.toString(), "fixture-session", false)
        try {
            assertFalse(worker.start(binding, configuration(), gateway).skillsGatewayAvailable)
            val result = worker.prompt("turn", prompt("hello"), broker)
            assertEquals("Finished", result["type"]!!.jsonPrimitive.content)
            assertTrue(updates.contains("replayed question"))
            assertTrue(updates.contains("replayed answer"))
            assertTrue(updates.contains("selected"))
        } finally { worker.stopAndAwait(); gateway.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun cancellationIsConfirmedByTheRealAgentResponse(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-stdio-cancel")
        val seen = MutableStateFlow(false)
        val worker = AcpStdioWorker(FixtureTransport(dir), AcpGatewayEndpointFactory { _, _ -> error("unexpected gateway") }, { null }, object : AcpStdioEvents {
            override suspend fun update(turnId: String?, update: JsonObject) { if ((update["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.content == "partial") seen.value = true }
        })
        val gateway = AcpGatewayCore(backend, false, emptySet())
        val binding = AcpWorkerBinding("acp:one", "agent", "project", dir.toString(), "fixture-session", false)
        try {
            worker.start(binding, configuration(), gateway)
            val turn = async { worker.prompt("turn", prompt("wait"), AcpPermissionBroker()) }
            withTimeout(10_000) { seen.first { it } }
            turn.cancelAndJoin()
            assertTrue(withTimeout(10_000) { worker.cancelAndConfirm() })
        } finally { worker.stopAndAwait(); gateway.close(); dir.toFile().deleteRecursively() }
    }
    @Test fun realAgentCallsLiveRoomSkillsThroughTheSseGateway(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-stdio-gateway")
        val db = androidx.room.Room.databaseBuilder<cc.ptoe.messenger.data.local.room.LocalDatabase>(name = dir.resolve("db").toString())
            .setDriver(androidx.sqlite.driver.bundled.BundledSQLiteDriver()).build()
        val skills = cc.ptoe.messenger.data.local.skill.RoomSkillStore(db)
        val updates = mutableListOf<String>()
        val worker = AcpStdioWorker(FixtureTransport(dir, "skills-resume"), LoopbackAcpGatewayEndpointFactory, { skills.discovery(true) }, object : AcpStdioEvents {
            override suspend fun update(turnId: String?, update: JsonObject) { updates.add((update["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.content ?: "") }
        })
        val gateway = AcpGatewayCore(AcpSkillsGatewayBackend(skills, backend), false, emptySet())
        val binding = AcpWorkerBinding("acp:one", "agent", "project", dir.toString(), "fixture-session", false)
        try {
            skills.put("fixture-skill", cc.ptoe.messenger.data.local.skill.StoredSkill(mapOf(
                "SKILL.md" to "---\nname: fixture-skill\ndescription: Fixture\n---\nPRIVATE SKILL BODY",
                "references/guide.md" to "PRIVATE RESOURCE"), emptyMap(), true))
            assertTrue(worker.start(binding, configuration(), gateway).skillsGatewayAvailable)
            gateway.beginTurn("first")
            assertEquals("Finished", worker.prompt("first", prompt("hello"), AcpPermissionBroker())["type"]!!.jsonPrimitive.content)
            gateway.endTurn("first")
            skills.setEnabled("fixture-skill", false)
            gateway.beginTurn("second")
            assertEquals("Finished", worker.prompt("second", prompt("disabled"), AcpPermissionBroker())["type"]!!.jsonPrimitive.content)
            gateway.endTurn("second")
            assertTrue(updates.contains("skill read"))
            assertTrue(updates.contains("skill disabled"))
        } finally { worker.stopAndAwait(); gateway.close(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun acknowledgedCancelStillWaitsForPersistentChildToStop(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-stdio-child")
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("core/rust/crates/messenger-http/tests/fixtures/acp_agent.py")) }
        val fixture = root.resolve("core/rust/crates/messenger-http/tests/fixtures/acp_agent.py")
        val pidFile = dir.resolve("child.pid")
        val wrapper = Files.writeString(dir.resolve("agent_with_child.py"), """
            import os, signal, subprocess, sys
            signal.signal(signal.SIGCHLD, signal.SIG_IGN)
            child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            with open(sys.argv[2], 'w') as f:
                f.write(str(child.pid))
            os.execv(sys.executable, [sys.executable, sys.argv[1], 'load'])
        """.trimIndent())
        val stopStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var process: Process? = null
        lateinit var input: BufferedWriter
        lateinit var output: BufferedReader
        fun killFixture() {
            if (Files.exists(pidFile)) {
                val child = runCatching { ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElse(null) }.getOrNull()
                if (child != null && child.isAlive) child.destroyForcibly()
            }
            process?.let { if (it.isAlive) it.destroyForcibly() }
        }
        val transport = object : AcpLineTransport {
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) {
                process = ProcessBuilder("python3", wrapper.toString(), fixture.toString(), pidFile.toString())
                    .directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                input = checkNotNull(process).outputStream.bufferedWriter(); output = checkNotNull(process).inputStream.bufferedReader()
            }
            override suspend fun readLine(): String? = withContext(Dispatchers.IO) { output.readLine() }
            override suspend fun sendLine(line: String) = withContext(Dispatchers.IO) { input.write(line); input.newLine(); input.flush() }
            override suspend fun stopAndAwait(): Unit = withContext(Dispatchers.IO) {
                stopStarted.complete(Unit)
                release.await()
                val child = ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElse(null)
                if (child != null && child.isAlive) { child.destroyForcibly(); child.onExit().get(5, TimeUnit.SECONDS) }
                checkNotNull(process).destroyForcibly()
                check(checkNotNull(process).waitFor(5, TimeUnit.SECONDS))
            }
        }
        val seen = CompletableDeferred<Unit>()
        val worker = AcpStdioWorker(transport, AcpGatewayEndpointFactory { _, _ -> error("unexpected gateway") }, { null }, object : AcpStdioEvents {
            override suspend fun update(turnId: String?, update: JsonObject) { if ((update["content"] as? JsonObject)?.get("text")?.jsonPrimitive?.content == "partial") seen.complete(Unit) }
        })
        val manager = AcpWorkerManager(AcpWorkerFactory { worker }, AcpWorkspaceAuthority {}, backend, { false })
        val binding = AcpWorkerBinding("acp:one", "agent", "project", dir.toString(), "fixture-session", false)
        val turn = async { manager.prompt(binding, configuration(), prompt("wait")) }
        try {
            withTimeout(10_000) { seen.await() }
            val child = ProcessHandle.of(Files.readString(pidFile).trim().toLong()).orElseThrow()
            assertTrue(manager.cancel(binding.conversationId))
            withTimeout(10_000) { stopStarted.await() }
            assertTrue(child.isAlive)
            assertFalse(turn.isCompleted)
            kotlin.test.assertFailsWith<IllegalStateException> { manager.withInactiveSession(binding.conversationId, true) {} }
            release.complete(Unit)
            withTimeout(10_000) { turn.join() }
            assertFalse(child.isAlive)
            manager.withInactiveSession(binding.conversationId, true) {}
        } finally {
            release.complete(Unit)
            turn.cancel()
            withTimeoutOrNull(10_000) { turn.join() }
            killFixture()
            dir.toFile().deleteRecursively()
        }
    }

}
