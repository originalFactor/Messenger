/*
 * Copyright 2026 originalFactor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.data.remote.format

import cc.ptoe.messenger.data.remote.dto.ChatChoiceDto
import cc.ptoe.messenger.data.remote.dto.ChatCompletionResponseDto
import cc.ptoe.messenger.data.remote.dto.ChatMessageDto
import cc.ptoe.messenger.data.remote.dto.ToolCallDto
import cc.ptoe.messenger.data.remote.dto.ToolCallFunctionDto
import cc.ptoe.messenger.data.remote.dto.UsageDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.model.ApiFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Decoders for Responses / Messages / Interactions into the same event and
 * reply shapes the Chat Completions path produces, so the repository, the
 * agent loop, and the UI never branch on the provider's protocol.
 *
 * Reasoning is wrapped in `<think>` tags exactly like
 * [cc.ptoe.messenger.data.remote.sse.ChatStreamParser] and reported as
 * `reasoning_summary` (these protocols expose summaries, not raw chains).
 */
object ApiFormatStreamParser {

    fun parseToEvents(format: ApiFormat, jsonFlow: Flow<String>): Flow<ChatStreamEvent> = flow {
        val state = DecoderState()
        jsonFlow.collect { json ->
            if (json == "[DONE]") {
                return@collect
            }
            val payload = try {
                Json.parseToJsonElement(json) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
            if (payload == null) {
                state.invalidPayload()
                for (event in state.drain()) emit(event)
                return@collect
            }
            when (format) {
                ApiFormat.RESPONSES -> state.feedResponses(payload)
                ApiFormat.MESSAGES -> state.feedMessages(payload)
                ApiFormat.INTERACTIONS -> state.feedInteractions(payload)
                ApiFormat.CHAT, ApiFormat.AUTO -> Unit
            }
            for (event in state.drain()) emit(event)
        }
    }

    /**
     * Non-streaming reply → the Chat Completions DTO the rest of the app
     * already consumes (reasoning rides `reasoning_content`, tool calls are
     * complete entries).
     */
    fun decodeCompletion(format: ApiFormat, payload: JsonObject): ChatCompletionResponseDto {
        hostedSearchError(payload)?.let { throw IllegalStateException(it) }
        if (payload.str("stop_reason") == "pause_turn") {
            throw IllegalStateException(PAUSE_TURN_ERROR)
        }
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ToolCallDto>()
        var finishReason = "stop"
        var usage: UsageDto? = null

        fun addCall(id: String?, name: String?, arguments: JsonElement?) {
            if (name.isNullOrEmpty()) return
            toolCalls.add(
                ToolCallDto(
                    id = id ?: name,
                    type = "function",
                    function = ToolCallFunctionDto(
                        name = name,
                        arguments = arguments?.let { if (it is JsonPrimitive && it.isString) it.content else it.toString() } ?: "{}"
                    )
                )
            )
        }

        when (format) {
            ApiFormat.RESPONSES -> {
                payload.array("output").forEach { item ->
                    when (item.str("type")) {
                        "function_call" -> addCall(
                            item.str("call_id") ?: item.str("id"),
                            item.str("name"),
                            item["arguments"]
                        )
                        "reasoning" -> item.array("summary").forEach { summary ->
                            reasoning.append(summary.str("text") ?: "")
                        }
                        "message" -> item.array("content").forEach { part ->
                            if (part.str("type") == "output_text") {
                                text.append(part.str("text") ?: "")
                                part.array("annotations").forEach { citationLink(it)?.let(text::append) }
                            }
                        }
                    }
                }
                val rawUsage = payload["usage"] as? JsonObject
                if (rawUsage != null) {
                    usage = UsageDto(
                        promptTokens = rawUsage.int("input_tokens"),
                        completionTokens = rawUsage.int("output_tokens"),
                        totalTokens = rawUsage.int("total_tokens")
                    )
                }
                finishReason = if (toolCalls.isNotEmpty()) {
                    "tool_calls"
                } else if (payload.str("status") == "incomplete") {
                    "length"
                } else {
                    "stop"
                }
            }
            ApiFormat.MESSAGES -> {
                payload.array("content").forEach { block ->
                    when (block.str("type")) {
                        "text" -> {
                            text.append(block.str("text") ?: "")
                            block.array("citations").forEach { citationLink(it)?.let(text::append) }
                        }
                        "thinking" -> reasoning.append(block.str("thinking") ?: "")
                        "tool_use" -> addCall(block.str("id"), block.str("name"), block["input"])
                    }
                }
                val rawUsage = payload["usage"] as? JsonObject
                if (rawUsage != null) {
                    val prompt = rawUsage.int("input_tokens")
                    val completion = rawUsage.int("output_tokens")
                    usage = UsageDto(prompt, completion, prompt + completion)
                }
                finishReason = normalizeFinish(payload.str("stop_reason"), toolCalls.isNotEmpty())
            }
            ApiFormat.INTERACTIONS -> {
                payload.array("steps").forEach { step ->
                    when (step.str("type")) {
                        "function_call" -> addCall(step.str("id"), step.str("name"), step["arguments"])
                        "thought" -> step.array("summary").forEach { summary ->
                            reasoning.append(summary.str("text") ?: "")
                        }
                        "model_output" -> step.array("content").forEach { part ->
                            if (part.str("type") == "text") text.append(part.str("text") ?: "")
                        }
                    }
                }
                val rawUsage = payload["usage"] as? JsonObject
                if (rawUsage != null) {
                    usage = UsageDto(
                        promptTokens = rawUsage.int("total_input_tokens"),
                        completionTokens = rawUsage.int("total_output_tokens"),
                        totalTokens = rawUsage.int("total_tokens")
                    )
                }
                finishReason = if (toolCalls.isEmpty()) "stop" else "tool_calls"
            }
            ApiFormat.CHAT, ApiFormat.AUTO -> Unit
        }

        return ChatCompletionResponseDto(
            choices = listOf(
                ChatChoiceDto(
                    index = 0,
                    message = ChatMessageDto(
                        role = "assistant",
                        content = JsonPrimitive(text.toString()),
                        reasoningContent = reasoning.toString().ifEmpty { null },
                        toolCalls = toolCalls.ifEmpty { null }
                    ),
                    finishReason = finishReason
                )
            ),
            usage = usage
        )
    }

    /** Mutable per-stream state shared by the three decoders. */
    private class DecoderState {
        private val pending = mutableListOf<ChatStreamEvent>()
        private val toolCalls = linkedMapOf<Int, Triple<String, String, StringBuilder>>()
        private var usage: UsageDto? = null
        private var reasoningEmitted = false
        private var inThinkBlock = false
        private var finished = false
        private val citations = mutableSetOf<String>()

        private fun citation(value: JsonElement?) {
            val link = (value as? JsonObject)?.let(::citationLink) ?: return
            if (citations.add(link)) text(link)
        }

        fun invalidPayload() {
            if (!finished) fail("Invalid provider stream response")
        }

        private fun fail(message: String) {
            closeThink()
            pending.add(ChatStreamEvent.Error(message))
            finished = true
        }

        fun drain(): List<ChatStreamEvent> {
            val events = pending.toList()
            pending.clear()
            return events
        }

        private fun reasoning(text: String) {
            if (text.isEmpty()) return
            if (!reasoningEmitted) {
                reasoningEmitted = true
                pending.add(ChatStreamEvent.ReasoningDetected("reasoning_summary"))
            }
            if (!inThinkBlock) {
                pending.add(ChatStreamEvent.Content("<think>"))
                inThinkBlock = true
            }
            pending.add(ChatStreamEvent.Content(text))
        }

        private fun text(text: String) {
            if (text.isEmpty()) return
            if (text.isBlank() && inThinkBlock) return
            closeThink()
            pending.add(ChatStreamEvent.Content(text))
        }

        private fun closeThink() {
            if (!inThinkBlock) return
            inThinkBlock = false
            pending.add(ChatStreamEvent.Content("</think>\n"))
        }

        private fun completeToolCalls(): List<ToolCallData> = toolCalls.entries
            .sortedBy { it.key }
            .mapNotNull { (index, call) ->
                val (id, name, arguments) = call
                if (name.isEmpty()) return@mapNotNull null
                ToolCallData(
                    callId = id.ifEmpty { "call_$index" },
                    name = name,
                    arguments = arguments.toString().ifEmpty { "{}" }
                )
            }

        fun feedResponses(record: JsonObject) {
            if (finished) return
            when (record.str("type")) {
                "response.output_text.delta" -> text(record.str("delta") ?: "")
                "response.output_text.annotation.added" -> citation(record["annotation"])
                "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                    reasoning(record.str("delta") ?: "")
                "response.output_item.added" -> {
                    val item = record["item"] as? JsonObject
                    if (item?.str("type") == "function_call") {
                        val index = record.int("output_index")
                        toolCalls[index] = Triple(
                            item.str("call_id") ?: item.str("id") ?: "",
                            item.str("name") ?: "",
                            StringBuilder()
                        )
                    }
                }
                "response.function_call_arguments.delta" -> {
                    val index = record.int("output_index")
                    val call = toolCalls.getOrPut(index) { Triple("", "", StringBuilder()) }
                    call.third.append(record.str("delta") ?: "")
                }
                "response.completed", "response.incomplete" -> {
                    val response = record["response"] as? JsonObject ?: record
                    (response["usage"] as? JsonObject)?.let { rawUsage ->
                        usage = UsageDto(
                            promptTokens = rawUsage.int("input_tokens"),
                            completionTokens = rawUsage.int("output_tokens"),
                            totalTokens = rawUsage.int("total_tokens")
                        )
                    }
                    response.array("output").forEachIndexed { index, item ->
                        if (item.str("type") == "message") {
                            item.array("content").forEach { part ->
                                part.array("annotations").forEach { citation(it) }
                            }
                        }
                        if (item.str("type") != "function_call") return@forEachIndexed
                        val call = toolCalls.getOrPut(index) { Triple("", "", StringBuilder()) }
                        if (call.first.isEmpty()) {
                            toolCalls[index] = Triple(
                                item.str("call_id") ?: item.str("id") ?: "",
                                call.second.ifEmpty { item.str("name") ?: "" },
                                if (call.third.isEmpty()) {
                                    StringBuilder(item["arguments"]?.jsonString() ?: "{}")
                                } else call.third
                            )
                        }
                    }
                    finished = true
                    closeThink()
                    val calls = completeToolCalls()
                    pending.add(
                        ChatStreamEvent.Done(
                            finishReason = when {
                                calls.isNotEmpty() -> "tool_calls"
                                record.str("type") == "response.incomplete" -> "length"
                                else -> "stop"
                            },
                            usage = usage,
                            toolCalls = calls
                        )
                    )
                }
                "response.failed", "error" -> {
                    val message = ((record["error"] as? JsonObject)?.str("message"))
                        ?: (((record["response"] as? JsonObject)?.get("error") as? JsonObject)?.str("message"))
                        ?: "Upstream stream failed."
                    fail(message)
                }
            }
        }

        fun feedMessages(record: JsonObject) {
            if (finished) return
            when (record.str("type")) {
                "message_start" -> {
                    val rawUsage = ((record["message"] as? JsonObject)?.get("usage")) as? JsonObject
                    if (rawUsage != null) {
                        usage = UsageDto(
                            promptTokens = rawUsage.int("input_tokens"),
                            completionTokens = rawUsage.int("output_tokens"),
                            totalTokens = rawUsage.int("input_tokens") + rawUsage.int("output_tokens")
                        )
                    }
                }
                "content_block_start" -> {
                    val block = record["content_block"] as? JsonObject
                    block?.let { hostedSearchError(it) }?.let { fail(it); return }
                    block?.array("citations")?.forEach { citation(it) }
                    if (block?.str("type") == "tool_use") {
                        val index = record.int("index")
                        toolCalls[index] = Triple(
                            block.str("id") ?: "",
                            block.str("name") ?: "",
                            StringBuilder()
                        )
                    }
                }
                "content_block_delta" -> {
                    val delta = record["delta"] as? JsonObject ?: return
                    when (delta.str("type")) {
                        "text_delta" -> text(delta.str("text") ?: "")
                        "thinking_delta" -> reasoning(delta.str("thinking") ?: "")
                        "citations_delta" -> citation(delta["citation"])
                        "input_json_delta" -> {
                            val index = record.int("index")
                            val call = toolCalls.getOrPut(index) { Triple("", "", StringBuilder()) }
                            call.third.append(delta.str("partial_json") ?: "")
                        }
                    }
                }
                "message_delta" -> {
                    val rawUsage = record["usage"] as? JsonObject
                    if (rawUsage != null) {
                        val prompt = usage?.promptTokens ?: 0
                        val completion = rawUsage.int("output_tokens")
                        usage = UsageDto(prompt, completion, prompt + completion)
                    }
                    val stopReason = (record["delta"] as? JsonObject)?.str("stop_reason")
                    if (stopReason == "pause_turn") { fail(PAUSE_TURN_ERROR); return }
                    if (stopReason != null) {
                        finished = true
                        closeThink()
                        pending.add(
                            ChatStreamEvent.Done(
                                finishReason = normalizeFinish(stopReason, toolCalls.isNotEmpty()),
                                usage = usage,
                                toolCalls = completeToolCalls()
                            )
                        )
                    }
                }
                "message_stop" -> Unit
                "error" -> {
                    val message = ((record["error"] as? JsonObject)?.str("message"))
                        ?: (((record["response"] as? JsonObject)?.get("error") as? JsonObject)?.str("message"))
                        ?: "Upstream stream failed."
                    fail(message)
                }
            }
        }

        fun feedInteractions(record: JsonObject) {
            if (finished) return
            when (record.str("event_type") ?: record.str("type")) {
                "step.start" -> {
                    val step = record["step"] as? JsonObject
                    if (step?.str("type") == "function_call") {
                        val index = record.int("index")
                        toolCalls[index] = Triple(
                            step.str("id") ?: "",
                            step.str("name") ?: "",
                            StringBuilder()
                        )
                    }
                }
                "step.delta" -> {
                    val delta = record["delta"] as? JsonObject ?: return
                    when (delta.str("type")) {
                        "text" -> text(delta.str("text") ?: "")
                        "thought_summary" -> reasoning(
                            ((delta["content"] as? JsonObject)?.str("text")) ?: ""
                        )
                        "arguments_delta" -> {
                            val index = record.int("index")
                            val call = toolCalls.getOrPut(index) { Triple("", "", StringBuilder()) }
                            call.third.append(delta.str("arguments") ?: "")
                        }
                    }
                }
                "interaction.completed" -> {
                    val interaction = record["interaction"] as? JsonObject ?: record
                    (interaction["usage"] as? JsonObject)?.let { rawUsage ->
                        usage = UsageDto(
                            promptTokens = rawUsage.int("total_input_tokens"),
                            completionTokens = rawUsage.int("total_output_tokens"),
                            totalTokens = rawUsage.int("total_tokens")
                        )
                    }
                    finished = true
                    closeThink()
                    pending.add(
                        ChatStreamEvent.Done(
                            finishReason = if (toolCalls.isEmpty()) "stop" else "tool_calls",
                            usage = usage,
                            toolCalls = completeToolCalls()
                        )
                    )
                }
                "error" -> {
                    val message = ((record["error"] as? JsonObject)?.str("message"))
                        ?: (((record["response"] as? JsonObject)?.get("error") as? JsonObject)?.str("message"))
                        ?: "Upstream stream failed."
                    fail(message)
                }
            }
        }
    }
}

private fun normalizeFinish(reason: String?, hasToolCalls: Boolean): String {
    if (hasToolCalls) return "tool_calls"
    return when (reason) {
        "max_tokens", "length" -> "length"
        "tool_use", "tool_calls", "function_call" -> "tool_calls"
        "content_filter", "safety", "refusal" -> "content_filter"
        else -> "stop"
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.longOrNull?.toInt() } ?: 0

private fun JsonObject.array(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

private fun JsonElement.str(key: String): String? = (this as? JsonObject)?.str(key)

private fun JsonElement.array(key: String): List<JsonObject> =
    (this as? JsonObject)?.array(key) ?: emptyList()

private fun JsonElement.jsonString(): String =
    if (this is JsonPrimitive && isString) content else toString()

private const val PAUSE_TURN_ERROR = "Native web search paused by provider (pause_turn); automatic continuation is not enabled."

internal fun hostedSearchError(value: JsonElement): String? = when (value) {
    is JsonObject -> if (value.str("type") == "web_search_tool_result_error") {
        "Native web search failed: ${value.str("error_code") ?: "unknown_error"}"
    } else value.values.firstNotNullOfOrNull(::hostedSearchError)
    is JsonArray -> value.firstNotNullOfOrNull(::hostedSearchError)
    else -> null
}

private fun citationLink(citation: JsonObject): String? {
    val rawUrl = citation.str("url") ?: return null
    if (!rawUrl.startsWith("https://") && !rawUrl.startsWith("http://")) return null
    val url = rawUrl.replace(" ", "%20").replace("<", "%3C").replace(">", "%3E")
        .replace("\n", "%0A").replace("\r", "%0D")
    val title = (citation.str("title") ?: "Source").replace("\\", "\\\\")
        .replace("[", "\\[").replace("]", "\\]").replace("\n", " ").replace("\r", " ")
    return " [$title](<$url>)"
}
