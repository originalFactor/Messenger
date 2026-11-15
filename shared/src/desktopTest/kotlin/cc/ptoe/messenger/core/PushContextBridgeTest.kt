package cc.ptoe.messenger.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PushContextBridgeTest {
    @Test fun preservesAcpConversationAndRemoteTarget() = runBlocking {
        val body = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v1/conversations/acp:session") { exchange ->
                val response = """{"id":"acp:session","title":"ACP task"}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            createContext("/api/v1/remote/status") { exchange ->
                val response = """{"deviceId":"desktop-1","connected":true,"onlineDevices":[]}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            createContext("/api/v1/cloud/push/events") { exchange ->
                body.set(exchange.requestBody.bufferedReader().readText())
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
        try {
            val bridge = RestCoreBridge("127.0.0.1:${server.address.port}", "token")
            val title = Json.parseToJsonElement(bridge.getConversationJson("acp:session")!!).jsonObject["title"]!!.jsonPrimitive.content
            val deviceId = bridge.remoteStatus()["deviceId"]!!.jsonPrimitive.content
            bridge.cloudPushEvent("approval", title, "remote", "acp:session", deviceId)
            val sent = Json.parseToJsonElement(body.get()).jsonObject
            assertEquals("ACP task", sent["title"]!!.jsonPrimitive.content)
            assertEquals("remote", sent["target"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
            assertEquals("acp:session", sent["target"]!!.jsonObject["conversationId"]!!.jsonPrimitive.content)
            assertEquals("desktop-1", sent["target"]!!.jsonObject["deviceId"]!!.jsonPrimitive.content)
            bridge.cloudPushEvent("completed", null, "local", "synced-chat", null)
            val local = Json.parseToJsonElement(body.get()).jsonObject["target"]!!.jsonObject
            assertEquals("local", local["kind"]!!.jsonPrimitive.content)
            assertEquals("synced-chat", local["conversationId"]!!.jsonPrimitive.content)
            assertNull(local["deviceId"])
        } finally {
            server.stop(0)
        }
    }
}
