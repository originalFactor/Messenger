/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomCloudTransportTest {
    @Test fun serverRetryHintsAndTimeoutResponsesDoNotRepeatRequests(): Unit = runBlocking {
        val count = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            count.incrementAndGet()
            if (exchange.requestURI.path == "/unavailable") exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(if (exchange.requestURI.path == "/unavailable") 503 else 408, -1)
            exchange.close()
        }
        server.start()
        val client = RoomCloudHttpClient()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertEquals(503, client.http.post("$base/unavailable") { setBody("mutation") }.status.value)
            assertEquals(1, count.get())
            assertEquals(408, client.http.post("$base/timeout") { setBody("mutation") }.status.value)
            assertEquals(2, count.get())
            assertEquals(503, client.http.get("$base/unavailable").status.value)
            assertEquals(3, count.get())
        } finally { client.close(); server.stop(0) }
    }
}
