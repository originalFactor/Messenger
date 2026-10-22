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
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.CurrentAgentRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProjectRepository
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.conversations_new_chat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * 会话主页的 ViewModel：Recent Chats 提供最近会话的快捷入口，文件夹
 * （项目 + “无项目”）负责归类。
 *
 * 会话级的操作（重命名、删除、多选、切换 Agent）都在文件夹二级页
 * （[ProjectConversationsViewModel]）里做，Recent Chats 只负责跳转。
 */
class ConversationsViewModel(
    private val conversationRepository: ConversationRepository,
    private val currentAgentRepository: CurrentAgentRepository,
    private val agentRepository: AgentRepository,
    private val modelRepository: ModelRepository,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    val currentAgent: StateFlow<Agent?> = currentAgentRepository.currentAgent
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val allAgents: StateFlow<List<Agent>> = agentRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** 全部会话；只用于文件夹计数与空状态判断。 */
    private val conversations: StateFlow<List<Conversation>> = conversationRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** 聊天列表的 Project 文件夹。 */
    val projects: StateFlow<List<Project>> = projectRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** 每个项目的会话数（项目行副标题）。 */
    val projectConversationCounts: StateFlow<Map<String, Int>> = conversations
        .map { all -> all.mapNotNull { it.projectId }.groupingBy { it }.eachCount() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    /**
     * “无项目”文件夹里的会话数：普通对话不再直接铺在列表里，而是收进
     * 这个文件夹，避免同一会话重复展示。
     */
    val unprojectedConversationCount: StateFlow<Int> = conversations
        .map { all -> all.count { it.projectId == null } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    /**
     * Recent Chats：最近 [RECENT_CONVERSATION_LIMIT] 个会话（无论是否属于
     * 项目）的快捷入口，让用户从聊天列表一键回到上一次的对话。会话本身
     * 仍归在各自文件夹里，这里只是重复的入口，不是第二份列表。
     */
    val recentConversations: StateFlow<List<Conversation>> = conversations
        .map { all -> all.take(RECENT_CONVERSATION_LIMIT) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun createNewConversation(
        title: String? = null,
        agentId: String? = null,
        onCreated: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val agent = if (agentId != null) {
                agentRepository.getById(agentId).first()
            } else {
                currentAgentRepository.currentAgent.first()
            } ?: return@launch
            currentAgentRepository.setCurrentAgentId(agent.id)
            val now = currentTimeMillis()
            val providerId = determineProviderId(agent)
            val conversation = Conversation(
                id = randomUuid(),
                title = title ?: getString(Res.string.conversations_new_chat),
                providerId = providerId,
                agentId = agent.id,
                createdAt = now,
                updatedAt = now,
                lastMessage = null
            )
            conversationRepository.insert(conversation)
            onCreated(conversation.id)
        }
    }

    /**
     * 清理建好后从未发过消息的空会话。规则在 Rust core
     * （`messenger_core::conversation`），这里只负责触发；聊天列表页进入时
     * 调用。[keepConversationId] 保护双栏布局中右栏正在展示的会话。
     */
    fun sweepEmptyConversations(keepConversationId: String? = null) {
        viewModelScope.launch {
            conversationRepository.sweepEmptyConversations(
                excludeIds = keepConversationId?.let { listOf(it) } ?: emptyList()
            )
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
        /** Recent Chats 区展示的会话条数。 */
        const val RECENT_CONVERSATION_LIMIT = 10

        fun provideFactory(
            conversationRepository: ConversationRepository,
            currentAgentRepository: CurrentAgentRepository,
            agentRepository: AgentRepository,
            modelRepository: ModelRepository,
            projectRepository: ProjectRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return ConversationsViewModel(
                    conversationRepository,
                    currentAgentRepository,
                    agentRepository,
                    modelRepository,
                    projectRepository
                ) as T
            }
        }
    }
}
