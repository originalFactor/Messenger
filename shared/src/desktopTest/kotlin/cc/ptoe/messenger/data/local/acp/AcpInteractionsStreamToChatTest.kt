/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpInteractionsStreamToChatTest {
    private fun stream() = AcpInteractionsStreamToChat("messenger/m", AcpNativeToChat { "chatcmpl_snapshot" }) { "chatcmpl_stream" }
    private fun data(wire: String): List<JsonObject> = wire.lineSequence().filter { it.startsWith("data: ") && it != "data: [DONE]" }
        .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }.toList()
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun event(type: String, vararg fields: Pair<String, JsonElement>): String = JsonObject(mapOf("event_type" to JsonPrimitive(type)) + fields.toMap()).toString()
    private fun obj(vararg fields: Pair<String, JsonElement>) = JsonObject(mapOf(*fields))
    private fun str(value: String) = JsonPrimitive(value)
    private fun num(value: Int) = JsonPrimitive(value)
    private fun created() = event("interaction.created", "interaction" to obj("id" to str("v1_test"), "model" to str("upstream"), "status" to str("in_progress")))
    private fun begin(index: Int, step: JsonObject) = event("step.start", "index" to num(index), "step" to step)
    private fun change(index: Int, delta: JsonObject) = event("step.delta", "index" to num(index), "delta" to delta)
    private fun stop(index: Int, usage: JsonObject? = null) = event("step.stop", "index" to num(index), "usage" to (usage ?: JsonNull))
    private fun completed(status: String = "completed", vararg fields: Pair<String, JsonElement>) = event("interaction.completed", "interaction" to JsonObject(
        mapOf("id" to str("v1_test"), "model" to str("upstream"), "status" to str(status)) + fields.toMap()))
    private fun delta(wire: String) = data(wire).single().getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject

    @Test fun officialTextExampleWithoutTerminalSteps() {
        val codec = stream(); codec.feed(created())
        codec.feed(begin(0, obj("type" to str("model_output"))))
        assertEquals("Hello, my name is Phil", delta(codec.feed(change(0, obj("type" to str("text"), "text" to str("Hello, my name is Phil"))))).getValue("content").jsonPrimitive.content)
        assertEquals(", and I live in Germany.", delta(codec.feed(change(0, obj("type" to str("text"), "text" to str(", and I live in Germany."))))).getValue("content").jsonPrimitive.content)
        codec.feed(stop(0, json("""{"total_input_tokens":7,"total_output_tokens":10,"total_tokens":17}""")))
        val wire = codec.feed(completed("completed", "usage" to json("""{"total_input_tokens":7,"total_output_tokens":12,"total_tokens":19}""")))
        val final = data(wire).single()
        assertEquals("stop", final.getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
        assertEquals(19, final.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.int)
        assertTrue(wire.endsWith("data: [DONE]\n\n")); assertTrue(codec.isFinished)
        assertFails { codec.feed(completed()) }; assertEquals("", codec.endOfStream())
    }

    @Test fun officialFunctionRequiresActionWithoutSnapshotAndBuffersFragments() {
        val codec = stream(); codec.feed(created())
        val call = obj("type" to str("function_call"), "id" to str("c1"), "name" to str("get_weather"), "arguments" to JsonObject(emptyMap()))
        assertEquals("", codec.feed(begin(0, call)))
        assertEquals("", codec.feed(change(0, obj("type" to str("arguments_delta"), "arguments" to str("{\"location\":")))))
        assertEquals("", codec.feed(change(0, obj("type" to str("arguments_delta"), "arguments" to str("\"Paris\"}")))))
        codec.feed(stop(0))
        val wire = codec.feed(completed("requires_action", "usage" to json("""{"total_input_tokens":10,"total_output_tokens":5,"total_thought_tokens":0,"total_tokens":15}""")))
        val chunks = data(wire)
        assertEquals(2, chunks.size)
        val tool = chunks.first().getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject.getValue("tool_calls").jsonArray.single().jsonObject
        assertEquals("c1", tool.getValue("id").jsonPrimitive.content)
        assertEquals("{\"location\":\"Paris\"}", tool.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
        assertEquals("tool_calls", chunks.last().getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
    }

    @Test fun reconcilesEquivalentStepsAndOutputsSnapshots() {
        val codec = stream(); codec.feed(created())
        val thoughtStart = codec.feed(begin(0, obj("type" to str("thought"))))
        assertEquals("", delta(thoughtStart).getValue("reasoning_content").jsonPrimitive.content)
        val thought = obj("type" to str("thought_summary"), "content" to obj("type" to str("text"), "text" to str("why")))
        assertEquals("why", delta(codec.feed(change(0, thought))).getValue("reasoning_content").jsonPrimitive.content)
        codec.feed(stop(0)); codec.feed(begin(1, obj("type" to str("model_output"))))
        assertEquals("answer", delta(codec.feed(change(1, obj("type" to str("text"), "text" to str("answer"))))).getValue("content").jsonPrimitive.content)
        codec.feed(stop(1)); codec.feed(begin(2, obj("type" to str("function_call"), "id" to str("c"), "name" to str("f"), "arguments" to json("""{"a":1,"b":2}"""))))
        codec.feed(stop(2))
        val steps = JsonArray(listOf(json("""{"type":"thought","summary":[{"type":"text","text":"why"}]}"""),
            json("""{"type":"model_output","content":[{"type":"text","text":"answer"}]}"""),
            json("""{"type":"function_call","id":"c","name":"f","arguments":{"a":1,"b":2}}""")))
        val outputs = JsonArray(listOf(json("""{"type":"thought","summary":[{"type":"text","text":"why"}]}"""),
            json("""{"type":"text","text":"answer"}"""), json("""{"type":"function_call","id":"c","name":"f","arguments":{"b":2,"a":1}}""")))
        val wire = codec.feed(completed("requires_action", "steps" to steps, "outputs" to outputs,
            "usage" to json("""{"total_input_tokens":10,"total_output_tokens":3,"total_thought_tokens":2,"total_tokens":15}""")))
        val final = data(wire).last()
        assertEquals("tool_calls", final.getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
        assertEquals(5, final.getValue("usage").jsonObject.getValue("completion_tokens").jsonPrimitive.int)
    }

    @Test fun incompleteRequiresExplicitRepresentableReason() {
        val codec = stream(); codec.feed(created()); codec.feed(begin(0, obj("type" to str("model_output"))))
        codec.feed(change(0, obj("type" to str("text"), "text" to str("partial")))); codec.feed(stop(0))
        val wire = codec.feed(completed("incomplete", "incomplete_details" to obj("reason" to str("max_output_tokens"))))
        assertEquals("length", data(wire).single().getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
        val missing = stream(); missing.feed(created()); assertFails { missing.feed(completed("incomplete")) }
        assertTrue(data(missing.fail("incomplete")).single().containsKey("error"))
    }

    @Test fun eofBareDoneAndDuplicateEventsCannotSucceed() {
        val eof = stream(); eof.feed(created()); eof.feed(begin(0, obj("type" to str("model_output"))))
        val error = eof.endOfStream(); assertTrue(data(error).single().containsKey("error")); assertFalse(error.contains("[DONE]"))
        val bare = stream(); bare.feed(created()); assertFails { bare.feed("[DONE]") }
        assertTrue(data(bare.fail("invalid upstream")).single().containsKey("error"))
        val duplicate = stream(); val input = event("interaction.created", "event_id" to str("evt_1"), "interaction" to obj("id" to str("v1_test"), "status" to str("in_progress")))
        duplicate.feed(input); assertFails { duplicate.feed(input) }
    }

    @Test fun rejectsMismatchesSignaturesUnknownsAndInvalidOrdering() {
        fun reject(events: List<String>) {
            val codec = stream(); assertFails { events.forEach { codec.feed(it) } }
            assertTrue(data(codec.fail("invalid upstream")).single().containsKey("error"))
        }
        val mismatch = JsonArray(listOf(json("""{"type":"text","text":"wrong"}""")))
        reject(listOf(created(), begin(0, obj("type" to str("model_output"))), change(0, obj("type" to str("text"), "text" to str("right"))), stop(0), completed("completed", "outputs" to mismatch)))
        reject(listOf(created(), begin(0, obj("type" to str("thought"))), change(0, obj("type" to str("thought_signature"), "signature" to str("opaque")))))
        reject(listOf(created(), begin(0, obj("type" to str("function_call"), "id" to str("c"), "name" to str("f"), "arguments" to JsonObject(emptyMap()), "signature" to str("opaque")))))
        reject(listOf(created(), begin(0, obj("type" to str("model_output"))), change(0, obj("type" to str("image"), "data" to str("base64")))))
        reject(listOf(created(), begin(0, obj("type" to str("model_output"))), stop(0), begin(1, obj("type" to str("thought")))))
        reject(listOf(created(), begin(0, obj("type" to str("model_output"))), completed()))
        reject(listOf(created(), completed("requires_action")))
        reject(listOf(created(), completed("completed", "continuation_token" to str("opaque"))))
        reject(listOf(created(), completed("completed", "service_tier" to str("standard"))))
        reject(listOf(created(), """{"event_type":"step.start","type":"step.delta","index":0,"step":{"type":"model_output"}}"""))
        reject(listOf(created(), """{"event_type":"interaction.completed","event_type":"interaction.completed"}"""))
    }

    @Test fun toolArgumentsMustBeCompleteUniqueAndProperlyStopped() {
        fun reject(arguments: String) {
            val codec = stream(); codec.feed(created()); codec.feed(begin(0, obj("type" to str("function_call"), "id" to str("c"), "name" to str("f"))))
            codec.feed(change(0, obj("type" to str("arguments_delta"), "arguments" to str(arguments))))
            assertFails { codec.feed(stop(0)) }
            assertTrue(data(codec.fail("invalid arguments")).single().containsKey("error"))
        }
        for (arguments in listOf("{", "[]", "{\"x\":1,\"x\":2}")) reject(arguments)
        val missing = stream(); missing.feed(created()); missing.feed(begin(0, obj("type" to str("function_call"), "id" to str("c"), "name" to str("f"))))
        assertFails { missing.feed(stop(0)) }
        val duplicate = stream(); duplicate.feed(created())
        val call = obj("type" to str("function_call"), "id" to str("c"), "name" to str("f"), "arguments" to JsonObject(emptyMap()))
        duplicate.feed(begin(0, call)); duplicate.feed(stop(0)); assertFails { duplicate.feed(begin(1, call)) }
        val ambiguous = stream(); ambiguous.feed(created()); ambiguous.feed(begin(0, obj("type" to str("function_call"), "id" to str("c"), "name" to str("f"), "arguments" to json("""{"x":1}"""))))
        assertFails { ambiguous.feed(change(0, obj("type" to str("arguments_delta"), "arguments" to str("{}")))) }
    }

    @Test fun rejectsAccountingLossAndRegressingUsage() {
        val creation = stream(); creation.feed(created())
        assertFails { creation.feed(completed("completed", "usage" to json("""{"total_input_tokens":10,"total_output_tokens":5,"total_tool_use_tokens":1,"total_tokens":15}"""))) }
        val modalities = stream(); modalities.feed(created())
        assertFails { modalities.feed(completed("completed", "usage" to json("""{"total_input_tokens":10,"total_output_tokens":5,"total_tokens":15,"input_tokens_by_modality":[{"modality":"text","tokens":10}]}"""))) }
        val decrease = stream(); decrease.feed(created()); decrease.feed(begin(0, obj("type" to str("model_output")))); decrease.feed(stop(0, json("""{"total_input_tokens":4,"total_output_tokens":5,"total_tokens":9}""")))
        assertFails { decrease.feed(completed("completed", "usage" to json("""{"total_input_tokens":4,"total_output_tokens":4,"total_tokens":8}"""))) }
        val total = stream(); total.feed(created())
        assertFails { total.feed(completed("completed", "usage" to json("""{"total_input_tokens":4,"total_output_tokens":5,"total_tokens":10}"""))) }
    }
}
