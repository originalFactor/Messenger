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
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.conversations_new_chat
import org.jetbrains.compose.resources.getString
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.CurrentAgentRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 项目二级页的 ViewModel：某个项目的会话列表。
 *
 * [agentId] 非 null 时只展示该 Agent 的会话（从 Agent 聊天列表进入的情形）。
 */
class ProjectConversationsViewModel(
    private val projectId: String,
    private val agentId: String?,
    private val projectRepository: ProjectRepository,
    private val conversationRepository: ConversationRepository,
    private val agentRepository: AgentRepository,
    private val messageRepository: MessageRepository,
    private val modelRepository: ModelRepository,
    private val currentAgentRepository: CurrentAgentRepository
) : ViewModel() {

    val project: StateFlow<Project?> = projectRepository.getById(projectId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val conversations: StateFlow<List<Conversation>> = conversationRepository
        .getByProjectId(projectId)
        .let { flow -> if (agentId == null) flow else flow.map { all -> all.filter { it.agentId == agentId } } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allAgents: StateFlow<List<Agent>> = agentRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun createNewConversation(onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
            val agent = agentId?.let { agentRepository.getById(it).first() }
                ?: currentAgentRepository.currentAgent.first()
                ?: return@launch
            currentAgentRepository.setCurrentAgentId(agent.id)
            val now = currentTimeMillis()
            val conversation = Conversation(
                id = randomUuid(),
                title = getString(Res.string.conversations_new_chat),
                providerId = determineProviderId(agent),
                agentId = agent.id,
                projectId = projectId,
                createdAt = now,
                updatedAt = now,
                lastMessage = null
            )
            conversationRepository.insert(conversation)
            onCreated(conversation.id)
        }
    }

    fun renameConversation(conversationId: String, newTitle: String) {
        viewModelScope.launch {
            val conversation = conversationRepository.getById(conversationId).first() ?: return@launch
            conversationRepository.update(
                conversation.copy(title = newTitle, updatedAt = currentTimeMillis())
            )
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch { conversationRepository.delete(conversationId) }
    }

    fun moveToPlainChats(conversationId: String) {
        viewModelScope.launch {
            val conversation = conversationRepository.getById(conversationId).first() ?: return@launch
            conversationRepository.update(conversation.copy(projectId = null))
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

    companion object {
        fun provideFactory(
            projectId: String,
            agentId: String?,
            projectRepository: ProjectRepository,
            conversationRepository: ConversationRepository,
            agentRepository: AgentRepository,
            messageRepository: MessageRepository,
            modelRepository: ModelRepository,
            currentAgentRepository: CurrentAgentRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return ProjectConversationsViewModel(
                    projectId,
                    agentId,
                    projectRepository,
                    conversationRepository,
                    agentRepository,
                    messageRepository,
                    modelRepository,
                    currentAgentRepository
                ) as T
            }
        }
    }
}