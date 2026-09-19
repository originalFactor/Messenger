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
import cc.ptoe.messenger.data.cloud.CloudMarketAgentUpdate
import cc.ptoe.messenger.data.cloud.CloudSyncRepository
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.error_name_required
import org.jetbrains.compose.resources.getString
import cc.ptoe.messenger.data.util.randomUuid
import kotlin.reflect.KClass

data class AgentEditUiState(
    val name: String = "",
    val avatar: String? = null,
    val systemPrompt: String = "",
    val defaultModelId: String? = null,
    val selectedProviderId: String? = null,
    val temperature: Float = 0.7f,
    val topP: Float = 1.0f,
    val maxTokens: String? = null,
    val reasoningEffort: String? = null,
    val isDefault: Boolean = false,
    val followDefaultSystemPrompt: Boolean = false,
    val followDefaultModel: Boolean = false,
    val followDefaultTemperature: Boolean = false,
    val followDefaultTopP: Boolean = false,
    val followDefaultMaxTokens: Boolean = false,
    val followDefaultReasoningEffort: Boolean = false,
    val defaultAgent: Agent? = null,
    val nameError: String? = null,
    val isEditing: Boolean = false,
    val isSaved: Boolean = false,
    val marketAgentId: String? = null,
    val marketAgentRole: String? = null,
    val marketActionInProgress: Boolean = false
)

