package cc.ptoe.messenger.core

import cc.ptoe.messenger.presentation.platform.ForegroundTaskRegistry
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BackgroundTaskBridgeTest {
    @Test
    fun reconcilesAmbiguousNativeAdmissionAndStartsAcpWithLease() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var key: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/") { exchange ->
                val path = exchange.requestURI.path
                calls.add("${exchange.requestMethod} $path")
                when {
                    exchange.requestMethod == "POST" && path.endsWith("/background-turn") -> {
                        key = exchange.requestHeaders.getFirst("Idempotency-Key")
                        exchange.sendResponseHeaders(500, -1)
                    }
                    exchange.requestMethod == "GET" && path.endsWith("/background-turn") -> {
                        val bytes = "{\"active\":true,\"requestId\":\"$key\"}".encodeToByteArray()
                        exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
                    }
                    path.contains("/control/background/sessions/") -> exchange.sendResponseHeaders(404, -1)
                    else -> exchange.sendResponseHeaders(202, -1)
                }
                exchange.close()
            }
            start()
        }
        val lifecycle = mutableListOf<String>()
        ForegroundTaskRegistry.begin = { lifecycle += "begin" }
        ForegroundTaskRegistry.end = { lifecycle += "end" }
        ForegroundTaskRegistry.coreActive = { lifecycle += "active:$it" }
        try {
            val bridge = RestCoreBridge("127.0.0.1:${server.address.port}", "token")
            val config = TurnConfigBridge(
                conversationId = "c1", modelId = "model", baseUrl = "", apiKey = "", apiFormat = "chat",
                systemPrompt = "", temperature = null, topP = null, maxTokens = null, reasoningEffort = null,
                toolNames = emptyList(), writable = false, contextWindow = 0, summarizePrompt = "",
                titleAgentId = null, titleAgentSystemPrompt = null, titleAgentModelId = null,
                hasWorkspace = false, workspaceNote = "",
            )
            bridge.startBackgroundTurn(config)
            assertNotNull(key)
            assertEquals(listOf("begin", "active:true", "end"), lifecycle)
            assertEquals(1, calls.count { it.startsWith("POST /api/v1/conversations/") })
            lifecycle.clear()
            bridge.startBackgroundAcp("r1", buildJsonObject { put("action", "prompt") })
            assertEquals(listOf("begin", "active:true", "end"), lifecycle)
            assertNull(bridge.backgroundAcpBySession("acp:session"))
        } finally {
            ForegroundTaskRegistry.begin = null
            ForegroundTaskRegistry.end = null
            ForegroundTaskRegistry.coreActive = null
            server.stop(0)
        }
    }
}
