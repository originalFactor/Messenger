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

class AcpGatewayCoreTest {
    private fun rpc(id: Int, method: String, params: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
    }
    private fun arguments(name: String) = buildJsonObject { put("name", name); put("arguments", JsonObject(emptyMap())) }
    private fun names(result: JsonObject) = result["result"]!!.jsonObject["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()

    @Test fun scopeIsFrozenAndFeatureOptOutAppliesToLiveCalls(): Unit = runBlocking {
        var enabled = false
        var imageCalls = 0
        val backend = object : AcpGatewayBackend {
            override suspend fun liveTools() = if (!enabled) emptyList() else listOf("messenger_learning", "messenger_agents", "messenger_generate_image").map { AcpGatewayTool(it, it, JsonObject(emptyMap())) }
            override suspend fun execute(turnId: String, name: String, arguments: JsonObject): ToolExecutionResult {
                if (name == "messenger_generate_image") { imageCalls++; return ToolExecutionResult("failed", isError = true) }
                return ToolExecutionResult("$turnId:$name", structuredContent = buildJsonObject { put("future", buildJsonObject { put("value", 42) }) })
            }
        }
        val gateway = AcpGatewayCore(backend, allowDelegationAtStartup = false, setOf("messenger_learning", "messenger_generate_image"))
        val secret = gateway.capability()
        assertFailsWith<SecurityException> { gateway.request("wrong", rpc(1, "tools/list")) }
        assertEquals(setOf("messenger_skills"), names(gateway.request(secret, rpc(1, "tools/list"))!!))
        enabled = true
        assertEquals(setOf("messenger_skills", "messenger_learning", "messenger_generate_image"), names(gateway.request(secret, rpc(2, "tools/list"))!!))
        val inactive = gateway.request(secret, rpc(3, "tools/call", arguments("messenger_learning")))!!
        assertTrue(inactive["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        gateway.beginTurn("turn")
        val learning = gateway.request(secret, rpc(4, "tools/call", arguments("messenger_learning")))!!["result"]!!.jsonObject
        assertFalse(learning["isError"]!!.jsonPrimitive.boolean)
        assertEquals(42, learning["structuredContent"]!!.jsonObject["future"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        val part = cc.ptoe.messenger.domain.model.ContentPart.ToolResult("call", "messenger_learning", "ok", false, learning["structuredContent"])
        val decoded = cc.ptoe.messenger.data.local.ContentPartCodec.decode(cc.ptoe.messenger.data.local.ContentPartCodec.encode(listOf(part))).single() as cc.ptoe.messenger.domain.model.ContentPart.ToolResult
        assertEquals(learning["structuredContent"], decoded.structuredContent)
        assertNotNullError(gateway.request(secret, rpc(5, "tools/call", arguments("messenger_agents")))!!)
        gateway.request(secret, rpc(6, "tools/call", arguments("messenger_generate_image")))
        gateway.request(secret, rpc(7, "tools/call", arguments("messenger_generate_image")))
        assertEquals(1, imageCalls)
        enabled = false
        assertNotNullError(gateway.request(secret, rpc(8, "tools/call", arguments("messenger_learning")))!!)
        gateway.endTurn("turn")
        gateway.close()
        assertFailsWith<IllegalStateException> { gateway.request(secret, rpc(9, "ping")) }
    }
    private fun assertNotNullError(response: JsonObject) { assertTrue(response["error"] is JsonObject) }

    @Test fun turnEndCancelsAndWaitsForOutstandingToolBeforeReusingWorker(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>(); val stopping = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val backend = object : AcpGatewayBackend {
            override suspend fun liveTools() = listOf(AcpGatewayTool("messenger_learning", "learning", JsonObject(emptyMap())))
            override suspend fun execute(turnId: String, name: String, arguments: JsonObject): ToolExecutionResult {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopping.complete(Unit); withContext(NonCancellable) { release.await() } }
            }
        }
        val gateway = AcpGatewayCore(backend, false, setOf("messenger_learning"))
        gateway.beginTurn("one")
        val pending = async { gateway.request(gateway.capability(), rpc(1, "tools/call", arguments("messenger_learning"))) }
        withTimeout(5000) { started.await() }
        val ending = async { gateway.endTurn("one") }
        withTimeout(5000) { stopping.await() }
        assertFalse(ending.isCompleted)
        assertFailsWith<IllegalStateException> { gateway.beginTurn("two") }
        release.complete(Unit)
        withTimeout(5000) { ending.await(); pending.await() }
        gateway.beginTurn("two")
        gateway.endTurn("two")
        gateway.close()
    }

    @Test fun permissionOptionsAreOneUseAndBoundToTheOwningTurn(): Unit = runBlocking {
        val broker = AcpPermissionBroker(timeoutMillis = 2000)
        val prompt = CompletableDeferred<AcpPermissionPrompt>()
        val resolved = CompletableDeferred<Unit>()
        val request = async { broker.request("turn", listOf(AcpPermissionOption("allow-real", "Allow", "allow_once")), "File", "detail") {
            if (it == null) resolved.complete(Unit) else prompt.complete(it)
        } }
        val option = withTimeout(5000) { prompt.await() }.options.single()
        assertFalse(broker.choose("other-turn", option.id))
        assertFalse(broker.choose("turn", "guessed"))
        assertTrue(broker.choose("turn", option.id))
        assertFalse(broker.choose("turn", option.id))
        assertEquals("allow-real", request.await())
        resolved.await()
        assertFalse(broker.choose("turn", option.id))
        broker.close()
        assertFailsWith<IllegalStateException> { broker.request("turn", listOf(AcpPermissionOption("x", "x", "allow_once")), "", "") {} }
    }
    @Test fun closingWaitsForOldPermissionUiToClear(): Unit = runBlocking {
        val broker = AcpPermissionBroker()
        val shown = CompletableDeferred<Unit>()
        val clearing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = async { broker.request("turn", listOf(AcpPermissionOption("allow", "Allow", "allow_once")), "", "") { prompt ->
            if (prompt != null) shown.complete(Unit) else { clearing.complete(Unit); release.await() }
        } }
        withTimeout(5000) { shown.await() }
        val closing = async { broker.close() }
        withTimeout(5000) { clearing.await() }
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        withTimeout(5000) { closing.await() }
        assertEquals(null, request.await())
        assertFailsWith<IllegalStateException> { broker.request("next", listOf(AcpPermissionOption("a", "A", "allow_once")), "", "") {} }
    }

    @Test fun imageCallsSerializeAndFailureOrCancellationBlocksAnotherCharge(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val backend = object : AcpGatewayBackend {
            override suspend fun liveTools() = listOf(AcpGatewayTool("messenger_generate_image", "image", JsonObject(emptyMap())))
            override suspend fun execute(turnId: String, name: String, arguments: JsonObject): ToolExecutionResult {
                calls++
                entered.complete(Unit)
                release.await()
                return ToolExecutionResult("provider failed", isError = true)
            }
        }
        val gateway = AcpGatewayCore(backend, false, setOf("messenger_generate_image"))
        val secret = gateway.capability()
        gateway.beginTurn("turn")
        val first = async { gateway.request(secret, rpc(1, "tools/call", arguments("messenger_generate_image"))) }
        withTimeout(5000) { entered.await() }
        val second = async { gateway.request(secret, rpc(2, "tools/call", arguments("messenger_generate_image"))) }
        yield()
        assertEquals(1, calls)
        release.complete(Unit)
        assertTrue(first.await()!!["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        assertTrue(second.await()!!["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        assertEquals(1, calls)
        gateway.endTurn("turn")
        gateway.close()

        val started = CompletableDeferred<Unit>()
        var ambiguousCalls = 0
        val ambiguous = object : AcpGatewayBackend {
            override suspend fun liveTools() = listOf(AcpGatewayTool("messenger_generate_image", "image", JsonObject(emptyMap())))
            override suspend fun execute(turnId: String, name: String, arguments: JsonObject): ToolExecutionResult { ambiguousCalls++; started.complete(Unit); awaitCancellation() }
        }
        val another = AcpGatewayCore(ambiguous, false, setOf("messenger_generate_image"))
        another.beginTurn("turn")
        val pending = async { another.request(another.capability(), rpc(3, "tools/call", arguments("messenger_generate_image"))) }
        withTimeout(5000) { started.await() }
        another.request(another.capability(), buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "notifications/cancelled"); put("params", buildJsonObject { put("requestId", 3) })
        })
        assertTrue(pending.await()!!["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        assertTrue(another.request(another.capability(), rpc(4, "tools/call", arguments("messenger_generate_image")))!!["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        assertEquals(1, ambiguousCalls)
        another.endTurn("turn")
        another.close()
    }

}
