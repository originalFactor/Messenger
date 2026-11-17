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

package cc.ptoe.messenger.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class WearChatRepository(
    private val preferences: WearChatStore,
    private val bridgeClient: WearChatTransport,
    scope: CoroutineScope
) {

    // 内存缓存：流式消息期间直接更新，避免频繁 DataStore 写入
    private val _messageHistoryCache = MutableStateFlow<Map<String, List<WearChatMessage>>>(emptyMap())
    private val _conversationsCache = MutableStateFlow<List<WearConversation>>(emptyList())
    private val _agentsCache = MutableStateFlow<List<WearAgent>>(emptyList())
    private val _userAvatarCache = MutableStateFlow<String?>(null)

    private data class PendingChat(
        val requestId: String,
        val user: WearChatMessage,
        val assistant: WearChatMessage,
        val userId: String? = null,
        val assistantId: String? = null,
        val accepted: Boolean = false,
        val awaitingSync: Boolean = false,
    )
    private data class AwaitingSnapshot(val userId: String?, val assistantId: String?, val syncBarrier: Long,
        var legacyRows: List<WearChatMessage>? = null)
    private data class CachedSnapshot(val agents: List<WearAgent>, val conversations: List<WearConversation>,
        val messages: Map<String, List<WearChatMessage>>, val avatar: String?)
    private val stateLock = Any()
    private val persistence = Mutex()
    private val _storageError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = _storageError
    private val pending = MutableStateFlow<Map<String, PendingChat>>(emptyMap())
    private val legacyDivergence = MutableStateFlow<Set<String>>(emptySet())
    private val awaitingSnapshot = mutableMapOf<String, AwaitingSnapshot>()
    private var receivedSnapshot = false
    private val initialLoad = CompletableDeferred<Unit>()
    @Volatile private var initialLoadRecovered = false

    val agents: Flow<List<WearAgent>> = _agentsCache
    val conversations: Flow<List<WearConversation>> = _conversationsCache
    val selectedConversationId: Flow<String?> = preferences.selectedConversationId
    val messageHistory: Flow<Map<String, List<WearChatMessage>>> = _messageHistoryCache
    val userAvatarPath: Flow<String?> = _userAvatarCache

    val connectionState: StateFlow<WearConnectionState> = bridgeClient.connectionState
    val syncError: StateFlow<String?> = bridgeClient.syncError

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
        pending,
        preferences.selectedConversationId
    ) { _, _, selectedId -> synchronized(stateLock) {
        if (selectedId == null) emptyList() else {
            val base = _messageHistoryCache.value[selectedId].orEmpty()
            val overlay = pending.value[selectedId]
            if (overlay == null) base else {
                val user = overlay.user.copy(id = overlay.userId ?: overlay.user.id)
                val assistant = overlay.assistant.copy(id = overlay.assistantId ?: overlay.assistant.id)
                base.filterNot { it.id == user.id || it.id == assistant.id } + listOf(user, assistant)
            }
        }
    } }

    val selectedHasUncertainTurn: Flow<Boolean> = combine(pending, legacyDivergence, preferences.selectedConversationId) { overlays, divergent, id ->
        id != null && (overlays[id]?.awaitingSync == true || id in divergent)
    }

    fun requestReconciliation() { bridgeClient.requestSyncNow() }
    suspend fun retrySync(): Boolean = bridgeClient.retrySync()

    suspend fun dismissUncertainTurn() {
        val id = preferences.selectedConversationId.first() ?: return
        val changedHistory = synchronized(stateLock) {
            if (id in inFlightChats) return@synchronized false
            if (pending.value[id]?.awaitingSync == true) pending.value = pending.value - id
            val legacyRows = awaitingSnapshot[id]?.legacyRows
            if (id in legacyDivergence.value) {
                legacyDivergence.value = legacyDivergence.value - id
                if (legacyRows != null) {
                    awaitingSnapshot.remove(id)
                    _messageHistoryCache.value = _messageHistoryCache.value + (id to legacyRows)
                    true
                } else false
            } else false
        }
        if (changedHistory) persistToDataStore()
    }

    /**
     * Conversations that currently have a streaming chat in flight, keyed by
     * conversationId -> requestId. Used by the sync collector to avoid
     * clobbering the local optimistic messages with a stale phone snapshot
     * while the phone is still generating the reply. See [sendMessage].
     */
    private val inFlightChats = mutableMapOf<String, String>()

    private val streamTimeoutMs: Long = 180_000L

    init {
        bridgeClient.start()
        bridgeClient.loadExistingState()

        scope.launch {
            try {
                loadPersistedState()
                initialLoad.complete(Unit)
            } catch (cancelled: CancellationException) {
                initialLoad.completeExceptionally(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                _storageError.value = failure.message ?: "Could not load watch storage."
                initialLoad.completeExceptionally(failure)
            }
        }

        scope.launch {
            bridgeClient.syncUpdates.collect { snapshot ->
                synchronized(stateLock) {
                    receivedSnapshot = true
                    val mergedMessages = snapshot.messages.toMutableMap()
                    var mergedConversations = snapshot.conversations
                    val localHistory = _messageHistoryCache.value
                    val localConversations = _conversationsCache.value.associateBy { it.id }
                    val missingLegacyBatches = if (snapshot.messagesComplete) emptySet() else
                        snapshot.conversations.map { it.id }.filterNot { it in snapshot.messages }.toSet()
                    for (id in missingLegacyBatches) {
                        localHistory[id]?.let { mergedMessages[id] = it }
                    }
                    val keep = inFlightChats.keys.toMutableSet()
                    val reconciled = mutableListOf<String>()
                    for ((id, expected) in awaitingSnapshot) {
                        // An omitted legacy batch provides no evidence about this conversation.
                        if (id in missingLegacyBatches) continue
                        val rows = snapshot.messages[id].orEmpty()
                        if ((snapshot.messagesComplete && snapshot.syncGeneration >= expected.syncBarrier) ||
                            (expected.userId != null && expected.assistantId != null &&
                            rows.any { it.id == expected.userId } && rows.any { it.id == expected.assistantId && !it.isPending }))
                            reconciled.add(id) else {
                                if (!snapshot.messagesComplete && snapshot.syncGeneration >= expected.syncBarrier) {
                                    expected.legacyRows = rows
                                    legacyDivergence.value = legacyDivergence.value + id
                                }
                                // Preserve only the two confirmed rows, never the whole old history:
                                // the phone snapshot can have advanced beyond its bounded history window.
                                val localRows = localHistory[id].orEmpty().filter { it.id == expected.userId || it.id == expected.assistantId }
                                val remoteRows = mergedMessages[id].orEmpty()
                                mergedMessages[id] = (remoteRows.filterNot { remote -> localRows.any { it.id == remote.id } } + localRows)
                                    .sortedBy { it.timestamp }
                            }
                    }
                    reconciled.forEach { awaitingSnapshot.remove(it) }
                    legacyDivergence.value = legacyDivergence.value - reconciled.toSet()
                    for (id in keep) {
                        mergedMessages[id] = localHistory[id].orEmpty()
                        localConversations[id]?.let { local -> mergedConversations = mergedConversations.map { if (it.id == id) local else it } }
                    }
                    _agentsCache.value = snapshot.agents
                    _userAvatarCache.value = snapshot.userAvatarPath
                    _conversationsCache.value = mergedConversations
                    _messageHistoryCache.value = mergedMessages
                    // Only matching phone identities can replace a transient outcome safely.
                    pending.value = pending.value.filterNot { (id, turn) ->
                        val rows = snapshot.messages[id].orEmpty()
                        turn.awaitingSync && id !in inFlightChats && matchesPendingOutcome(rows, turn)
                    }
                }
                persistToDataStore()
            }
        }
    }

    private suspend fun loadPersistedState() {
        val history = preferences.messageHistory.first()
        val conversations = preferences.conversations.first()
        val agents = preferences.agents.first()
        val avatar = preferences.userAvatarPath.first()
        synchronized(stateLock) {
            if (!receivedSnapshot) {
                _messageHistoryCache.value = history
                _conversationsCache.value = conversations
                _agentsCache.value = agents
                _userAvatarCache.value = avatar
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

    suspend fun sendMessage(text: String): Result<Unit> = sendMessage(preferences.selectedConversationId.first(), text)

    suspend fun sendMessage(conversationId: String?, text: String): Result<Unit> {
        try { initialLoad.await() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!initialLoadRecovered) return Result.failure(WearNotAdmittedException(failure.message ?: "Could not load watch storage.", failure))
        }
        val (conversation, agent) = synchronized(stateLock) {
            val conversation = _conversationsCache.value.firstOrNull { it.id == conversationId }
            conversation to _agentsCache.value.firstOrNull { it.id == conversation?.agentId }
        }
        if (conversation == null) return Result.failure(WearNotAdmittedException("Open a chat first."))
        if (agent == null) return Result.failure(WearNotAdmittedException("Agent not synced yet."))
        if (!agent.canSendExistingChat()) {
            return Result.failure(WearModelRequiredException("This agent needs a model on your phone before it can chat."))
        }
        val requestId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val turn = PendingChat(requestId,
            WearChatMessage("local-user-$requestId", conversation.id, WearMessageRole.USER, text, now),
            WearChatMessage("local-assistant-$requestId", conversation.id, WearMessageRole.ASSISTANT, "", now + 1, isPending = true))
        synchronized(stateLock) {
            if (conversation.id in inFlightChats) return Result.failure(WearNotAdmittedException("A chat is already in progress."))
            if (pending.value[conversation.id]?.awaitingSync == true) return Result.failure(WearNotAdmittedException("Check the previous chat on your phone before dismissing it."))
            if (conversation.id in legacyDivergence.value) return Result.failure(WearNotAdmittedException("Check phone history before dismissing it."))
            inFlightChats[conversation.id] = requestId
            pending.value = pending.value + (conversation.id to turn)
        }
        val frames = try { bridgeClient.registerChat(requestId) } catch (cancelled: CancellationException) {
            synchronized(stateLock) { inFlightChats.remove(conversation.id); pending.value = pending.value - conversation.id }
            throw cancelled
        } catch (failure: Exception) {
            failPending(conversation.id, requestId, failure.message ?: "Could not start chat.", true)
            return Result.failure(WearNotAdmittedException(failure.message ?: "Could not start chat.", failure))
        }
        try {
            val sent = bridgeClient.requestChat(requestId, conversation.id, text)
            if (sent.isFailure) {
                val failure = sent.exceptionOrNull() ?: IllegalStateException("Phone is not connected.")
                failPending(conversation.id, requestId, failure.message ?: "Phone is not connected.", false)
                bridgeClient.requestSyncNow()
                return Result.failure(failure)
            }
            val currentContent = StringBuilder()
            var lastUpdateTime = 0L
            val terminal = try {
                withTimeout(streamTimeoutMs) {
                    while (true) {
                        when (val frame = frames.receive()) {
                            is WearChatFrame.Accepted -> synchronized(stateLock) {
                                val current = pending.value[conversation.id]
                                if (current?.requestId == requestId) pending.value = pending.value + (conversation.id to current.copy(
                                    accepted = true, userId = frame.userMessageId,
                                    assistantId = frame.assistantMessageId ?: current.assistantId))
                            }
                            is WearChatFrame.Delta -> {
                                currentContent.append(frame.delta)
                                val tick = System.currentTimeMillis()
                                synchronized(stateLock) {
                                    val current = pending.value[conversation.id]
                                    if (current?.requestId == requestId) pending.value = pending.value + (conversation.id to current.copy(
                                        accepted = true, assistant = if (tick - lastUpdateTime >= 70) current.assistant.copy(content = currentContent.toString()) else current.assistant))
                                }
                                if (tick - lastUpdateTime >= 70) lastUpdateTime = tick
                            }
                            is WearChatFrame.Done, is WearChatFrame.Error -> return@withTimeout frame
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") error("Unreachable")
                }
            } catch (cancelled: kotlinx.coroutines.TimeoutCancellationException) {
                failPending(conversation.id, requestId, "Timed out waiting for your phone.", false)
                bridgeClient.requestSyncNow()
                return Result.failure(IllegalStateException("Timed out waiting for your phone."))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failPending(conversation.id, requestId, failure.message ?: "Phone connection ended.", false)
                bridgeClient.requestSyncNow()
                return Result.failure(failure)
            }
            return when (terminal) {
                is WearChatFrame.Error -> {
                    val preAdmissionModelError = synchronized(stateLock) {
                        terminal.code == "model_required" && pending.value[conversation.id]?.let { it.requestId == requestId && !it.accepted } == true
                    }
                    failPending(conversation.id, requestId, terminal.message, preAdmissionModelError)
                    if (!preAdmissionModelError) bridgeClient.requestSyncNow()
                    Result.failure(if (preAdmissionModelError) WearModelRequiredException(terminal.message) else IllegalStateException(terminal.message))
                }
                is WearChatFrame.Done -> {
                    val content = terminal.content.ifEmpty { currentContent.toString() }
                    completePending(conversation.id, requestId, terminal.userMessageId, terminal.assistantMessageId, content)
                    persistToDataStore()
                    bridgeClient.requestSyncNow()
                    Result.success(Unit)
                }
                else -> error("Unexpected non-terminal chat frame")
            }
        } catch (cancelled: CancellationException) {
            failPending(conversation.id, requestId, "Chat outcome is unknown. Check your phone.", false)
            bridgeClient.requestSyncNow()
            throw cancelled
        } catch (failure: Exception) {
            failPending(conversation.id, requestId, failure.message ?: "Phone connection ended.", false)
            bridgeClient.requestSyncNow()
            return Result.failure(failure)
        } finally {
            bridgeClient.unregisterChat(requestId)
        }
    }

    /** An unconfirmed error is shown only as transient UI until a phone snapshot arrives. */
    private fun matchesPendingOutcome(rows: List<WearChatMessage>, turn: PendingChat): Boolean {
        val user = turn.userId ?: return false
        val index = rows.indexOfFirst { it.id == user && it.role == WearMessageRole.USER }
        if (index < 0) return false
        val assistant = if (turn.assistantId != null) rows.firstOrNull { it.id == turn.assistantId }
            else rows.getOrNull(index + 1)
        return assistant?.role == WearMessageRole.ASSISTANT && !assistant.isPending
    }

    private fun failPending(conversationId: String, requestId: String, error: String, modelRequired: Boolean) {
        synchronized(stateLock) {
            if (inFlightChats[conversationId] == requestId) inFlightChats.remove(conversationId)
            val current = pending.value[conversationId]
            if (current?.requestId == requestId) {
                pending.value = if (modelRequired) pending.value - conversationId else pending.value + (conversationId to current.copy(
                    assistant = current.assistant.copy(content = error, isPending = false, isError = true), awaitingSync = true))
            }
        }
    }

    private fun completePending(conversationId: String, requestId: String, userId: String?, assistantId: String?, content: String) {
        synchronized(stateLock) {
            val current = pending.value[conversationId]?.takeIf { it.requestId == requestId } ?: return
            if (inFlightChats[conversationId] == requestId) inFlightChats.remove(conversationId)
            val actualUser = userId ?: current.userId
            val actualAssistant = assistantId ?: current.assistantId
            if (actualUser == null || actualAssistant == null) {
                // Old phones may omit IDs. Do not invent durable rows that cannot be reconciled.
                pending.value = pending.value + (conversationId to current.copy(
                    assistant = current.assistant.copy(content = content, isPending = false), awaitingSync = true))
                return
            }
            val user = current.user.copy(id = actualUser)
            val assistant = current.assistant.copy(id = actualAssistant, content = content, isPending = false)
            val history = _messageHistoryCache.value[conversationId].orEmpty().filterNot { it.id == actualUser || it.id == actualAssistant }
            _messageHistoryCache.value = _messageHistoryCache.value + (conversationId to (history + user + assistant))
            _conversationsCache.value = _conversationsCache.value.map {
                if (it.id == conversationId) it.copy(lastMessage = stripThinkForPreview(content.ifBlank { user.content }), updatedAt = System.currentTimeMillis()) else it
            }.sortedByDescending { it.updatedAt }
            awaitingSnapshot[conversationId] = AwaitingSnapshot(actualUser, actualAssistant, bridgeClient.markSyncBarrier())
            pending.value = pending.value - conversationId
        }
    }

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

    suspend fun retryStorage(): Boolean = persistence.withLock {
        try {
            try {
                initialLoad.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!initialLoadRecovered) {
                    loadPersistedState()
                    initialLoadRecovered = true
                }
            }
            writeSnapshot()
            _storageError.value = null
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _storageError.value = failure.message ?: "Could not save watch storage."
            false
        }
    }

    private suspend fun persistToDataStore() = persistence.withLock {
        if (_storageError.value != null) return@withLock
        try {
            writeSnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _storageError.value = failure.message ?: "Could not save watch storage."
        }
    }

    private suspend fun writeSnapshot() {
        val snapshot = synchronized(stateLock) {
            CachedSnapshot(_agentsCache.value, _conversationsCache.value, _messageHistoryCache.value, _userAvatarCache.value)
        }
        preferences.applySnapshot(snapshot.agents, snapshot.conversations, snapshot.messages, snapshot.avatar)
    }
}
