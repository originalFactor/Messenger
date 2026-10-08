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

package cc.ptoe.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import kotlin.reflect.KClass
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.CurrentAgentRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.conversations_clone_suffix
import cc.ptoe.messenger.generated.resources.conversations_new_chat
import org.jetbrains.compose.resources.getString
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.randomUuid

/**
 * Agent 聊天列表子页的 ViewModel：固定展示某个 Agent 名下的全部会话
 * （主页聊天列表不再做 Agent 筛选，本页是唯一的按 Agent 过滤入口）。
 */
class AgentConversationsViewModel(
    private val agentId: String,
    private val agentRepository: AgentRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val modelRepository: ModelRepository,
    private val currentAgentRepository: CurrentAgentRepository
) : ViewModel() {

    val agent: StateFlow<Agent?> = agentRepository.getById(agentId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val conversations: StateFlow<List<Conversation>> = conversationRepository.getByAgentId(agentId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** 「切换 Agent」菜单的目标列表（与主页会话行的菜单保持一致）。 */
    val allAgents: StateFlow<List<Agent>> = agentRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun createNewConversation(onCreated: (String) -> Unit = {}) {        viewModelScope.launch {
            val agent = agentRepository.getById(agentId).first() ?: return@launch
            currentAgentRepository.setCurrentAgentId(agent.id)
            val now = currentTimeMillis()
            val conversation = Conversation(
                id = randomUuid(),
                title = getString(Res.string.conversations_new_chat),
                providerId = determineProviderId(agent),
                agentId = agent.id,
                createdAt = now,
                updatedAt = now,
                lastMessage = null
            )
            conversationRepository.insert(conversation)
            onCreated(conversation.id)
        }
    }

    private suspend fun determineProviderId(agent: Agent): String {
        val enabledModels = modelRepository.getAll().first().filter { it.isEnabled }
        if (enabledModels.isEmpty()) return ""
        val defaultModel = agent.defaultModelId?.let { modelId ->
            enabledModels.find { it.id == modelId }
        }
        return (defaultModel ?: enabledModels.firstOrNull())?.providerId ?: ""
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        viewModelScope.launch {
            val conversation = conversationRepository.getById(conversationId).first()
                ?: return@launch
            conversationRepository.update(
                conversation.copy(
                    title = newTitle,
                    updatedAt = currentTimeMillis()
                )
            )
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.delete(conversationId)
        }
    }

    fun cloneConversation(
        conversationId: String,
        onCloned: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val source = conversationRepository.getById(conversationId).first()
                ?: return@launch
            val clonedConversationId = randomUuid()
            val now = currentTimeMillis()
            val clonedConversation = source.copy(
                id = clonedConversationId,
                title = source.title + getString(Res.string.conversations_clone_suffix),
                createdAt = now,
                updatedAt = now
            )

            conversationRepository.insert(clonedConversation)
            val clonedMessages = messageRepository.getByConversationId(source.id).first().map { message ->
                message.copy(
                    id = randomUuid(),
                    conversationId = clonedConversationId,
                    status = if (message.status == MessageStatus.SENDING) {
                        MessageStatus.SENT
                    } else {
                        message.status
                    }
                )
            }
            messageRepository.insertAll(clonedMessages)
            onCloned(clonedConversationId)
        }
    }

    fun switchAgentForConversations(ids: List<String>, targetAgentId: String) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val now = currentTimeMillis()
            ids.forEach { id ->
                val conversation = conversationRepository.getById(id).first() ?: return@forEach
                conversationRepository.update(
                    conversation.copy(
                        agentId = targetAgentId,
                        updatedAt = now
                    )
                )
            }
        }
    }

    companion object {
        fun provideFactory(
            agentId: String,
            agentRepository: AgentRepository,
            conversationRepository: ConversationRepository,
            messageRepository: MessageRepository,
            modelRepository: ModelRepository,
            currentAgentRepository: CurrentAgentRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return AgentConversationsViewModel(
                    agentId,
                    agentRepository,
                    conversationRepository,
                    messageRepository,
                    modelRepository,
                    currentAgentRepository
                ) as T
            }
        }
    }
}
