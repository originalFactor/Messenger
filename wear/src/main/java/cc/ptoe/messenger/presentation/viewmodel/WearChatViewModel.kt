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
    val isCreatingChat: Boolean = false,
    val bannerMessage: String? = null,
    val connectionState: WearConnectionState = WearConnectionState.Disconnected,
    val draft: String = ""
)

class WearChatViewModel(
    private val repository: WearChatRepository
) : ViewModel() {

    private val isSending = MutableStateFlow(false)
    private val isCreatingChat = MutableStateFlow(false)
    private val bannerMessage = MutableStateFlow<String?>(null)
    private val screen = MutableStateFlow(WearScreen.ChatList)
    private val draft = MutableStateFlow("")

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
        repository.selectedMessages
    ) { conversation, agent, messages ->
        Triple(conversation, agent, messages)
    }

    private val transientState = combine(
        isSending,
        isCreatingChat,
        bannerMessage,
        screen
    ) { sending, creating, banner, currentScreen ->
        TransientState(
            isSending = sending,
            isCreatingChat = creating,
            bannerMessage = banner,
            screen = currentScreen
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
            selectedConversation = chat.first,
            selectedAgent = chat.second,
            messages = chat.third,
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
            screen.value = WearScreen.Chat
            bannerMessage.value = null
        }
    }

    fun navigateBackToList() {
        screen.value = WearScreen.ChatList
        bannerMessage.value = null
    }

    fun updateDraft(text: String) {
        draft.value = text
    }

    fun sendDraft() {
        val text = draft.value
        if (text.isBlank() || isSending.value) return
        draft.value = ""
        viewModelScope.launch {
            isSending.value = true
            val result = repository.sendMessage(text.trim())
            isSending.value = false
            result.exceptionOrNull()?.message?.let { showBanner(it) }
        }
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

    private fun showBanner(message: String) {
        bannerMessage.value = message
    }

    private data class TransientState(
        val isSending: Boolean,
        val isCreatingChat: Boolean,
        val bannerMessage: String?,
        val screen: WearScreen
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
