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

package cc.ptoe.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cc.ptoe.messenger.data.WearAgent
import cc.ptoe.messenger.data.WearChatMessage
import cc.ptoe.messenger.data.WearChatRepository
import cc.ptoe.messenger.data.WearConnectionState
import cc.ptoe.messenger.data.WearConversation
import cc.ptoe.messenger.data.WearNotAdmittedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class WearScreen {
    ChatList,
    NewChat,
    Chat
}

data class WearChatListItem(
    val conversation: WearConversation,
    val agent: WearAgent?
)

data class WearChatUiState(
    val chats: List<WearChatListItem> = emptyList(),
    val agents: List<WearAgent> = emptyList(),
    val selectedConversation: WearConversation? = null,
    val selectedAgent: WearAgent? = null,
    val messages: List<WearChatMessage> = emptyList(),
    val userAvatarPath: String? = null,
    val screen: WearScreen = WearScreen.ChatList,
    val isSending: Boolean = false,
    val hasUncertainTurn: Boolean = false,
    val storageError: String? = null,
    val isRetryingStorage: Boolean = false,
    val syncError: String? = null,
    val isRetryingSync: Boolean = false,
    val isCreatingChat: Boolean = false,
    val bannerMessage: String? = null,
    val connectionState: WearConnectionState = WearConnectionState.Disconnected,
    val draft: String = ""
)

