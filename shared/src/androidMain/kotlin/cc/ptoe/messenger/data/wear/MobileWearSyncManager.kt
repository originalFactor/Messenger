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

package cc.ptoe.messenger.data.wear

import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.model.Provider
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.util.UUID

/**
 * Shared handler for chat and new-conversation requests coming from the
 * Wear OS companion. Used by the WebSocket server in [MobileHttpServer] —
 * the transport changed from DataLayer → Bluetooth RFCOMM → WebSocket, but
 * the request/response business logic is the same.
 */
class MobileWearChatHandler(private val app: AppContainer) {

    /**
     * Streaming wear chat handler. Instead of buffering the whole reply into
     * a single frame, it:
     *
     *  1. Inserts the user message and a SENDING assistant placeholder into
     *     Room immediately (so the phone's own chat UI shows a pending bubble).
     *  2. Drives [ApiRepository.streamChatCompletion], updating the assistant
     *     message content on every delta (so the phone streams too).
     *  3. Pushes incremental frames to the watch via [sendFrame]:
     *
     *       chat_delta  {"requestId","delta"}      (zero or more)
     *       chat_done   {"requestId","content","userMessageId","assistantMessageId"}
     *       chat_error   {"requestId","error"}
     *
     *     Exactly one terminal frame (chat_done / chat_error) is always sent.
     *
     * Replaces the previous single-shot [createChatCompletion] path, which
     * meant the watch sat on a "Thinking..." bubble for the whole duration
     * and neither side saw streaming.
     */
    suspend fun handleChatRequestStreaming(
        payloadBytes: ByteArray,
        sendFrame: (JSONObject) -> Unit
    ) {
        val request = JSONObject(payloadBytes.decodeToString())
        val requestId = request.optString("requestId")

        fun frame(type: String, block: JSONObject.() -> Unit) {
            sendFrame(JSONObject().apply {
                put("requestId", requestId)
                put("type", type)
                block()
            })
        }

        try {
            val conversationId = request.optString("conversationId")
            val text = request.optString("text").trim()
            if (conversationId.isBlank()) {
                throw IllegalArgumentException("Conversation is required.")
            }
            if (text.isBlank()) {
                throw IllegalArgumentException("Message is empty.")
            }

            val conversation = app.conversationRepository.getById(conversationId).first()
                ?: throw IllegalStateException("Conversation not found on phone.")
            val agent = app.agentRepository.getById(conversation.agentId).first()
                ?: throw IllegalStateException("Agent not found on phone.")
            val resolvedAgent = resolveAgent(agent, conversation)
            if (resolvedAgent.defaultModelId == null) {
                throw IllegalStateException("This agent has no model set. Configure it on your phone first.")
            }
            val activeModel = resolveModelAndProvider(resolvedAgent)
                ?: throw IllegalStateException("No enabled model is available for this agent.")

            val now = System.currentTimeMillis()
            val userMessage = Message(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = MessageRole.USER,
                content = text,
                timestamp = now,
                status = MessageStatus.SENT
            )
            app.messageRepository.insert(userMessage)

            val assistantMessageId = UUID.randomUUID().toString()
            val assistantMessage = Message(
                id = assistantMessageId,
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = "",
                timestamp = System.currentTimeMillis(),
                status = MessageStatus.SENDING
            )
            app.messageRepository.insert(assistantMessage)

            val bridge = app.coreBridge
            val titleHolder = app.agentRepository.getAll().first().firstOrNull { it.role == Agent.ROLE_TITLE }
            val config = TurnConfigBridge(
                conversationId = conversationId,
                modelId = activeModel.second.modelId,
                baseUrl = activeModel.first.baseUrl,
                apiKey = activeModel.first.apiKey,
                apiFormat = activeModel.first.apiFormat.requestFormat.wireName,
                systemPrompt = resolvedAgent.systemPrompt.orEmpty(),
                temperature = resolvedAgent.temperature.toDouble(),
                topP = resolvedAgent.topP.toDouble(),
                maxTokens = resolvedAgent.maxTokens?.toLong(),
                reasoningEffort = resolvedAgent.reasoningEffort,
                toolNames = emptyList(),
                writable = conversation.writable,
                contextWindow = activeModel.second.contextWindow,
                summarizePrompt = "Please summarize the conversation so far.",
                titleAgentId = titleHolder?.id,
                titleAgentSystemPrompt = titleHolder?.systemPrompt,
                titleAgentModelId = titleHolder?.defaultModelId,
                // The watch declares no tools at all (see toolNames), so it
                // has no workspace either.
                hasWorkspace = false,
                workspaceNote = ""
            )

            var currentContent = ""
            bridge.runTurn(
                config = config,
                toolExecutor = { _, _ -> "Tool execution disabled on watch" to true },
                onEventJson = { eventJson ->
                    val root = try {
                        NetworkClient.json.parseToJsonElement(eventJson).jsonObject
                    } catch (_: Exception) {
                        return@runTurn
                    }
                    val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return@runTurn
                    when (type) {
                        "TextDelta" -> {
                            val delta = root["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            currentContent += delta
                            frame("chat_delta") { put("delta", delta) }
                        }
                        "Finished" -> {
                            frame("chat_done") {
                                put("content", currentContent)
                                put("userMessageId", userMessage.id)
                                put("assistantMessageId", assistantMessage.id)
                            }
                        }
                        "Error" -> {
                            val msg = root["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                            if (currentContent.isNotBlank()) {
                                frame("chat_done") {
                                    put("content", currentContent)
                                    put("userMessageId", userMessage.id)
                                    put("assistantMessageId", assistantMessage.id)
                                }
                            }
                            frame("chat_error") { put("error", msg) }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            frame("chat_error") { put("error", e.message ?: "Unknown error") }
        }
    }

    suspend fun handleNewConversation(payloadBytes: ByteArray): ByteArray {
        val request = JSONObject(payloadBytes.decodeToString())
        val requestId = request.optString("requestId")
        val response = JSONObject().apply { put("requestId", requestId) }

        try {
            val agentId = request.optString("agentId").ifBlank { null }
            val agent = if (agentId != null) {
                app.agentRepository.getById(agentId).first()
            } else {
                app.agentRepository.getAll().first().firstOrNull { it.isDefault }
                    ?: app.agentRepository.getAll().first()
                        .firstOrNull { it.role != Agent.ROLE_TITLE }
            } ?: throw IllegalStateException("No agents available on phone.")

            val now = System.currentTimeMillis()
            val providerId = determineProviderId(agent)
            val conversation = Conversation(
                id = UUID.randomUUID().toString(),
                title = "新对话",
                providerId = providerId,
                agentId = agent.id,
                createdAt = now,
                updatedAt = now,
                lastMessage = null
            )
            app.conversationRepository.insert(conversation)
            response.put("conversationId", conversation.id)
            response.put("agentId", agent.id)
        } catch (e: Exception) {
            response.put("error", e.message ?: "Unknown error")
        }

        return response.toString().encodeToByteArray()
    }

    private suspend fun resolveAgent(agent: Agent, conversation: Conversation): Agent {
        val withDefault = if (agent.isDefault) {
            agent
        } else {
            val defaultAgent = app.agentRepository.getAll().first().firstOrNull { it.isDefault }
                ?: agent
            agent.copy(
                systemPrompt = if (agent.followDefaultSystemPrompt) {
                    defaultAgent.systemPrompt
                } else {
                    agent.systemPrompt
                },
                defaultModelId = if (agent.followDefaultModel) {
                    defaultAgent.defaultModelId
                } else {
                    agent.defaultModelId
                },
                temperature = if (agent.followDefaultTemperature) {
                    defaultAgent.temperature
                } else {
                    agent.temperature
                },
                topP = if (agent.followDefaultTopP) {
                    defaultAgent.topP
                } else {
                    agent.topP
                },
                maxTokens = if (agent.followDefaultMaxTokens) {
                    defaultAgent.maxTokens
                } else {
                    agent.maxTokens
                }
            )
        }
        return withDefault.copy(
            defaultModelId = conversation.overrideModelId ?: withDefault.defaultModelId,
            temperature = conversation.overrideTemperature ?: withDefault.temperature,
            topP = conversation.overrideTopP ?: withDefault.topP,
            maxTokens = conversation.overrideMaxTokens ?: withDefault.maxTokens,
            reasoningEffort = conversation.overrideReasoningEffort ?: withDefault.reasoningEffort
        )
    }

    private suspend fun resolveModelAndProvider(agent: Agent): Pair<Provider, ChatModel>? {
        val enabledModels = app.modelRepository.getAll().first().filter { it.isEnabled }
        if (enabledModels.isEmpty()) return null
        val model = agent.defaultModelId?.let { modelId ->
            enabledModels.firstOrNull { it.id == modelId }
        } ?: enabledModels.firstOrNull() ?: return null
        val provider = app.providerRepository.getById(model.providerId).first() ?: return null
        return provider to model
    }

    private suspend fun determineProviderId(agent: Agent): String {
        val resolved = resolveAgent(
            agent,
            Conversation(
                id = "",
                title = "",
                providerId = "",
                agentId = agent.id,
                createdAt = 0L,
                updatedAt = 0L,
                lastMessage = null
            )
        )
        return resolveModelAndProvider(resolved)?.second?.providerId.orEmpty()
    }
}
