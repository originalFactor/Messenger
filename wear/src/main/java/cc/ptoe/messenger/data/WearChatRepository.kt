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

package cc.ptoe.messenger.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WearChatRepository(
    private val preferences: WearChatPreferences,
    private val bridgeClient: WearBridgeClient,
    scope: CoroutineScope
) {

    // 内存缓存：流式消息期间直接更新，避免频繁 DataStore 写入
    private val _messageHistoryCache = MutableStateFlow<Map<String, List<WearChatMessage>>>(emptyMap())
    private val _conversationsCache = MutableStateFlow<List<WearConversation>>(emptyList())
    private val _agentsCache = MutableStateFlow<List<WearAgent>>(emptyList())

    // 标记当前是否有流式消息进行中
    @Volatile private var isStreamingMessage = false

    val agents: Flow<List<WearAgent>> = _agentsCache
    val conversations: Flow<List<WearConversation>> = _conversationsCache
    val selectedConversationId: Flow<String?> = preferences.selectedConversationId
    val messageHistory: Flow<Map<String, List<WearChatMessage>>> = _messageHistoryCache
    val userAvatarPath: Flow<String?> = preferences.userAvatarPath

    val connectionState: StateFlow<WearConnectionState> = bridgeClient.connectionState

    fun requestReconnect() = bridgeClient.requestReconnect()

    val selectedConversation: Flow<WearConversation?> = combine(
        _conversationsCache,
        preferences.selectedConversationId
    ) { conversations, selectedId ->
        conversations.firstOrNull { it.id == selectedId }
    }

    val selectedAgent: Flow<WearAgent?> = combine(
        _agentsCache,
        selectedConversation
    ) { agents, conversation ->
        conversation?.let { conv -> agents.firstOrNull { it.id == conv.agentId } }
    }

    val selectedMessages: Flow<List<WearChatMessage>> = combine(
        _messageHistoryCache,
        preferences.selectedConversationId
    ) { history, selectedId ->
        selectedId?.let { history[it].orEmpty() } ?: emptyList()
    }

    /**
     * Conversations that currently have a streaming chat in flight, keyed by
     * conversationId -> requestId. Used by the sync collector to avoid
     * clobbering the local optimistic messages with a stale phone snapshot
     * while the phone is still generating the reply. See [sendMessage].
     */
    private val inFlightChats = ConcurrentHashMap<String, String>()

    private val streamTimeoutMs: Long = 180_000L

    init {
        bridgeClient.start()
        bridgeClient.loadExistingState()

        // 启动时加载 DataStore 数据到内存缓存
        scope.launch {
            preferences.messageHistory.first().let { _messageHistoryCache.value = it }
        }
        scope.launch {
            preferences.conversations.first().let { _conversationsCache.value = it }
        }
        scope.launch {
            preferences.agents.first().let { _agentsCache.value = it }
        }

        scope.launch {
            bridgeClient.syncUpdates.collect { snapshot ->
                val currentSelection = preferences.selectedConversationId.first()
                val conversationIds = snapshot.conversations.map { it.id }.toSet()
                val nextSelection = when {
                    snapshot.conversations.isEmpty() -> null
                    currentSelection != null && currentSelection in conversationIds -> currentSelection
                    else -> snapshot.conversations.firstOrNull()?.id
                }
                // While a chat is streaming for a conversation, the phone's
                // sync snapshot lags behind the local optimistic messages
                // (local user message + streaming assistant placeholder).
                // Wholesale-replacing messages here would make the just-sent
                // message vanish until the phone's next sync catches up — the
                // "send -> message disappears" bug. So for in-flight
                // conversations we keep the local messages AND conversations
                // (the conversation's lastMessage/updatedAt may also be ahead
                // locally) and only let the sync overwrite the rest.
                val mergedMessages = snapshot.messages.toMutableMap()
                var mergedConversations = snapshot.conversations
                if (inFlightChats.isNotEmpty()) {
                    val localHistory = _messageHistoryCache.value
                    val localConversations = _conversationsCache.value
                    for (convId in inFlightChats.keys) {
                        // Preserve local messages (includes optimistic user msg + placeholder)
                        localHistory[convId]?.let { localMsgs ->
                            mergedMessages[convId] = localMsgs
                        }
                        // Preserve local conversation (includes updated lastMessage/updatedAt)
                        val localConv = localConversations.firstOrNull { it.id == convId }
                        if (localConv != null) {
                            mergedConversations = mergedConversations.map { sc ->
                                if (sc.id == convId) localConv else sc
                            }
                        }
                    }
                }

                // 更新内存缓存（UI 立即响应）
                _agentsCache.value = snapshot.agents
                _conversationsCache.value = mergedConversations
                _messageHistoryCache.value = mergedMessages

                // 非流式期间才写入 DataStore，流式期间仅更新内存
                if (!isStreamingMessage) {
                    preferences.applySnapshot(
                        agents = snapshot.agents,
                        conversations = mergedConversations,
                        messages = mergedMessages,
                        userAvatarPath = snapshot.userAvatarPath,
                        selectedConversationId = nextSelection
                    )
                }
            }
        }
    }

    suspend fun selectConversation(conversationId: String) {
        preferences.setSelectedConversationId(conversationId)
    }

    suspend fun createConversation(agentId: String? = null): Result<String> {
        val requestId = UUID.randomUUID().toString()
        val sendResult = bridgeClient.requestNewConversation(requestId, agentId)
        if (sendResult.isFailure) {
            return Result.failure(
                sendResult.exceptionOrNull()
                    ?: IllegalStateException("Phone is not connected.")
            )
        }

        val response = try {
            withTimeout(30000) {
                bridgeClient.newChatResponses.first { it.requestId == requestId }
            }
        } catch (_: Exception) {
            return Result.failure(IllegalStateException("Timed out waiting for your phone."))
        }

        return if (!response.error.isNullOrBlank()) {
            Result.failure(IllegalStateException(response.error))
        } else {
            val conversationId = response.conversationId
                ?: return Result.failure(IllegalStateException("Phone did not return a conversation."))
            preferences.setSelectedConversationId(conversationId)
            Result.success(conversationId)
        }
    }

    suspend fun sendMessage(text: String): Result<Unit> {
        val conversation = selectedConversation.first()
            ?: return Result.failure(IllegalStateException("Open a chat first."))
        val agent = selectedAgent.first()
            ?: return Result.failure(IllegalStateException("Agent not synced yet."))
        if (!agent.isReady) {
            return Result.failure(
                IllegalStateException("This agent needs a model on your phone before it can chat.")
            )
        }

        val existingMessages = _messageHistoryCache.value[conversation.id].orEmpty()
        val now = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()
        val userMessage = WearChatMessage(
            id = "local-user-$requestId",
            conversationId = conversation.id,
            role = WearMessageRole.USER,
            content = text,
            timestamp = now
        )
        val assistantPlaceholder = WearChatMessage(
            id = "local-assistant-$requestId",
            conversationId = conversation.id,
            role = WearMessageRole.ASSISTANT,
            content = "",
            timestamp = now + 1,
            isPending = true
        )

        // Mark in-flight BEFORE writing to memory cache
        inFlightChats[conversation.id] = requestId
        isStreamingMessage = true  // 标记流式开始，阻止 DataStore 写入

        // 内存更新：用户消息 + 占位符
        updateMessagesInMemory(
            conversation.id,
            existingMessages + userMessage + assistantPlaceholder
        )
        updateConversationPreviewInMemory(conversation.id, text, now)

        val sendResult = bridgeClient.requestChat(
            requestId = requestId,
            conversationId = conversation.id,
            text = text
        )

        if (sendResult.isFailure) {
            inFlightChats.remove(conversation.id)
            isStreamingMessage = false
            val error = sendResult.exceptionOrNull()?.message ?: "Phone is not connected."
            replacePendingWithErrorInMemory(conversation.id, requestId, error)
            // 错误时持久化到 DataStore
            persistToDataStore()
            return Result.failure(IllegalStateException(error))
        }

        // Collect streaming frames for this request: accumulate deltas into
        // the placeholder content (so the bubble streams on the watch), until
        // a terminal Done / Error frame arrives. onEach updates the bubble on
        // every delta; `first` returns the terminal frame and cancels collection.
        val currentContent = StringBuilder()
        val terminal = try {
            withTimeout(streamTimeoutMs) {
                bridgeClient.chatFrames
                    .filter { it.requestId == requestId }
                    .onEach { frame ->
                        if (frame is WearChatFrame.Delta) {
                            currentContent.append(frame.delta)
                            // 内存更新，不触发 DataStore
                            updatePlaceholderContentInMemory(
                                conversation.id,
                                requestId,
                                currentContent.toString()
                            )
                        }
                    }
                    .first { it is WearChatFrame.Done || it is WearChatFrame.Error }
            }
        } catch (_: Exception) {
            inFlightChats.remove(conversation.id)
            isStreamingMessage = false
            val error = "Timed out waiting for your phone."
            replacePendingWithErrorInMemory(conversation.id, requestId, error)
            persistToDataStore()
            return Result.failure(IllegalStateException(error))
        }

        inFlightChats.remove(conversation.id)
        isStreamingMessage = false  // 流式结束

        return when (terminal) {
            is WearChatFrame.Error -> {
                replacePendingWithErrorInMemory(conversation.id, requestId, terminal.message)
                persistToDataStore()
                Result.failure(IllegalStateException(terminal.message))
            }
            is WearChatFrame.Done -> {
                val content = terminal.content.ifEmpty { currentContent.toString() }
                finalizePlaceholderInMemory(
                    conversationId = conversation.id,
                    requestId = requestId,
                    content = content,
                    userMessageId = terminal.userMessageId,
                    assistantMessageId = terminal.assistantMessageId
                )
                updateConversationPreviewInMemory(
                    conversation.id,
                    content.ifBlank { text },
                    System.currentTimeMillis()
                )
                // 流式完成后一次性写入 DataStore
                persistToDataStore()
                Result.success(Unit)
            }
            is WearChatFrame.Delta -> error("unreachable: delta is not terminal")
        }
    }

    // ========== 内存操作方法（流式期间使用，避免 DataStore 写入）==========

    private fun updateMessagesInMemory(conversationId: String, messages: List<WearChatMessage>) {
        val updatedHistory = _messageHistoryCache.value.toMutableMap()
        updatedHistory[conversationId] = messages
        _messageHistoryCache.value = updatedHistory
    }

    private fun updateConversationPreviewInMemory(
        conversationId: String,
        preview: String,
        updatedAt: Long
    ) {
        val conversations = _conversationsCache.value.map { conversation ->
            if (conversation.id == conversationId) {
                conversation.copy(
                    lastMessage = stripThinkForPreview(preview),
                    updatedAt = updatedAt
                )
            } else {
                conversation
            }
        }.sortedByDescending { it.updatedAt }
        _conversationsCache.value = conversations
    }

    /**
     * 剥离思考块（think/thinking 标签包裹的内容），用于聊天列表预览。
     * wear 模块不依赖 shared，这里保留与 shared 的
     * `ThinkBlockUtils.stripThinkBlock` 相同的语义：移除所有已闭合块 + 末尾未闭合块。
     */
    private fun stripThinkForPreview(content: String): String {
        val closed = Regex(
            "<think(?:ing)?(?:\\s[^>]*)?>[\\s\\S]*?</think(?:ing)?>",
            RegexOption.IGNORE_CASE,
        )
        val unclosedTail = Regex(
            "<think(?:ing)?(?:\\s[^>]*)?>[\\s\\S]*$",
            RegexOption.IGNORE_CASE,
        )
        return content.replace(closed, "").replace(unclosedTail, "").trim()
    }

    private fun updatePlaceholderContentInMemory(
        conversationId: String,
        requestId: String,
        content: String
    ) {
        val current = _messageHistoryCache.value[conversationId].orEmpty()
        val updated = current.map { message ->
            if (message.id == "local-assistant-$requestId") {
                message.copy(content = content)
            } else {
                message
            }
        }
        updateMessagesInMemory(conversationId, updated)
    }

    private fun finalizePlaceholderInMemory(
        conversationId: String,
        requestId: String,
        content: String,
        userMessageId: String?,
        assistantMessageId: String?
    ) {
        val current = _messageHistoryCache.value[conversationId].orEmpty()
        val updated = current.map { message ->
            when (message.id) {
                "local-user-$requestId" -> message.copy(
                    id = userMessageId ?: message.id
                )
                "local-assistant-$requestId" -> message.copy(
                    id = assistantMessageId ?: message.id,
                    content = content,
                    isPending = false,
                    isError = false
                )
                else -> message
            }
        }
        updateMessagesInMemory(conversationId, updated)
    }

    private fun replacePendingWithErrorInMemory(
        conversationId: String,
        requestId: String,
        error: String
    ) {
        val current = _messageHistoryCache.value[conversationId].orEmpty()
        val updated = current.map { message ->
            if (message.id == "local-assistant-$requestId") {
                message.copy(
                    content = error,
                    isPending = false,
                    isError = true
                )
            } else {
                message
            }
        }
        updateMessagesInMemory(conversationId, updated)
        updateConversationPreviewInMemory(conversationId, error, System.currentTimeMillis())
    }

    /**
     * 将当前内存缓存持久化到 DataStore（流式结束后调用）
     */
    private suspend fun persistToDataStore() {
        preferences.applySnapshot(
            agents = _agentsCache.value,
            conversations = _conversationsCache.value,
            messages = _messageHistoryCache.value,
            userAvatarPath = preferences.userAvatarPath.first(),
            selectedConversationId = preferences.selectedConversationId.first()
        )
    }
}