class WearChatViewModel internal constructor(
    private val repository: WearChatRepository,
    private val sendMessage: suspend (String, String) -> Result<Unit>
) : ViewModel() {
    constructor(repository: WearChatRepository) : this(repository, repository::sendMessage)

    private val isSending = MutableStateFlow(false)
    private val isCreatingChat = MutableStateFlow(false)
    private val isRetryingStorage = MutableStateFlow(false)
    private val isRetryingSync = MutableStateFlow(false)
    private val bannerMessage = MutableStateFlow<String?>(null)
    private val screen = MutableStateFlow(WearScreen.ChatList)
    private val draft = MutableStateFlow("")
    private var draftConversationId: String? = null
    private val drafts = mutableMapOf<String, String>()
    private val draftEditGenerations = mutableMapOf<String, Long>()
    private val deferredChatErrors = mutableMapOf<String, String>()

    private val listContent = combine(
        repository.agents,
        repository.conversations,
        repository.userAvatarPath
    ) { agents, conversations, userAvatarPath ->
        Triple(agents, conversations, userAvatarPath)
    }

    private val chatContent = combine(
        repository.selectedConversation,
        repository.selectedAgent,
        repository.selectedMessages,
        repository.selectedHasUncertainTurn
    ) { conversation, agent, messages, uncertain ->
        ChatContent(conversation, agent, messages, uncertain)
    }

    private val serviceState = combine(repository.storageError, isRetryingStorage, repository.syncError, isRetryingSync) { storageError, storageRetrying, syncError, syncRetrying ->
        ServiceState(storageError, storageRetrying, syncError, syncRetrying)
    }

    private val transientState = combine(
        isSending,
        isCreatingChat,
        bannerMessage,
        screen,
        serviceState
    ) { sending, creating, banner, currentScreen, service ->
        TransientState(
            isSending = sending,
            isCreatingChat = creating,
            bannerMessage = banner,
            screen = currentScreen,
            storageError = service.storageError,
            isRetryingStorage = service.isRetryingStorage,
            syncError = service.syncError,
            isRetryingSync = service.isRetryingSync
        )
    }

    val uiState: StateFlow<WearChatUiState> = combine(
        listContent,
        chatContent,
        transientState,
        repository.connectionState,
        draft
    ) { list, chat, transient, connection, draftText ->
        val agents = list.first
        val conversations = list.second
        val userAvatarPath = list.third
        val agentsById = agents.associateBy { it.id }
        WearChatUiState(
            chats = conversations.map { conversation ->
                WearChatListItem(
                    conversation = conversation,
                    agent = agentsById[conversation.agentId]
                )
            },
            agents = agents,
            selectedConversation = chat.conversation,
            selectedAgent = chat.agent,
            messages = chat.messages,
            hasUncertainTurn = chat.uncertain,
            storageError = transient.storageError,
            isRetryingStorage = transient.isRetryingStorage,
            syncError = transient.syncError,
            isRetryingSync = transient.isRetryingSync,
            userAvatarPath = userAvatarPath,
            screen = transient.screen,
            isSending = transient.isSending,
            isCreatingChat = transient.isCreatingChat,
            bannerMessage = transient.bannerMessage,
            connectionState = connection,
            draft = draftText
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = WearChatUiState()
    )

    fun requestReconnect() {
        repository.requestReconnect()
    }

    fun openChat(conversationId: String) {
        viewModelScope.launch {
            repository.selectConversation(conversationId)
            selectDraft(conversationId)
            screen.value = WearScreen.Chat
        }
    }

    fun navigateBackToList() {
        screen.value = WearScreen.ChatList
        bannerMessage.value = null
    }

    fun updateDraft(text: String) {
        val conversationId = draftConversationId ?: return
        draftEditGenerations[conversationId] = (draftEditGenerations[conversationId] ?: 0L) + 1
        drafts[conversationId] = text
        draft.value = text
    }

    fun sendDraft() {
        val text = draft.value
        if (text.isBlank() || isSending.value) return
        val conversationId = draftConversationId ?: return
        val submittedGeneration = draftEditGenerations[conversationId] ?: 0L
        drafts[conversationId] = ""
        draft.value = ""
        isSending.value = true
        viewModelScope.launch {
            try {
                sendMessage(conversationId, text.trim()).exceptionOrNull()?.let { throw it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is WearNotAdmittedException && (draftEditGenerations[conversationId] ?: 0L) == submittedGeneration) {
                    drafts[conversationId] = text
                    if (draftConversationId == conversationId) draft.value = text
                }
                error.message?.let {
                    if (draftConversationId == conversationId && screen.value == WearScreen.Chat) showBanner(it)
                    else deferredChatErrors[conversationId] = it
                }
            } finally {
                isSending.value = false
            }
        }
    }

    fun requestReconciliation() {
        if (repository.syncError.value != null) retrySync() else repository.requestReconciliation()
    }

    fun retrySync() {
        if (isRetryingSync.value) return
        isRetryingSync.value = true
        viewModelScope.launch {
            try { repository.retrySync() } finally { isRetryingSync.value = false }
        }
    }

    fun retryStorage() {
        if (isRetryingStorage.value) return
        isRetryingStorage.value = true
        viewModelScope.launch {
            try { repository.retryStorage() } finally { isRetryingStorage.value = false }
        }
    }

    fun dismissUncertainTurn() {
        viewModelScope.launch { repository.dismissUncertainTurn() }
    }

    fun startNewChat() {
        if (isCreatingChat.value) return
        screen.value = WearScreen.NewChat
        bannerMessage.value = null
    }

    fun cancelNewChat() {
        screen.value = WearScreen.ChatList
        bannerMessage.value = null
    }

    fun createChat(agentId: String? = null) {
        if (isCreatingChat.value) return
        viewModelScope.launch {
            isCreatingChat.value = true
            val result = repository.createConversation(agentId)
            isCreatingChat.value = false
            result.fold(
                onSuccess = { conversationId ->
                    repository.selectConversation(conversationId)
                    selectDraft(conversationId)
                    screen.value = WearScreen.Chat
                },
                onFailure = { error ->
                    showBanner(error.message ?: "Failed to create chat.")
                    screen.value = WearScreen.ChatList
                }
            )
        }
    }

    fun dismissBanner() {
        bannerMessage.value = null
    }

    private fun selectDraft(conversationId: String) {
        draftConversationId = conversationId
        draft.value = drafts[conversationId].orEmpty()
        bannerMessage.value = deferredChatErrors.remove(conversationId)
    }

    private fun showBanner(message: String) {
        bannerMessage.value = message
    }

    private data class ChatContent(
        val conversation: WearConversation?,
        val agent: WearAgent?,
        val messages: List<WearChatMessage>,
        val uncertain: Boolean
    )

    private data class ServiceState(
        val storageError: String?,
        val isRetryingStorage: Boolean,
        val syncError: String?,
        val isRetryingSync: Boolean
    )

    private data class TransientState(
        val isSending: Boolean,
        val isCreatingChat: Boolean,
        val bannerMessage: String?,
        val screen: WearScreen,
        val storageError: String?,
        val isRetryingStorage: Boolean,
        val syncError: String?,
        val isRetryingSync: Boolean
    )

    companion object {
        fun provideFactory(
            repository: WearChatRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return WearChatViewModel(repository) as T
            }
        }
    }
}
