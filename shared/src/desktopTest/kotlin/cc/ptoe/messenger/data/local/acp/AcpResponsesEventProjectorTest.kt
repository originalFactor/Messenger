/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class AcpResponsesEventProjectorTest {
    @Test fun projectsTextToolAndLateUsageToBothProtocols() {
        for (format in listOf(AcpRelayFormat.CHAT, AcpRelayFormat.MESSAGES, AcpRelayFormat.INTERACTIONS)) {
            val source = AcpChatResponsesStream("messenger/m", emptySet(), { "resp_test" }, { 100 })
            val target = AcpResponsesEventProjector(format, "messenger/m")
            val output = StringBuilder(target.project(source.start()))
            output.append(target.project(source.feed("""{"choices":[{"delta":{"content":"hello"},"finish_reason":null}]}""")))
            output.append(target.project(source.feed("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","type":"function","function":{"name":"read","arguments":"{\"path\":\"a\"}"}}]},"finish_reason":"tool_calls"}]}""")))
            output.append(target.project(source.feed("""{"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}""")))
            output.append(target.project(source.feed("[DONE]")))
            val rendered = output.toString()
            assertTrue("hello" in rendered)
            assertTrue("read" in rendered)
            if (format == AcpRelayFormat.CHAT) {
                assertTrue("\"content\":\"hello\"" in rendered)
                assertTrue("\"finish_reason\":\"tool_calls\"" in rendered)
                val calls = rendered.lineSequence().filter { it.startsWith("data: ") && it != "data: [DONE]" }
                    .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
                    .mapNotNull { it["choices"]?.jsonArray?.singleOrNull()?.jsonObject?.get("delta")?.jsonObject?.get("tool_calls") as? JsonArray }
                    .single()
                assertEquals(0, calls.single().jsonObject["index"]!!.jsonPrimitive.int)
                assertEquals(1, Regex("data: \\[DONE\\]").findAll(rendered).count())
            } else if (format == AcpRelayFormat.MESSAGES) {
                assertTrue("event: message_start" in rendered)
                assertTrue("event: content_block_delta" in rendered)
                assertTrue("\"stop_reason\":\"tool_use\"" in rendered)
                assertEquals(1, Regex("event: message_stop").findAll(rendered).count())
            } else {
                assertTrue("event: interaction.created" in rendered)
                assertTrue("event: step.delta" in rendered)
                assertTrue("\"status\":\"requires_action\"" in rendered)
                assertTrue("\"total_tokens\":5" in rendered)
                assertEquals(1, Regex("event: interaction.completed").findAll(rendered).count())
            }
            assertTrue(target.isFinished)
            assertEquals("", target.fail())
        }
    }

    @Test fun unrepresentableUsageProducesOnlyOneErrorTerminal() {
        val source = AcpChatResponsesStream("m", emptySet(), { "resp_test" }, { 100 })
        val target = AcpResponsesEventProjector(AcpRelayFormat.MESSAGES, "m")
        target.project(source.start())
        target.project(source.feed("""{"choices":[{"delta":{"content":"x"},"finish_reason":"stop"}]}"""))
        target.project(source.feed("""{"choices":[],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5,"prompt_tokens_details":{"cached_tokens":1}}}"""))
        assertFails { target.project(source.feed("[DONE]")) }
        val failure = target.fail()
        assertTrue("event: error" in failure)
        assertFalse("event: message_stop" in failure)
        assertEquals("", target.fail())
    }
}
