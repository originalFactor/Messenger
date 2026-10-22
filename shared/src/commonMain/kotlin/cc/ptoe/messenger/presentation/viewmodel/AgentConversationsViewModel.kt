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
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import kotlin.reflect.KClass
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.repository.ProjectRepository
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.CurrentAgentRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.conversations_new_chat
import org.jetbrains.compose.resources.getString
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.randomUuid

/**
 * Agent 聊天列表子页的 ViewModel：Recent Chats 提供该 Agent 最近会话的
 * 快捷入口，文件夹（该 Agent 的项目 + “无项目”）负责归类。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentConversationsViewModel(
    private val agentId: String,
    private val agentRepository: AgentRepository,
    private val conversationRepository: ConversationRepository,
    private val modelRepository: ModelRepository,
    private val currentAgentRepository: CurrentAgentRepository,
    private val projectRepository: ProjectRepository
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

    /**
     * 该 Agent 名下的项目（聊天列表的文件夹行）；从会话列表按 agentId 过滤，
     * 所以点进去的是“该项目里该 Agent 的会话”文件夹页。
     */
    val projects: StateFlow<List<Project>> = conversations
        .map { all -> all.mapNotNull { it.projectId }.toSet() }
        .flatMapLatest { ids -> projectRepository.getAll().map { all -> all.filter { it.id in ids } } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val projectConversationCounts: StateFlow<Map<String, Int>> = conversations
        .map { all -> all.mapNotNull { it.projectId }.groupingBy { it }.eachCount() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    /** “无项目”文件夹里该 Agent 的普通对话数。 */
    val unprojectedConversationCount: StateFlow<Int> = conversations
        .map { all -> all.count { it.projectId == null } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    /**
     * Recent Chats：该 Agent 最近 [ConversationsViewModel.RECENT_CONVERSATION_LIMIT]
     * 个会话的快捷入口（无论是否属于项目）。会话仍归在各自文件夹里。
     */
    val recentConversations: StateFlow<List<Conversation>> = conversations
        .map { all -> all.take(ConversationsViewModel.RECENT_CONVERSATION_LIMIT) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun createNewConversation(onCreated: (String) -> Unit = {}) {
        viewModelScope.launch {
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

    /**
     * 清理建好后从未发过消息的空会话。规则在 Rust core
     * （`messenger_core::conversation`），这里只负责触发；
     * [keepConversationId] 保护双栏布局中右栏正在展示的会话。
     */
    fun sweepEmptyConversations(keepConversationId: String? = null) {
        viewModelScope.launch {
            conversationRepository.sweepEmptyConversations(
                excludeIds = keepConversationId?.let { listOf(it) } ?: emptyList()
            )
        }
    }

    companion object {
        fun provideFactory(
            agentId: String,
            agentRepository: AgentRepository,
            conversationRepository: ConversationRepository,
            modelRepository: ModelRepository,
            currentAgentRepository: CurrentAgentRepository,
            projectRepository: ProjectRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return AgentConversationsViewModel(
                    agentId,
                    agentRepository,
                    conversationRepository,
                    modelRepository,
                    currentAgentRepository,
                    projectRepository
                ) as T
            }
        }
    }
}
