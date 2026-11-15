package cc.ptoe.messenger.core

import cc.ptoe.messenger.presentation.platform.ForegroundTaskRegistry
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ForegroundTaskLeaseTest {
    @Test
    fun chatAndAcpReleaseForegroundLeaseWhenRequestFails() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/") { exchange ->
                events.add("request")
                exchange.sendResponseHeaders(500, -1)
                exchange.close()
            }
            start()
        }
        ForegroundTaskRegistry.begin = { events.add("begin") }
        ForegroundTaskRegistry.end = { events.add("end") }
        try {
            val bridge = RestCoreBridge("127.0.0.1:${server.address.port}", "token")
            val config = TurnConfigBridge(
                conversationId = "c1", modelId = "model", baseUrl = "", apiKey = "", apiFormat = "chat",
                systemPrompt = "", temperature = null, topP = null, maxTokens = null, reasoningEffort = null,
                toolNames = emptyList(), writable = false, contextWindow = 0, summarizePrompt = "",
                titleAgentId = null, titleAgentSystemPrompt = null, titleAgentModelId = null,
                hasWorkspace = false, workspaceNote = "",
            )
            assertTrue(runCatching { bridge.runTurn(config, { _, _ -> "" to false }, {}) }.isFailure)
            assertEquals(listOf("begin", "request", "end"), events.toList())
            events.clear()
            assertTrue(runCatching {
                bridge.controlCommand("r1", buildJsonObject { put("action", "prompt") }) {}
            }.isFailure)
            assertEquals(listOf("begin", "request", "end"), events.toList())
            events.clear()
            assertTrue(runCatching {
                bridge.controlCommand("r2", buildJsonObject { put("action", "other") }) {}
            }.isFailure)
            assertEquals(listOf("request"), events.toList())
        } finally {
            ForegroundTaskRegistry.begin = null
            ForegroundTaskRegistry.end = null
            server.stop(0)
        }
    }
}
