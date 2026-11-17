/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.core

import cc.ptoe.messenger.data.remote.NetworkClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RestCoreBridgeWearPaginationTest {
    private fun page(offset: Int, total: Int, next: String = if (offset + 500 < total) (offset + 500).toString() else "null"): String {
        val items = (offset until minOf(offset + 500, total)).joinToString(",") { "{\"id\":\"id-$it\"}" }
        return """{"items":[$items],"offset":$offset,"limit":500,"total":$total,"nextOffset":$next}"""
    }

    private fun withServer(handler: (String, Int) -> Pair<Int, String>, block: (RestCoreBridge) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/") { exchange ->
            val offset = exchange.requestURI.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("offset=") }
                ?.substringAfter('=')?.toInt() ?: 0
            val (status, body) = handler(exchange.requestURI.path, offset)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block(RestCoreBridge("127.0.0.1:${server.address.port}", "token")) } finally { server.stop(0) }
    }

    @Test fun completeAgentAndConversationSyncReadsEveryPage() {
        val calls = mutableListOf<Pair<String, Int>>()
        withServer({ path, offset -> calls.add(path to offset); 200 to page(offset, 1002) }) { bridge ->
            for (read in listOf(bridge::listAgentsForSyncJson, bridge::listConversationsForSyncJson)) {
                val rows = NetworkClient.json.parseToJsonElement(read()).jsonArray
                assertEquals(1002, rows.size)
                assertEquals("id-1001", rows.last().jsonObject.getValue("id").jsonPrimitive.content)
            }
            assertEquals(listOf(0, 500, 1000, 0, 500, 1000), calls.map { it.second })
        }
    }

    @Test fun laterPageFailuresAndInvalidContinuationsFailTheSync() {
        withServer({ _, offset -> if (offset == 0) 200 to page(0, 600) else 503 to "unavailable" }) { bridge ->
            assertFailsWith<Exception> { bridge.listAgentsForSyncJson() }
        }
        withServer({ _, _ -> 200 to page(0, 600, next = "0") }) { bridge ->
            assertFailsWith<Exception> { bridge.listConversationsForSyncJson() }
        }
        withServer({ _, offset -> if (offset == 0) 200 to page(0, 600) else 200 to page(500, 601) }) { bridge ->
            assertFailsWith<Exception> { bridge.listConversationsForSyncJson() }
        }
    }
}
