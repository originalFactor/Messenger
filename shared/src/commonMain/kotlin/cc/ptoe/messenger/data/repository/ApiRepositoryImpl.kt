/*
 * Copyright 2026 ECSDevs
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

package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.data.remote.api.OpenAiClient
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ChatMessageDto
import cc.ptoe.messenger.data.remote.dto.ThinkingDto
import cc.ptoe.messenger.data.remote.dto.ToolCallDto
import cc.ptoe.messenger.data.remote.dto.ToolCallFunctionDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ChatStreamParser
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.model.applyModelsDev
import cc.ptoe.messenger.domain.repository.ApiRepository
import cc.ptoe.messenger.domain.repository.ModelsDevRepository
import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.presentation.utils.extractThinkContent
import cc.ptoe.messenger.presentation.utils.stripThinkBlock
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ApiRepositoryImpl(
    private val modelsDevRepository: ModelsDevRepository? = null
) : ApiRepository {

    private fun openAiClient(provider: Provider) = OpenAiClient(provider.baseUrl, provider.apiKey)

    override suspend fun fetchModels(provider: Provider): List<ChatModel> {
        return try {
            val response = openAiClient(provider).getModels()
            // 批量查询 models.dev 元数据，用于默认填充缺失的 context window/能力字段
            val metadataByModelId = buildMap {
                for (dto in response.data) {
                    if (dto.id !in this) {
                        this[dto.id] = runCatching { modelsDevRepository?.getMetadata(dto.id) }.getOrNull()
                    }
                }
            }
            response.data.map { modelDto ->
                ChatModel(
                    id = randomUuid(),
                    providerId = provider.id,
                    modelId = modelDto.id,
                    displayName = modelDto.id,
                    isEnabled = true,
                    contextWindow = modelDto.contextWindow ?: 0L,
                    inputRate = modelDto.inputRate,
                    outputRate = modelDto.outputRate,
                    createdAt = System.currentTimeMillis()
                ).applyModelsDev(metadataByModelId[modelDto.id])
            }
        } catch (e: ResponseException) {
            throw ApiException(extractHttpErrorMessage(e), e)
        } catch (e: Exception) {
            throw ApiException("Failed to fetch models: ${e.message}", e)
        }
    }

    override fun streamChatCompletion(
        provider: Provider,
        modelId: String,
        messages: List<Message>,
        systemPrompt: String?,
        temperature: Float,
        topP: Float,
        maxTokens: Int?,
        reasoningEffort: String?,
        reasoningFormat: String?,
        tools: List<ChatTool>?
    ): Flow<ChatStreamEvent> = flow {
        try {
            val requestMessages = buildRequestMessages(messages, systemPrompt, reasoningFormat)
            val (effort, thinking) = buildReasoningParams(reasoningEffort)
            val request = ChatCompletionRequestDto(
                model = modelId,
                messages = requestMessages,
                temperature = temperature,
                topP = topP,
                maxTokens = maxTokens,
                reasoningEffort = effort,
                thinking = thinking,
                tools = tools?.map { it.toToolSpecDto() },
                stream = true
            )
            val sseFlow = openAiClient(provider).createChatCompletionStream(request)
            val eventFlow = ChatStreamParser.parseToEvents(sseFlow)
            var hasFinished = false
            eventFlow.collect { event ->
                if (event is ChatStreamEvent.Done || event is ChatStreamEvent.Error) {
                    hasFinished = true
                }
                emit(event)
            }
            if (!hasFinished) {
                emit(ChatStreamEvent.Error("API 未返回有效数据，请检查 API 配置和参数"))
            }
        } catch (e: ResponseException) {
            emit(ChatStreamEvent.Error(extractHttpErrorMessage(e)))
        } catch (e: Exception) {
            emit(ChatStreamEvent.Error(e.message ?: "Unknown error"))
        }
    }

    override suspend fun createChatCompletion(
        provider: Provider,
        modelId: String,
        messages: List<Message>,
        systemPrompt: String?,
        temperature: Float,
        topP: Float,
        maxTokens: Int?,
        reasoningEffort: String?,
        reasoningFormat: String?
    ): Message {
        return try {
            val requestMessages = buildRequestMessages(messages, systemPrompt, reasoningFormat)
            val (effort, thinking) = buildReasoningParams(reasoningEffort)
            val request = ChatCompletionRequestDto(
                model = modelId,
                messages = requestMessages,
                temperature = temperature,
                topP = topP,
                maxTokens = maxTokens,
                reasoningEffort = effort,
                thinking = thinking,
                stream = false
            )
            val response = openAiClient(provider).createChatCompletion(request)
            val choice = response.choices.firstOrNull()
                ?: throw ApiException("No choices in response")
            val reasoning = choice.message.reasoningContent?.takeIf { it.isNotEmpty() }
                ?: choice.message.reasoning?.takeIf { it.isNotEmpty() }
            val contentText = extractResponseContent(choice.message.content)
            val finalContent = if (!reasoning.isNullOrEmpty()) {
                "<think>$reasoning</think>\n$contentText"
            } else {
                contentText
            }
            Message(
                id = randomUuid(),
                conversationId = "",
                role = MessageRole.ASSISTANT,
                content = finalContent,
                timestamp = System.currentTimeMillis(),
                status = cc.ptoe.messenger.domain.model.MessageStatus.SENT
            )
        } catch (e: ResponseException) {
            throw ApiException(extractHttpErrorMessage(e), e)
        } catch (e: Exception) {
            throw ApiException("Failed to create chat completion: ${e.message}", e)
        }
    }

    /**
     * Translate the domain [ChatTool] into the request `tools` entry. The
     * JSON Schema parameters string is parsed here so [ChatTool] itself
     * stays a plain string carrier.
     */
    private fun ChatTool.toToolSpecDto(): ToolSpecDto = ToolSpecDto(
        type = "function",
        function = ToolSpecFunctionDto(
            name = name,
            description = description,
            parameters = try {
                Json.parseToJsonElement(parametersJson)
            } catch (_: Exception) {
                buildJsonObject { }
            }
        )
    )

    /**
     * Build the request payload for the chat completion API.
     *
     * Pure-text messages are sent as a `content` string (the legacy
     * shape that every provider accepts). Multimodal messages are sent
     * as a `content` array using the OpenAI image_url / text parts so
     * vision-capable models can read the bitmap.
     *
     * Tool turns round-trip the persisted parts: an assistant message
     * carrying [ContentPart.ToolCall] parts is re-sent as an
     * assistant message with `tool_calls`, and a [MessageRole.TOOL]
     * message becomes a `role:"tool"` message with `tool_call_id` —
     * OpenAI rejects histories where these pairs are broken.
     *
     * The role string is converted here so the DTO stays independent of
     * the domain enum.
     */
    private fun buildRequestMessages(
        messages: List<Message>,
        systemPrompt: String?,
        reasoningFormat: String?
    ): List<ChatMessageDto> {
        val result = mutableListOf<ChatMessageDto>()
        if (!systemPrompt.isNullOrEmpty()) {
            result.add(ChatMessageDto(role = "system", content = JsonPrimitive(systemPrompt)))
        }
        messages.forEach { message ->
            val role = when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
                MessageRole.TOOL -> "tool"
            }
            val parts = message.parts
            if (role == "tool") {
                val toolResult = parts.filterIsInstance<ContentPart.ToolResult>().firstOrNull()
                result.add(ChatMessageDto(
                    role = "tool",
                    content = JsonPrimitive(toolResult?.output ?: message.content),
                    toolCallId = toolResult?.callId ?: ""
                ))
                return@forEach
            }
            val assistantToolCalls = if (role == "assistant") {
                parts.filterIsInstance<ContentPart.ToolCall>()
            } else {
                emptyList()
            }
            if (message.hasImages && parts.any { it is ContentPart.Image }) {
                result.add(ChatMessageDto(role = role, content = buildMultipartContent(parts)))
            } else if (assistantToolCalls.isNotEmpty()) {
                // 工具调用轮：文本（可能为空）与 tool_calls 一起回显。
                // reasoning_summary（GPT 等加密思维链模型）直接剥离思考块，
                // 不回传任何推理字段；think_tag 保留标签原样回显。
                val text = if (parts.isNotEmpty()) {
                    parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
                } else {
                    message.content
                }
                val (reasoning, mainContent) = when (reasoningFormat) {
                    "think_tag" -> null to text
                    "reasoning_summary" -> null to stripThinkBlock(text)
                    else -> extractThinkContent(text)
                }
                result.add(ChatMessageDto(
                    role = role,
                    content = if (mainContent.isEmpty()) JsonNull else JsonPrimitive(mainContent),
                    reasoningContent = reasoning,
                    toolCalls = assistantToolCalls.map {
                        ToolCallDto(
                            id = it.callId,
                            type = "function",
                            function = ToolCallFunctionDto(name = it.name, arguments = it.arguments)
                        )
                    }
                ))
            } else {
                // Text-only path: fall back to the legacy `content` string
                // so providers that don't accept arrays (or that mirror
                // OpenAI without vision) still get a working request.
                val text = if (parts.isNotEmpty()) {
                    parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
                } else {
                    message.content
                }
                // 当对话标注为 "reasoning_content" 格式时，将显示用的 <think> 标签
                // 还原为 reasoning_content 字段，避免把 think 标签作为 content 发送给 API。
                // 当 reasoningFormat 未定义（老对话）且内容包含 <think> 标签时，也提取推理内容发送，
                // 让 API 返回它偏好的格式，后续根据首次响应标注对话格式。
                // "reasoning_summary"（GPT 等加密思维链模型）剥离思考后仅发正文 —
                // 其思维链不可回传，回传 `reasoning_content` 字段会被严格网关拒绝。
                if (role == "assistant" && reasoningFormat == "reasoning_summary") {
                    val stripped = stripThinkBlock(text)
                    result.add(ChatMessageDto(
                        role = role,
                        content = if (stripped.isEmpty()) JsonNull else JsonPrimitive(stripped)
                    ))
                } else if ((reasoningFormat == "reasoning_content" || reasoningFormat == null) && role == "assistant") {
                    val (reasoning, mainContent) = extractThinkContent(text)
                    if (reasoning != null) {
                        result.add(ChatMessageDto(
                            role = role,
                            content = JsonPrimitive(mainContent),
                            reasoningContent = reasoning
                        ))
                    } else {
                        result.add(ChatMessageDto(role = role, content = JsonPrimitive(text)))
                    }
                } else {
                    result.add(ChatMessageDto(role = role, content = JsonPrimitive(text)))
                }
            }
        }
        return result
    }

    /**
     * Translate the domain [ContentPart] list to the OpenAI multipart
     * shape. We always emit `image_url` parts (with the data: URI
     * captured at send time) because that's the only variant the spec
     * defines for vision inputs. Text segments pass through as `text`.
     */
    private fun buildMultipartContent(parts: List<ContentPart>): JsonArray = buildJsonArray {
        parts.forEach { part ->
            when (part) {
                is ContentPart.Text -> add(buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(part.text))
                })
                is ContentPart.Image -> add(buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put("image_url", buildJsonObject {
                        put("url", JsonPrimitive(part.image.dataUri))
                    })
                })
                // 工具调用/结果不走 multipart 分支（上面已单独处理），此处仅为穷尽。
                is ContentPart.ToolCall, is ContentPart.ToolResult -> {}
            }
        }
    }

    /**
     * Coerce a [JsonElement] reply (string OR multipart array) into a
     * flat string the existing text bubble can render. For multipart
     * responses we keep the text segments verbatim and skip image parts
     * (they're displayed by the bubble separately if we ever add an
     * inline image renderer). Markdown image syntax like `![alt](url)`
     * from a model is preserved by leaving the raw markdown in text
     * parts.
     */
    private fun extractResponseContent(content: JsonElement): String {
        when (content) {
            is JsonPrimitive -> return if (content.isString) content.content else content.toString()
            is JsonArray -> {
                val parts = content.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                        "image_url" -> (obj["image_url"] as? JsonObject)
                            ?.get("url")?.jsonPrimitive?.contentOrNull
                            ?.let { "![image]($it)" }
                        else -> null
                    }
                }
                return parts.joinToString("\n")
            }
            else -> return content.toString()
        }
    }

    /**
     * Build reasoning parameters for API request.
     *
     * - null (Default): do not send any reasoning parameters (API default)
     * - "none": send both reasoning_effort="none" and thinking.type="disabled" for DeepSeek compatibility
     * - other: send only reasoning_effort with the specified value
     */
    private fun buildReasoningParams(reasoningEffort: String?): Pair<String?, ThinkingDto?> {
        return when (reasoningEffort) {
            null -> Pair(null, null)
            "none" -> Pair("none", ThinkingDto("disabled"))
            else -> Pair(reasoningEffort, null)
        }
    }

    private suspend fun extractHttpErrorMessage(e: ResponseException): String {
        val code = e.response.status.value
        val rawBody = try {
            e.response.bodyAsText()
        } catch (_: Exception) {
            null
        }
        if (rawBody.isNullOrEmpty()) {
            return "HTTP $code: ${e.message}"
        }
        return try {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(rawBody).jsonObject
            (json["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: json["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: rawBody
        } catch (_: Exception) {
            "HTTP $code: $rawBody"
        }
    }
}

class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
