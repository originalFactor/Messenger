/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpWorkerManagerTest {
    private val backend = object : AcpGatewayBackend {
        override suspend fun liveTools() = listOf(AcpGatewayTool("messenger_agents", "agent", JsonObject(emptyMap())), AcpGatewayTool("messenger_learning", "learning", JsonObject(emptyMap())))
        override suspend fun execute(turnId: String, name: String, arguments: JsonObject) = ToolExecutionResult("ok")
    }
    private fun binding(delegated: Boolean = false) = AcpWorkerBinding("acp:session", "profile", "project", "/private/project", "agent-session", delegated)
    private fun config() = AcpWorkerConfiguration("agent", listOf("acp"), mapOf("SECRET" to "token"), JsonArray(emptyList()), true, setOf("messenger_learning"))
    private val content = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "hello") }))

    @Test fun cancellationStopsUnconfirmedWorkerBeforeReleasingReservation(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>(); val stopping = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var created = 0
        val process = object : AcpWorkerProcess {
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore) = AcpWorkerReady(true)
            override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject { entered.complete(Unit); awaitCancellation() }
            override suspend fun cancelAndConfirm() = true
            override suspend fun stopAndAwait() { stopping.complete(Unit); release.await() }
        }
        val manager = AcpWorkerManager(AcpWorkerFactory { created++; process }, AcpWorkspaceAuthority {}, backend, { false })
        val turn = async { manager.prompt(binding(), config(), content) }
        withTimeout(5000) { entered.await() }
        assertTrue(manager.cancel("acp:session"))
        withTimeout(5000) { stopping.await() }
        assertFalse(turn.isCompleted)
        assertFailsWith<IllegalStateException> { manager.prompt(binding(), config(), content) }
        assertFailsWith<IllegalStateException> { manager.withInactiveSession("acp:session", true) {} }
        release.complete(Unit)
        withTimeout(5000) { turn.join() }
        assertTrue(turn.isCancelled)
        manager.withInactiveSession("acp:session", true) {}
        assertEquals(1, created)
    }

    @Test fun delegatedWorkersCannotAcquireDelegationOrCoreCapabilities(): Unit = runBlocking {
        var allow: Boolean? = null
        var tools: Set<String>? = null
        lateinit var gateway: AcpGatewayCore
        val process = object : AcpWorkerProcess {
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, value: AcpGatewayCore): AcpWorkerReady {
                gateway = value; allow = configuration.allowDelegation; tools = configuration.coreToolScope
                assertFalse(configuration.toString().contains("token"))
                return AcpWorkerReady(true)
            }
            override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject {
                val request = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/list") }
                return buildJsonObject { put("type", "Finished"); put("payload", gateway.request(gateway.capability(), request)!!) }
            }
            override suspend fun cancelAndConfirm() = true
            override suspend fun stopAndAwait() {}
        }
        val manager = AcpWorkerManager(AcpWorkerFactory { process }, AcpWorkspaceAuthority {}, backend, { true })
        val result = manager.prompt(binding(delegated = true), config(), content)
        assertEquals(false, allow)
        assertTrue(tools!!.isEmpty())
        assertEquals(listOf("messenger_skills"), result["payload"]!!.jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        manager.withInactiveSession("acp:session", true) {}
    }

    @Test fun failedOrTimedOutStartupClosesProcessAndDoesNotLeaveAnActiveTurn(): Unit = runBlocking {
        var stopped = 0
        val process = object : AcpWorkerProcess {
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore): AcpWorkerReady { awaitCancellation() }
            override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject = error("unexpected")
            override suspend fun cancelAndConfirm() = false
            override suspend fun stopAndAwait() { stopped++ }
        }
        val manager = AcpWorkerManager(AcpWorkerFactory { process }, AcpWorkspaceAuthority {}, backend, { false }, startupTimeoutMillis = 20)
        assertFailsWith<IllegalStateException> { manager.prompt(binding(), config(), content) }
        assertEquals(1, stopped)
        manager.withInactiveSession("acp:session", true) {}
    }
    @Test fun failedAgentIsStoppedAndTheNextTurnStartsANewWorker(): Unit = runBlocking {
        var starts = 0
        var stops = 0
        val factory = AcpWorkerFactory {
            val number = ++starts
            object : AcpWorkerProcess {
                override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore) = AcpWorkerReady(true)
                override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker) = buildJsonObject { put("type", if (number == 1) "Error" else "Finished") }
                override suspend fun cancelAndConfirm() = false
                override suspend fun stopAndAwait() { stops++ }
            }
        }
        val manager = AcpWorkerManager(factory, AcpWorkspaceAuthority {}, backend, { false })
        assertEquals("Error", manager.prompt(binding(), config(), content)["type"]!!.jsonPrimitive.content)
        assertEquals(1, stops)
        assertEquals("Finished", manager.prompt(binding(), config(), content)["type"]!!.jsonPrimitive.content)
        assertEquals(2, starts)
        manager.withInactiveSession("acp:session", true) {}
        assertEquals(2, stops)
    }

    @Test fun workerConfigurationSnapshotsMutableInputs(): Unit = runBlocking {
        val args = mutableListOf("original")
        val env = mutableMapOf("TOKEN" to "original")
        val servers = mutableListOf<JsonElement>(buildJsonObject { put("name", "original") })
        val scopes = mutableSetOf("messenger_learning")
        val value = AcpWorkerConfiguration("agent", args, env, JsonArray(servers), true, scopes)
        args[0] = "changed"; env["TOKEN"] = "changed"; servers.clear(); scopes.clear()
        assertEquals(listOf("original"), value.arguments)
        assertEquals("original", value.environment["TOKEN"])
        assertEquals("original", value.mcpServers.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(setOf("messenger_learning"), value.coreToolScope)
        assertFalse(value.toString().contains("original"))
    }

    @Test fun cancellationIsBoundToTheObservedTurn(): Unit = runBlocking {
        val starts = kotlinx.coroutines.channels.Channel<String>(2)
        val factory = AcpWorkerFactory { object : AcpWorkerProcess {
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore) = AcpWorkerReady(true)
            override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject {
                starts.send(turnId); awaitCancellation()
            }
            override suspend fun cancelAndConfirm() = true
            override suspend fun stopAndAwait() {}
        } }
        val manager = AcpWorkerManager(factory, AcpWorkspaceAuthority {}, backend, { false })
        val requested = "1fc44a2a-a164-4192-bf49-7f226c902f32"
        val first = async { manager.prompt(binding(), config(), content, requested) }
        val firstId = withTimeout(5000) { starts.receive() }
        assertEquals(requested, firstId)
        assertTrue(manager.cancelTurn("acp:session", firstId))
        withTimeout(5000) { first.join() }
        val second = async { manager.prompt(binding(), config(), content) }
        val secondId = withTimeout(5000) { starts.receive() }
        assertFalse(manager.cancelTurn("acp:session", firstId))
        assertFalse(second.isCompleted)
        assertTrue(manager.cancelTurn("acp:session", secondId))
        withTimeout(5000) { second.join() }
    }

    @Test fun idleRetirementRequiresProofBeforeAnotherTurn(): Unit = runBlocking {
        var stops = 0
        val process = object : AcpWorkerProcess {
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore) = AcpWorkerReady(true)
            override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker) = buildJsonObject { put("type", "Finished") }
            override suspend fun cancelAndConfirm() = true
            override suspend fun stopAndAwait() { if (++stops == 1) error("Cannot prove process tree stopped") }
        }
        val manager = AcpWorkerManager(AcpWorkerFactory { process }, AcpWorkspaceAuthority {}, backend, { false })
        manager.prompt(binding(), config(), content)
        assertFailsWith<IllegalStateException> { manager.retireAndAwait("acp:session") }
        assertFailsWith<IllegalStateException> { manager.prompt(binding(), config(), content) }
        assertTrue(manager.reconcileStopped("acp:session"))
        manager.prompt(binding(), config(), content)
        manager.retireAndAwait("acp:session")
        assertEquals(3, stops)
    }

    @Test fun stoppedTurnBarrierBindsReceiptAndSurvivesCommitFailure(): Unit = runBlocking {
        var stops = 0
        val process = object : AcpWorkerProcess {
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore) = AcpWorkerReady(true)
            override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker) = buildJsonObject { put("type", "Finished") }
            override suspend fun cancelAndConfirm() = true
            override suspend fun stopAndAwait() { if (++stops == 1) error("No process-tree proof") }
        }
        val manager = AcpWorkerManager(AcpWorkerFactory { process }, AcpWorkspaceAuthority {}, backend, { false })
        val barrier = AcpManagerStopBarrier(manager)
        val first = "1fc44a2a-a164-4192-bf49-7f226c902f32"
        val second = "2fc44a2a-a164-4192-bf49-7f226c902f32"
        assertFailsWith<IllegalStateException> { barrier.withStopped(first, "acp:session") { } }
        manager.prompt(binding(), config(), content, first)
        assertFailsWith<IllegalStateException> { barrier.withStopped(second, "acp:session") { } }
        assertFailsWith<IllegalStateException> { barrier.withStopped(first, "acp:session") { } }
        assertFailsWith<IllegalStateException> { manager.prompt(binding(), config(), content, second) }
        assertTrue(manager.reconcileStopped("acp:session"))
        assertFailsWith<IllegalStateException> { barrier.withStopped(first, "acp:session") { error("Commit failed") } }
        assertEquals("committed", barrier.withStopped(first, "acp:session") { "committed" })
        manager.prompt(binding(), config(), content, second)
        assertFailsWith<IllegalStateException> { barrier.withStopped(first, "acp:session") { } }
        assertEquals("second", barrier.withStopped(second, "acp:session") { "second" })
        assertEquals(3, stops)
    }

    @Test fun recreatingSessionInvalidatesOldStopReceipt(): Unit = runBlocking {
        val factory = AcpWorkerFactory { object : AcpWorkerProcess, AcpNewSessionBinding {
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore) =
                AcpWorkerReady(true, if (binding.agentSessionId.isEmpty()) "new-session" else null)
            override fun bindCreatedSession(binding: AcpWorkerBinding) {}
            override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker) = buildJsonObject { put("type", "Finished") }
            override suspend fun cancelAndConfirm() = true
            override suspend fun stopAndAwait() {}
        } }
        val manager = AcpWorkerManager(factory, AcpWorkspaceAuthority {}, backend, { false })
        val old = "1fc44a2a-a164-4192-bf49-7f226c902f32"
        manager.prompt(binding(), config(), content, old)
        manager.withStoppedTurn("acp:session", old) { }
        manager.createNew(binding().copy(agentSessionId = ""), config(), AcpNewSessionAuthority { _, _ -> }) { }
        assertFailsWith<IllegalStateException> { manager.withStoppedTurn("acp:session", old) { } }
        manager.retireAndAwait("acp:session")
    }

    @Test fun quiescenceProofAndLedgerSettlementFenceLaterTurns(): Unit = runBlocking {
        val proving = CompletableDeferred<Unit>()
        val finishProof = CompletableDeferred<Unit>()
        var prompts = 0
        val process = object : AcpWorkerProcess, AcpReusableWorkerProof {
            override val supportsReusableTurns = true
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore) = AcpWorkerReady(true)
            override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject {
                prompts++
                return buildJsonObject { put("type", "Finished") }
            }
            override suspend fun requireInitialQuiescence() = Unit
            override suspend fun requireTurnQuiescence(turnId: String) { proving.complete(Unit); finishProof.await() }
            override suspend fun cancelAndConfirm() = false
            override suspend fun stopAndAwait() = Unit
        }
        val manager = AcpWorkerManager(AcpWorkerFactory { process }, AcpWorkspaceAuthority {}, backend, { false }, holdForSettlement = true)
        val first = "1fc44a2a-a164-4192-bf49-7f226c902f32"
        val second = "2fc44a2a-a164-4192-bf49-7f226c902f32"
        manager.prompt(binding(), config(), content, first)
        assertFailsWith<IllegalStateException> { manager.prompt(binding(), config(), content, second) }
        val committing = async { manager.withQuiescentTurn("acp:session", first) { "saved" } }
        withTimeout(5_000) { proving.await() }
        val later = async(start = CoroutineStart.UNDISPATCHED) { manager.prompt(binding(), config(), content, second) }
        assertFalse(later.isCompleted)
        assertEquals(1, prompts)
        finishProof.complete(Unit)
        withTimeout(5_000) { assertEquals("saved", committing.await()); later.await() }
        assertEquals(2, prompts)
        assertFailsWith<IllegalStateException> { manager.withQuiescentTurn("acp:session", first) { } }
        manager.withStoppedTurn("acp:session", second) { }
    }

    @Test fun reusableWorkerRejectsChangedStartupConfiguration(): Unit = runBlocking {
        var starts = 0
        var stops = 0
        val process = object : AcpWorkerProcess {
            override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore): AcpWorkerReady { starts++; return AcpWorkerReady(true) }
            override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker) = buildJsonObject { put("type", "Finished") }
            override suspend fun cancelAndConfirm() = false
            override suspend fun stopAndAwait() { stops++ }
        }
        val manager = AcpWorkerManager(AcpWorkerFactory { process }, AcpWorkspaceAuthority {}, backend, { false })
        manager.prompt(binding(), config(), content)
        val changed = AcpWorkerConfiguration("agent", listOf("different"), mapOf("SECRET" to "token"), JsonArray(emptyList()), true, setOf("messenger_learning"))
        assertFailsWith<IllegalStateException> { manager.prompt(binding(), changed, content) }
        assertEquals(1, starts)
        assertEquals(1, stops)
    }

}
