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
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.conversations_clone_suffix
import cc.ptoe.messenger.generated.resources.conversations_new_chat
import org.jetbrains.compose.resources.getString
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.CurrentAgentRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 文件夹页的目标：项目（null = 无项目文件夹）与可选的 Agent 过滤。 */
private data class FolderTarget(
    val projectId: String?,
    val agentId: String?
)

/** 文件夹二级页的多选状态。 */
data class FolderUiState(
    val isMultiSelectMode: Boolean = false,
    val selectedConversationIds: Set<String> = emptySet()
)

/**
 * 文件夹二级页的 ViewModel。
 *
 * [projectId] 非 null = 项目的会话列表；null = “无项目”文件夹（普通对话）。
 * [agentId] 非 null 时只展示该 Agent 的会话（从 Agent 聊天列表进入的情形）。
 *
 * 两个目标都可能变化：双栏布局下右栏在同一个插槽里换文件夹，而
 * `viewModel()` 的默认 key 只按类名生成（Compose 的 `key(...)` 不参与），
 * 所以这里不能把构造参数当成一辈子不变——列表流必须跟着 [loadFolder]
 * 的目标走，否则点开第二个项目仍然显示第一个文件夹的会话，新建对话也会
 * 写进旧文件夹（见 [createNewConversation]）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectConversationsViewModel(
    initialProjectId: String?,
    initialAgentId: String?,
    private val projectRepository: ProjectRepository,
    private val conversationRepository: ConversationRepository,
    private val agentRepository: AgentRepository,
    private val messageRepository: MessageRepository,
    private val modelRepository: ModelRepository,
    private val currentAgentRepository: CurrentAgentRepository
) : ViewModel() {

    private val target = MutableStateFlow(FolderTarget(initialProjectId, initialAgentId))

    /** 当前展示的文件夹目标；新建会话写入它的 projectId/agentId。 */
    private val projectId: String? get() = target.value.projectId
    private val agentId: String? get() = target.value.agentId

    private val _uiState = MutableStateFlow(FolderUiState())
    val uiState: StateFlow<FolderUiState> = _uiState.asStateFlow()

    val project: StateFlow<Project?> = target
        .map { it.projectId }
        .flatMapLatest { id -> if (id == null) flowOf(null) else projectRepository.getById(id) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val conversations: StateFlow<List<Conversation>> = target
        .flatMapLatest { folder ->
            val conversations = if (folder.projectId == null) {
                conversationRepository.getAll().map { all -> all.filter { it.projectId == null } }
            } else {
                conversationRepository.getByProjectId(folder.projectId)
            }
            folder.agentId?.let { agentId -> conversations.map { all -> all.filter { it.agentId == agentId } } }
                ?: conversations
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 切换右栏展示的文件夹。目标未变时是 no-op，避免返回选择页再进入时
     * 重置多选状态。
     */
    fun loadFolder(projectId: String?, agentId: String?) {
        val next = FolderTarget(projectId, agentId)
        if (target.value == next) return
        target.value = next
        _uiState.value = FolderUiState()
    }

    val allAgents: StateFlow<List<Agent>> = agentRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun createNewConversation(
        agentIdOverride: String? = null,
        onCreated: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val agent = agentIdOverride?.let { agentRepository.getById(it).first() }
                ?: agentId?.let { agentRepository.getById(it).first() }
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

    /**
     * 清理建好后从未发过消息的空会话。规则在 Rust core
     * （`messenger_core::conversation`），这里只负责触发；文件夹页进入时
     * 调用。[keepConversationId] 保护双栏布局中右栏正在展示的会话。
     */
    fun sweepEmptyConversations(keepConversationId: String? = null) {
        viewModelScope.launch {
            conversationRepository.sweepEmptyConversations(
                excludeIds = keepConversationId?.let { listOf(it) } ?: emptyList()
            )
        }
    }

    fun moveToPlainChats(conversationId: String) {
        viewModelScope.launch {
            val conversation = conversationRepository.getById(conversationId).first() ?: return@launch
            conversationRepository.update(conversation.copy(projectId = null))
        }
    }

    fun cloneConversation(
        conversationId: String,
        onCloned: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val source = conversationRepository.getById(conversationId).first() ?: return@launch
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

    fun enterMultiSelectMode(conversationId: String) {
        _uiState.value = FolderUiState(
            isMultiSelectMode = true,
            selectedConversationIds = setOf(conversationId)
        )
    }

    fun exitMultiSelectMode() {
        _uiState.value = FolderUiState()
    }

    fun toggleSelection(conversationId: String) {
        val current = _uiState.value.selectedConversationIds
        val updated = if (conversationId in current) current - conversationId else current + conversationId
        _uiState.value = _uiState.value.copy(selectedConversationIds = updated)
    }

    fun selectAll(conversationIds: List<String>) {
        _uiState.value = _uiState.value.copy(selectedConversationIds = conversationIds.toSet())
    }

    fun deselectAll() {
        _uiState.value = _uiState.value.copy(selectedConversationIds = emptySet())
    }

    fun deleteConversationsBatch(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { id -> conversationRepository.delete(id) }
            exitMultiSelectMode()
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
            exitMultiSelectMode()
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
            projectId: String?,
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
