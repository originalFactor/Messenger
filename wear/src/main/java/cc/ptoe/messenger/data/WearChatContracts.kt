/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Phone and persisted-state boundaries shared by the watch chat owner. */
interface WearChatTransport {
    val syncUpdates: Flow<WearSyncSnapshot>
    val newChatResponses: Flow<WearNewChatResponse>
    val connectionState: StateFlow<WearConnectionState>
    val syncError: StateFlow<String?>
    fun start()
    fun loadExistingState()
    fun requestReconnect()
    /** Marks completed local work; later sync requests capture this generation. */
    fun markSyncBarrier(): Long
    fun requestSyncNow()
    suspend fun retrySync(): Boolean
    fun registerChat(requestId: String): ReceiveChannel<WearChatFrame>
    fun unregisterChat(requestId: String)
    suspend fun requestChat(requestId: String, conversationId: String, text: String): Result<Unit>
    suspend fun requestNewConversation(requestId: String, agentId: String?): Result<Unit>
}

interface WearChatStore {
    val agents: Flow<List<WearAgent>>
    val conversations: Flow<List<WearConversation>>
    val selectedConversationId: Flow<String?>
    val messageHistory: Flow<Map<String, List<WearChatMessage>>>
    val userAvatarPath: Flow<String?>
    suspend fun setSelectedConversationId(conversationId: String?)
    suspend fun applySnapshot(agents: List<WearAgent>, conversations: List<WearConversation>,
        messages: Map<String, List<WearChatMessage>>, userAvatarPath: String?)
}