class AgentEditViewModel(
    private val agentRepository: AgentRepository,
    private val modelRepository: ModelRepository,
    private val providerRepository: ProviderRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    agentId: String? = null
) : ViewModel() {

    /**
     * 当前正在编辑的 Agent id。null 表示新建 Agent。
     * 双栏布局下切换 Agent 时由 [loadAgent] 更新，`save()` / 市场操作读取此值。
     */
    private var currentAgentId: String? = agentId

    private val _uiState = MutableStateFlow(AgentEditUiState())
    val uiState: StateFlow<AgentEditUiState> = _uiState.asStateFlow()

    /**
     * Pre-loaded localized error message. Compose Multiplatform's `getString`
     * is `suspend`, so we resolve it once at construction time and reuse it
     * from the synchronous [save] validator. The user can never click Save
     * before this completes (UI render + interaction is slower than a single
     * resource lookup).
     */
    private var errorMsgNameRequired: String = ""

    val providers: StateFlow<List<Provider>> = providerRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val modelsForSelectedProvider: StateFlow<List<ChatModel>> = _uiState
        .map { it.selectedProviderId }
        .distinctUntilChanged()
        .flatMapLatest { providerId ->
            if (providerId == null) {
                flowOf(emptyList())
            } else {
                modelRepository.getEnabledByProviderId(providerId)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 加载 Agent 的协程 job。切换 Agent 时取消旧 job，避免多个 collect 并发污染 UiState。
     */
    private var loadJob: Job? = null

    /**
     * 已加载过的目标。从选择页返回等场景组合重建会再次调用 [loadAgent]（相同 id），
     * 此时若重新读取数据库会覆盖用户尚未保存的编辑（如刚选的 defaultModelId），
     * 因此对已加载过的相同目标直接跳过。
     */
    private var loadedTarget: String? = null
    private var hasLoaded = false

    init {
        // 始终加载默认 Agent，用于非默认 Agent 的"跟随"展示
        viewModelScope.launch {
            agentRepository.getAll().collect { agents ->
                val default = agents.firstOrNull { it.isDefault }
                _uiState.value = _uiState.value.copy(defaultAgent = default)
            }
        }
        // Pre-load localized validation message (Compose Multiplatform getString is suspend).
        viewModelScope.launch {
            errorMsgNameRequired = getString(Res.string.error_name_required)
        }
    }

    /**
     * 加载指定 id 的 Agent 到 UiState。传入 null 表示新建 Agent，重置为初始状态。
     * 双栏布局下切换 Agent 时由 [AgentEditScreen] 的 `LaunchedEffect(agentId)` 调用，
     * 避免依赖 ViewModel 重建（`viewModel()` 按 ViewModelStoreOwner 缓存，position 不变时
     * 不会重建）。
     */
    fun loadAgent(id: String?) {
        // 同一目标已加载过则跳过：从选择页返回时组合重建会再次调用 loadAgent(相同 id)，
        // 重新读取数据库会把表单中尚未保存的编辑（defaultModelId 等）覆盖回旧值
        if (hasLoaded && loadedTarget == id) {
            return
        }
        hasLoaded = true
        loadedTarget = id
        loadJob?.cancel()
        currentAgentId = id
        if (id == null) {
            _uiState.value = AgentEditUiState(defaultAgent = _uiState.value.defaultAgent)
            return
        }
        loadJob = viewModelScope.launch {
            val agent = agentRepository.getById(id).first()
            if (agent != null) {
                val providerId = agent.defaultModelId?.let { modelId ->
                    modelRepository.getById(modelId).first()?.providerId
                }
                _uiState.value = _uiState.value.copy(
                    name = agent.name,
                    avatar = agent.avatar,
                    systemPrompt = agent.systemPrompt,
                    defaultModelId = agent.defaultModelId,
                    selectedProviderId = providerId,
                    temperature = agent.temperature,
                    topP = agent.topP,
                    maxTokens = agent.maxTokens?.toString(),
                    reasoningEffort = agent.reasoningEffort,
                    isDefault = agent.isDefault,
                    followDefaultSystemPrompt = agent.followDefaultSystemPrompt,
                    followDefaultModel = agent.followDefaultModel,
                    followDefaultTemperature = agent.followDefaultTemperature,
                    followDefaultTopP = agent.followDefaultTopP,
                    followDefaultMaxTokens = agent.followDefaultMaxTokens,
                    followDefaultReasoningEffort = agent.followDefaultReasoningEffort,
                    marketAgentId = agent.marketAgentId,
                    marketAgentRole = agent.marketAgentRole,
                    isEditing = true
                )
            }
        }
    }

    fun onNameChange(name: String) {
        _uiState.value = _uiState.value.copy(
            name = name,
            nameError = null
        )
    }

    fun onAvatarChange(avatar: String?) {
        _uiState.value = _uiState.value.copy(avatar = avatar)
    }

    fun onSystemPromptChange(systemPrompt: String) {
        _uiState.value = _uiState.value.copy(systemPrompt = systemPrompt)
    }

    fun onProviderChange(providerId: String?) {
        // 切换 Provider 时清空已选模型，避免出现 provider 与 model 不匹配的情况
        _uiState.value = _uiState.value.copy(
            selectedProviderId = providerId,
            defaultModelId = null
        )
    }

    fun onDefaultModelChange(modelId: String?) {
        _uiState.value = _uiState.value.copy(defaultModelId = modelId)
    }

    /**
     * 模型选择页回传结果：按模型反查所属 Provider，并一次性原子更新
     * provider + 默认模型，避免"先清空模型再设置"的中间态与竞态。
     */
    fun onModelPicked(modelId: String) {
        viewModelScope.launch {
            val model = modelRepository.getById(modelId).first() ?: return@launch
            _uiState.value = _uiState.value.copy(
                selectedProviderId = model.providerId,
                defaultModelId = model.id
            )
        }
    }

    fun onTemperatureChange(temperature: Float) {
        _uiState.value = _uiState.value.copy(temperature = temperature)
    }

    fun onTopPChange(topP: Float) {
        _uiState.value = _uiState.value.copy(topP = topP)
    }

    fun onMaxTokensChange(maxTokens: String?) {
        _uiState.value = _uiState.value.copy(maxTokens = maxTokens)
    }

    fun onFollowSystemPromptChange(follow: Boolean) {
        _uiState.value = _uiState.value.copy(followDefaultSystemPrompt = follow)
    }

    fun onFollowModelChange(follow: Boolean) {
        _uiState.value = _uiState.value.copy(followDefaultModel = follow)
    }

    fun onFollowTemperatureChange(follow: Boolean) {
        _uiState.value = _uiState.value.copy(followDefaultTemperature = follow)
    }

    fun onFollowTopPChange(follow: Boolean) {
        _uiState.value = _uiState.value.copy(followDefaultTopP = follow)
    }

    fun onFollowMaxTokensChange(follow: Boolean) {
        _uiState.value = _uiState.value.copy(followDefaultMaxTokens = follow)
    }

    fun onReasoningEffortChange(effort: String?) {
        _uiState.value = _uiState.value.copy(reasoningEffort = effort)
    }

    fun onFollowReasoningEffortChange(follow: Boolean) {
        _uiState.value = _uiState.value.copy(followDefaultReasoningEffort = follow)
    }

    fun save(): Boolean {
        val currentState = _uiState.value
        var hasError = false

        val nameError = if (currentState.name.isBlank()) {
            errorMsgNameRequired
        } else null

        if (nameError != null) {
            hasError = true
        }

        _uiState.value = currentState.copy(
            nameError = nameError
        )

        if (hasError) {
            return false
        }

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val maxTokensInt = currentState.maxTokens?.toIntOrNull()

            val editingId = currentAgentId
            if (editingId != null) {
                val existing = agentRepository.getById(editingId).first()
                if (existing != null) {
                    val updatedAgent = existing.copy(
                        name = currentState.name.trim(),
                        avatar = currentState.avatar,
                        systemPrompt = currentState.systemPrompt.trim(),
                        defaultModelId = currentState.defaultModelId,
                        temperature = currentState.temperature,
                        topP = currentState.topP,
                        maxTokens = maxTokensInt,
                        reasoningEffort = currentState.reasoningEffort,
                        followDefaultSystemPrompt = currentState.followDefaultSystemPrompt,
                        followDefaultModel = currentState.followDefaultModel,
                        followDefaultTemperature = currentState.followDefaultTemperature,
                        followDefaultTopP = currentState.followDefaultTopP,
                        followDefaultMaxTokens = currentState.followDefaultMaxTokens,
                        followDefaultReasoningEffort = currentState.followDefaultReasoningEffort,
                        updatedAt = now
                    )
                    agentRepository.update(updatedAgent)
                    _uiState.value = _uiState.value.copy(isSaved = true)
                }
            } else {
                val newAgent = Agent(
                    id = randomUuid(),
                    name = currentState.name.trim(),
                    avatar = currentState.avatar,
                    systemPrompt = currentState.systemPrompt.trim(),
                    defaultModelId = currentState.defaultModelId,
                    temperature = currentState.temperature,
                    topP = currentState.topP,
                    maxTokens = maxTokensInt,
                    reasoningEffort = currentState.reasoningEffort,
                    isDefault = false,
                    followDefaultSystemPrompt = currentState.followDefaultSystemPrompt,
                    followDefaultModel = currentState.followDefaultModel,
                    followDefaultTemperature = currentState.followDefaultTemperature,
                    followDefaultTopP = currentState.followDefaultTopP,
                    followDefaultMaxTokens = currentState.followDefaultMaxTokens,
                    followDefaultReasoningEffort = currentState.followDefaultReasoningEffort,
                    createdAt = now,
                    updatedAt = now
                )
                agentRepository.insert(newAgent)
                currentAgentId = newAgent.id
                _uiState.value = _uiState.value.copy(isSaved = true)
            }
        }

        return true
    }

    fun publishMarketAgent(onResult: (Result<Unit>) -> Unit) = runMarketAction(onResult) {
        cloudSyncRepository.publishMarketAgent(requireNotNull(currentAgentId))
    }

    fun pushMarketAgentUpdate(onResult: (Result<Unit>) -> Unit) = runMarketAction(onResult) {
        cloudSyncRepository.pushMarketAgentUpdate(requireNotNull(currentAgentId))
    }

    fun removeMarketAgent(onResult: (Result<Unit>) -> Unit) = runMarketAction(onResult) {
        cloudSyncRepository.removeMarketAgent(requireNotNull(currentAgentId))
    }

    fun checkMarketAgentUpdate(onResult: (Result<CloudMarketAgentUpdate>) -> Unit) {
        val id = currentAgentId ?: return onResult(Result.failure(IllegalStateException("Save the Agent before checking updates.")))
        _uiState.value = _uiState.value.copy(marketActionInProgress = true)
        viewModelScope.launch {
            val result = runCatching { cloudSyncRepository.checkMarketAgentUpdate(id) }
            _uiState.value = _uiState.value.copy(marketActionInProgress = false)
            onResult(result)
        }
    }

    fun applyMarketAgentUpdate(onResult: (Result<Unit>) -> Unit) = runMarketAction(onResult) {
        val update = cloudSyncRepository.checkMarketAgentUpdate(requireNotNull(currentAgentId))
        check(update.hasUpdate) { "This Agent is already up to date." }
        cloudSyncRepository.applyMarketAgentUpdate(requireNotNull(currentAgentId), update.agent)
    }

    private fun runMarketAction(onResult: (Result<Unit>) -> Unit, action: suspend () -> Unit) {
        if (currentAgentId == null) {
            onResult(Result.failure(IllegalStateException("Save the Agent before using the market.")))
            return
        }
        _uiState.value = _uiState.value.copy(marketActionInProgress = true)
        viewModelScope.launch {
            val result = runCatching {
                saveCurrentAgentForMarket()
                action()
            }
            _uiState.value = _uiState.value.copy(marketActionInProgress = false)
            onResult(result)
        }
    }

    private suspend fun saveCurrentAgentForMarket() {
        val id = requireNotNull(currentAgentId)
        val currentState = _uiState.value
        check(currentState.name.isNotBlank()) { "名称不能为空" }
        val existing = checkNotNull(agentRepository.getById(id).first()) { "Agent 不存在" }
        agentRepository.update(
            existing.copy(
                name = currentState.name.trim(),
                avatar = currentState.avatar,
                systemPrompt = currentState.systemPrompt.trim(),
                defaultModelId = currentState.defaultModelId,
                temperature = currentState.temperature,
                topP = currentState.topP,
                maxTokens = currentState.maxTokens?.toIntOrNull(),
                reasoningEffort = currentState.reasoningEffort,
                followDefaultSystemPrompt = currentState.followDefaultSystemPrompt,
                followDefaultModel = currentState.followDefaultModel,
                followDefaultTemperature = currentState.followDefaultTemperature,
                followDefaultTopP = currentState.followDefaultTopP,
                followDefaultMaxTokens = currentState.followDefaultMaxTokens,
                followDefaultReasoningEffort = currentState.followDefaultReasoningEffort,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        fun provideFactory(
            agentRepository: AgentRepository,
            modelRepository: ModelRepository,
            providerRepository: ProviderRepository,
            cloudSyncRepository: CloudSyncRepository,
            agentId: String? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return AgentEditViewModel(
                    agentRepository,
                    modelRepository,
                    providerRepository,
                    cloudSyncRepository,
                    agentId
                ) as T
            }
        }
    }
}
