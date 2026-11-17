/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpModelRelayTest {
    private class Sink : AcpRelaySink {
        var status = 0
        var type: String? = null
        val output = java.io.ByteArrayOutputStream()
        override suspend fun start(status: Int, contentType: String?) { this.status = status; type = contentType }
        override suspend fun write(chunk: ByteArray) { output.write(chunk) }
    }
    private data class Capture(val path: String, val body: JsonObject, val headers: Map<String, List<String>>)
    private fun model(id: String, base: String, format: AcpRelayFormat, enabled: Boolean = true) =
        AcpRelayModel(id, "Display $id", "provider-$id", 2000, enabled, "upstream-$id", base, "provider-secret-$id", format)

    @Test fun fourNativeProtocolsPreserveBodiesAndBytesWithScopedCredentials(): Unit = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Capture>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val response = "event: custom\ndata:{\"unknown\":1}\ndata: 2\n\n".encodeToByteArray()
        server.createContext("/") { exchange ->
            val requestBody = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            requests.add(Capture(exchange.requestURI.path, requestBody, exchange.requestHeaders.mapKeys { it.key.lowercase() }))
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/v1"
        val formats = AcpRelayFormat.entries
        val available = formats.associate { it.wire to model(it.wire, base, it) }
        val mutableScope = formats.map { it.wire }.toMutableSet()
        val relay = AcpModelRelay(AcpRelayCatalog { available[it] }, mutableScope)
        try {
            mutableScope.clear()
            val token = relay.capability()
            val catalog = relay.models(token).toString()
            assertTrue("messenger/chat" in catalog)
            assertFalse("provider-secret" in catalog)
            assertFalse(relay.toString().contains(token))
            assertFalse(available.getValue("chat").toString().contains("provider-secret"))
            assertFailsWith<AcpRelayException> { relay.models("invalid") }
            relay.beginTurn("turn")
            for (format in formats) {
                val body = buildJsonObject {
                    put("model", "messenger/${format.wire}")
                    put("stream", true)
                    put("future", buildJsonObject { put("nested", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("x")))) })
                }
                val sink = Sink()
                relay.forward(token, format, body, "2024-01-01", "feature-1", sink)
                assertEquals(200, sink.status)
                assertEquals("text/event-stream", sink.type)
                assertContentEquals(response, sink.output.toByteArray())
                val captured = requests.last()
                assertEquals("/v1${format.path}", captured.path)
                assertEquals(JsonObject(body + ("model" to JsonPrimitive("upstream-${format.wire}"))), captured.body)
                assertEquals("Bearer provider-secret-${format.wire}", captured.headers["authorization"]!!.single())
                assertEquals(if (format == AcpRelayFormat.MESSAGES) "provider-secret-messages" else null, captured.headers["x-api-key"]?.single())
                assertEquals(if (format == AcpRelayFormat.INTERACTIONS) "provider-secret-interactions" else null, captured.headers["x-goog-api-key"]?.single())
                assertEquals(if (format == AcpRelayFormat.MESSAGES) "2024-01-01" else null, captured.headers["anthropic-version"]?.single())
                assertEquals(if (format == AcpRelayFormat.MESSAGES) "feature-1" else null, captured.headers["anthropic-beta"]?.single())
                assertEquals(null, captured.headers["cookie"])
            }
            assertEquals(4, requests.size)
            relay.endTurn("turn")
            assertFailsWith<IllegalStateException> { relay.models(token) }
            assertFailsWith<IllegalStateException> { relay.beginTurn("next") }
            assertFailsWith<AcpRelayException> { relay.forward(token, AcpRelayFormat.CHAT, buildJsonObject { put("model", "messenger/chat") }, null, null, Sink()) }
        } finally { relay.close(); server.stop(0) }
    }

    @Test fun redirectsErrorsDisabledModelsAndCrossFormatNeverTriggerAnotherRequest(): Unit = runBlocking {
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++
            val request = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            if (request["redirect"]?.jsonPrimitive?.booleanOrNull == true) {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${server.address.port}/stolen")
                exchange.sendResponseHeaders(307, -1)
            } else {
                val bytes = "provider-secret-live".encodeToByteArray()
                exchange.sendResponseHeaders(401, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val catalog = mapOf("live" to model("live", base, AcpRelayFormat.CHAT), "disabled" to model("disabled", base, AcpRelayFormat.CHAT, false))
        val relay = AcpModelRelay(AcpRelayCatalog { catalog[it] }, setOf("live", "disabled"))
        try {
            val token = relay.capability()
            assertFalse(relay.models(token).toString().contains("disabled"))
            relay.beginTurn("turn")
            val disabled = buildJsonObject { put("model", "messenger/disabled") }
            assertEquals("model_not_found", assertFailsWith<AcpRelayException> { relay.forward(token, AcpRelayFormat.CHAT, disabled, null, null, Sink()) }.code)
            val valid = buildJsonObject { put("model", "messenger/live") }
            assertEquals("unsupported_feature", assertFailsWith<AcpRelayException> { relay.forward(token, AcpRelayFormat.MESSAGES, valid, null, null, Sink()) }.code)
            val unsupportedStream = JsonObject(valid + mapOf("messages" to JsonArray(emptyList()), "stream" to JsonPrimitive(true), "unknown" to JsonPrimitive(true)))
            assertEquals("unsupported_feature", assertFailsWith<AcpRelayException> { relay.forward(token, AcpRelayFormat.MESSAGES, unsupportedStream, null, null, Sink()) }.code)
            assertEquals("unauthorized", assertFailsWith<AcpRelayException> { relay.forward("bad", AcpRelayFormat.CHAT, valid, null, null, Sink()) }.code)
            val rejected = Sink()
            relay.forward(token, AcpRelayFormat.CHAT, valid, null, null, rejected)
            assertEquals(401, rejected.status)
            assertFalse(rejected.output.toString().contains("provider-secret"))
            val redirect = Sink()
            relay.forward(token, AcpRelayFormat.CHAT, JsonObject(valid + ("redirect" to JsonPrimitive(true))), null, null, redirect)
            assertEquals(307, redirect.status)
            assertEquals(2, calls)
            relay.endTurn("turn")
        } finally { relay.close(); server.stop(0) }
    }

    @Test fun endingTurnCancelsAnInFlightStream(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.requestBody.readBytes()
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write("start".encodeToByteArray()); exchange.responseBody.flush(); entered.countDown()
                while (true) { Thread.sleep(25); exchange.responseBody.write("x".encodeToByteArray()); exchange.responseBody.flush() }
            } catch (_: Exception) { } finally { exchange.close() }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val relay = AcpModelRelay(AcpRelayCatalog { model("live", base, AcpRelayFormat.CHAT) }, setOf("live"))
        try {
            relay.beginTurn("turn")
            val job = async { relay.forward(relay.capability(), AcpRelayFormat.CHAT, buildJsonObject { put("model", "messenger/live"); put("stream", true) }, null, null, Sink()) }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            withTimeout(5000) { relay.endTurn("turn") }
            assertTrue(job.isCancelled)
        } finally { relay.close(); server.stop(0) }
    }
    @Test fun nonstreamResponsesToChatRoundTripUsesOneProviderCall(): Unit = runBlocking {
        var calls = 0
        var captured: JsonObject? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++
            captured = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val bytes = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","reasoning_content":"reason","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"patch","arguments":"{\"input\":\"edit\"}"}}]}}],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val relay = AcpModelRelay(AcpRelayCatalog { model("live", base, AcpRelayFormat.CHAT) }, setOf("live"))
        try {
            relay.beginTurn("turn")
            val body = Json.parseToJsonElement("""{"model":"messenger/live","input":"hello","tools":[{"type":"custom","name":"patch","format":{"type":"text"}}]}""").jsonObject
            val sink = Sink()
            relay.forward(relay.capability(), AcpRelayFormat.RESPONSES, body, null, null, sink)
            assertEquals(200, sink.status)
            assertEquals("application/json", sink.type)
            assertEquals("upstream-live", captured!!["model"]!!.jsonPrimitive.content)
            assertEquals("hello", captured!!["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content)
            val output = Json.parseToJsonElement(sink.output.toString()).jsonObject
            assertEquals("messenger/live", output["model"]!!.jsonPrimitive.content)
            assertEquals(listOf("reasoning", "custom_tool_call"), output["output"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
            assertEquals("edit", output["output"]!!.jsonArray.last().jsonObject["input"]!!.jsonPrimitive.content)
            assertEquals(5, output["usage"]!!.jsonObject["total_tokens"]!!.jsonPrimitive.int)
            assertEquals(1, calls)
            relay.endTurn("turn")
        } finally { relay.close(); server.stop(0) }
    }

    @Test fun responsesStreamingConvertsFramedChatEventsAndLateUsage(): Unit = runBlocking {
        var calls = 0
        var captured: JsonObject? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++
            captured = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val text = """data:{"choices":[
                |data: {"delta":{"content":"hello"},"finish_reason":null}]}
                |
                |data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
                |
                |data: {"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}
                |
                |data: [DONE]
                |
                |""".trimMargin().replace("\n", "\r\n").encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output -> text.forEach { output.write(byteArrayOf(it)); output.flush() } }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val relay = AcpModelRelay(AcpRelayCatalog { model("live", base, AcpRelayFormat.CHAT) }, setOf("live"))
        try {
            relay.beginTurn("turn")
            val body = buildJsonObject { put("model", "messenger/live"); put("input", "hi"); put("stream", true) }
            val sink = Sink()
            relay.forward(relay.capability(), AcpRelayFormat.RESPONSES, body, null, null, sink)
            assertEquals(200, sink.status)
            assertEquals("text/event-stream", sink.type)
            assertEquals(true, captured!!["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
            val output = sink.output.toString()
            assertTrue("response.output_text.delta" in output)
            assertTrue("hello" in output)
            assertEquals(1, Regex("event: response.completed").findAll(output).count())
            assertTrue("\"total_tokens\":5" in output)
            assertFalse("response.failed" in output)
            assertEquals(1, calls)
            relay.endTurn("turn")
        } finally { relay.close(); server.stop(0) }
    }

    @Test fun messagesAndInteractionsConvertThroughChatWithoutRetry(): Unit = runBlocking {
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++
            exchange.requestBody.readBytes()
            val bytes = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"answer","tool_calls":[{"id":"call","type":"function","function":{"name":"read","arguments":"{\"path\":\"a\"}"}}]}}],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}""".encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            for (format in listOf(AcpRelayFormat.MESSAGES, AcpRelayFormat.INTERACTIONS)) {
                val relay = AcpModelRelay(AcpRelayCatalog { model("live", base, AcpRelayFormat.CHAT) }, setOf("live"))
                try {
                    relay.beginTurn("turn")
                    val body = if (format == AcpRelayFormat.MESSAGES) Json.parseToJsonElement("""{"model":"messenger/live","messages":[{"role":"user","content":"hi"}],"max_tokens":100}""").jsonObject
                        else Json.parseToJsonElement("""{"model":"messenger/live","input":"hi"}""").jsonObject
                    val sink = Sink()
                    relay.forward(relay.capability(), format, body, null, null, sink)
                    val output = Json.parseToJsonElement(sink.output.toString()).jsonObject
                    val items = output[if (format == AcpRelayFormat.MESSAGES) "content" else "outputs"]!!.jsonArray
                    assertEquals("answer", items.first().jsonObject["text"]!!.jsonPrimitive.content)
                    assertEquals(if (format == AcpRelayFormat.MESSAGES) "tool_use" else "function_call", items.last().jsonObject["type"]!!.jsonPrimitive.content)
                    relay.endTurn("turn")
                } finally { relay.close() }
            }
            assertEquals(2, calls)
        } finally { server.stop(0) }
    }

    @Test fun messagesAndInteractionsStreamingProjectChatEvents(): Unit = runBlocking {
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++; exchange.requestBody.readBytes()
            val text = """data: {"choices":[{"delta":{"content":"hello"},"finish_reason":null}]}
                |
                |data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
                |
                |data: {"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}
                |
                |data: [DONE]
                |
                |""".trimMargin().encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, text.size.toLong()); exchange.responseBody.use { it.write(text) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            for (format in listOf(AcpRelayFormat.MESSAGES, AcpRelayFormat.INTERACTIONS)) {
                val relay = AcpModelRelay(AcpRelayCatalog { model("live", base, AcpRelayFormat.CHAT) }, setOf("live"))
                try {
                    relay.beginTurn("turn")
                    val body = if (format == AcpRelayFormat.MESSAGES) Json.parseToJsonElement("""{"model":"messenger/live","messages":[{"role":"user","content":"hi"}],"max_tokens":100,"stream":true}""").jsonObject
                        else Json.parseToJsonElement("""{"model":"messenger/live","input":"hi","stream":true}""").jsonObject
                    val sink = Sink()
                    relay.forward(relay.capability(), format, body, null, null, sink)
                    val output = sink.output.toString()
                    assertTrue("hello" in output)
                    val terminal = if (format == AcpRelayFormat.MESSAGES) "event: message_stop" else "event: interaction.completed"
                    assertEquals(1, Regex(terminal).findAll(output).count())
                    assertFalse("event: error" in output)
                    relay.endTurn("turn")
                } finally { relay.close() }
            }
            assertEquals(2, calls)
        } finally { server.stop(0) }
    }

    @Test fun allNativeProviderFormatsSupportValidatedNonstreamMatrix(): Unit = runBlocking {
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++; exchange.requestBody.readBytes()
            val raw = when (exchange.requestURI.path) {
                "/v1/responses" -> """{"object":"response","status":"completed","output":[{"type":"message","role":"assistant","status":"completed","content":[{"type":"output_text","text":"hello","annotations":[]}]}],"usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5}}"""
                "/v1/messages" -> """{"type":"message","role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"hello"}],"usage":{"input_tokens":2,"output_tokens":3}}"""
                "/v1/interactions" -> """{"status":"completed","outputs":[{"type":"text","text":"hello"}],"usage":{"total_input_tokens":2,"total_output_tokens":3,"total_tokens":5}}"""
                else -> error("Unexpected provider path")
            }
            val bytes = raw.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/v1"
        try {
            for (provider in listOf(AcpRelayFormat.RESPONSES, AcpRelayFormat.MESSAGES, AcpRelayFormat.INTERACTIONS)) {
                for (source in AcpRelayFormat.entries.filter { it != provider }) {
                    val relay = AcpModelRelay(AcpRelayCatalog { model("live", base, provider) }, setOf("live"))
                    try {
                        relay.beginTurn("turn")
                        val raw = when (source) {
                            AcpRelayFormat.CHAT -> """{"model":"messenger/live","messages":[{"role":"user","content":"hi"}],"max_tokens":100}"""
                            AcpRelayFormat.RESPONSES -> """{"model":"messenger/live","input":"hi","max_output_tokens":100}"""
                            AcpRelayFormat.MESSAGES -> """{"model":"messenger/live","messages":[{"role":"user","content":"hi"}],"max_tokens":100}"""
                            AcpRelayFormat.INTERACTIONS -> """{"model":"messenger/live","input":"hi","max_tokens":100}"""
                        }
                        val sink = Sink()
                        relay.forward(relay.capability(), source, Json.parseToJsonElement(raw).jsonObject, null, null, sink)
                        assertEquals(200, sink.status, "$source via $provider")
                        val result = sink.output.toString()
                        assertTrue("hello" in result, "$source via $provider: $result")
                        assertTrue("messenger/live" in result, "$source via $provider: $result")
                        assertFalse("provider-secret" in result)
                        relay.endTurn("turn")
                    } finally { relay.close() }
                }
            }
            assertEquals(9, calls)
        } finally { server.stop(0) }
    }

    @Test fun responsesProviderStreamsToOtherProtocolsWithVerifiedTerminal(): Unit = runBlocking {
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++; exchange.requestBody.readBytes()
            val stream = """data: {"type":"response.output_text.delta","output_index":0,"item_id":"m","delta":"hello"}
                |
                |data: {"type":"response.completed","response":{"object":"response","status":"completed","output":[{"type":"message","id":"m","role":"assistant","status":"completed","content":[{"type":"output_text","text":"hello","annotations":[]}]}],"usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5}}}
                |
                |""".trimMargin().encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, stream.size.toLong()); exchange.responseBody.use { it.write(stream) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/v1"
        try {
            for (format in listOf(AcpRelayFormat.CHAT, AcpRelayFormat.MESSAGES, AcpRelayFormat.INTERACTIONS)) {
                val relay = AcpModelRelay(AcpRelayCatalog { model("live", base, AcpRelayFormat.RESPONSES) }, setOf("live"))
                try {
                    relay.beginTurn("turn")
                    val raw = when (format) {
                        AcpRelayFormat.CHAT -> """{"model":"messenger/live","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"hi"}]}"""
                        AcpRelayFormat.MESSAGES -> """{"model":"messenger/live","stream":true,"max_tokens":100,"messages":[{"role":"user","content":"hi"}]}"""
                        else -> """{"model":"messenger/live","stream":true,"input":"hi"}"""
                    }
                    val sink = Sink()
                    relay.forward(relay.capability(), format, Json.parseToJsonElement(raw).jsonObject, null, null, sink)
                    val text = sink.output.toString()
                    assertTrue("hello" in text, "$format: $text")
                    val terminal = when (format) {
                        AcpRelayFormat.CHAT -> "data: [DONE]"
                        AcpRelayFormat.MESSAGES -> "event: message_stop"
                        else -> "event: interaction.completed"
                    }
                    assertEquals(1, Regex(Regex.escape(terminal)).findAll(text).count(), "$format: $text")
                    assertFalse("event: error" in text, "$format: $text")
                    relay.endTurn("turn")
                } finally { relay.close() }
            }
            assertEquals(3, calls)
        } finally { server.stop(0) }
    }

    @Test fun messagesProviderStreamsToOtherProtocolsWithVerifiedTerminal(): Unit = runBlocking {
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++; exchange.requestBody.readBytes()
            val stream = """data: {"type":"message_start","message":{"type":"message","role":"assistant","content":[],"usage":{"input_tokens":2,"output_tokens":0}}}
                |
                |data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
                |
                |data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}
                |
                |data: {"type":"content_block_stop","index":0}
                |
                |data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}
                |
                |data: {"type":"message_stop"}
                |
                |""".trimMargin().encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, stream.size.toLong()); exchange.responseBody.use { it.write(stream) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/v1"
        try {
            for (format in listOf(AcpRelayFormat.CHAT, AcpRelayFormat.RESPONSES, AcpRelayFormat.INTERACTIONS)) {
                val relay = AcpModelRelay(AcpRelayCatalog { model("live", base, AcpRelayFormat.MESSAGES) }, setOf("live"))
                try {
                    relay.beginTurn("turn")
                    val raw = when (format) {
                        AcpRelayFormat.CHAT -> """{"model":"messenger/live","stream":true,"max_tokens":100,"messages":[{"role":"user","content":"hi"}]}"""
                        AcpRelayFormat.RESPONSES -> """{"model":"messenger/live","stream":true,"max_output_tokens":100,"input":"hi"}"""
                        else -> """{"model":"messenger/live","stream":true,"max_tokens":100,"input":"hi"}"""
                    }
                    val sink = Sink()
                    relay.forward(relay.capability(), format, Json.parseToJsonElement(raw).jsonObject, null, null, sink)
                    val text = sink.output.toString()
                    assertTrue("hello" in text, "$format: $text")
                    val terminal = when (format) {
                        AcpRelayFormat.CHAT -> "data: [DONE]"
                        AcpRelayFormat.RESPONSES -> "event: response.completed"
                        else -> "event: interaction.completed"
                    }
                    assertEquals(1, Regex(Regex.escape(terminal)).findAll(text).count(), "$format: $text")
                    assertFalse("event: error" in text, "$format: $text")
                    relay.endTurn("turn")
                } finally { relay.close() }
            }
            assertEquals(3, calls)
        } finally { server.stop(0) }
    }

    @Test fun interactionsProviderStreamsToOtherProtocolsWithVerifiedTerminal(): Unit = runBlocking {
        var calls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls++; exchange.requestBody.readBytes()
            val stream = """data: {"event_type":"interaction.created","interaction":{"id":"i","status":"in_progress"}}
                |
                |data: {"event_type":"step.start","index":0,"step":{"type":"model_output"}}
                |
                |data: {"event_type":"step.delta","index":0,"delta":{"type":"text","text":"hello"}}
                |
                |data: {"event_type":"step.stop","index":0}
                |
                |data: {"event_type":"interaction.completed","interaction":{"id":"i","status":"completed","usage":{"total_input_tokens":2,"total_output_tokens":3,"total_tokens":5}}}
                |
                |""".trimMargin().encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, stream.size.toLong()); exchange.responseBody.use { it.write(stream) }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/v1"
        try {
            for (format in listOf(AcpRelayFormat.CHAT, AcpRelayFormat.RESPONSES, AcpRelayFormat.MESSAGES)) {
                val relay = AcpModelRelay(AcpRelayCatalog { model("live", base, AcpRelayFormat.INTERACTIONS) }, setOf("live"))
                try {
                    relay.beginTurn("turn")
                    val raw = when (format) {
                        AcpRelayFormat.CHAT -> """{"model":"messenger/live","stream":true,"max_tokens":100,"messages":[{"role":"user","content":"hi"}]}"""
                        AcpRelayFormat.RESPONSES -> """{"model":"messenger/live","stream":true,"max_output_tokens":100,"input":"hi"}"""
                        else -> """{"model":"messenger/live","stream":true,"max_tokens":100,"messages":[{"role":"user","content":"hi"}]}"""
                    }
                    val sink = Sink()
                    relay.forward(relay.capability(), format, Json.parseToJsonElement(raw).jsonObject, null, null, sink)
                    val text = sink.output.toString()
                    assertTrue("hello" in text, "$format: $text")
                    val terminal = when (format) {
                        AcpRelayFormat.CHAT -> "data: [DONE]"
                        AcpRelayFormat.RESPONSES -> "event: response.completed"
                        else -> "event: message_stop"
                    }
                    assertEquals(1, Regex(Regex.escape(terminal)).findAll(text).count(), "$format: $text")
                    assertFalse("event: error" in text, "$format: $text")
                    relay.endTurn("turn")
                } finally { relay.close() }
            }
            assertEquals(3, calls)
        } finally { server.stop(0) }
    }

}
