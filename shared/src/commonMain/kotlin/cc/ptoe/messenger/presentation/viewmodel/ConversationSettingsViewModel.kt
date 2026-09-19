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

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import kotlin.reflect.KClass
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

data class ConversationSettingsUiState(
    val title: String = "",
    val agentId: String = "",
    // 模型 override（开关与值解耦，避免 null 语义歧义）
    val overrideModelEnabled: Boolean = false,
    val overrideModelId: String? = null,
    // Temperature override
    val overrideTemperatureEnabled: Boolean = false,
    val overrideTemperatureValue: Float? = null,
    // Reasoning Effort override
    val overrideReasoningEffortEnabled: Boolean = false,
    val overrideReasoningEffortValue: String? = null,
    // Max Tokens override（null 值表示"不限"，需用 boolean 跟踪开关）
    val overrideMaxTokensEnabled: Boolean = false,
    val overrideMaxTokensValue: Int? = null,
    val selectedProviderId: String? = null,
    val isSaved: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSettingsViewModel(
    private val conversationRepository: ConversationRepository,
    private val agentRepository: AgentRepository,
    private val modelRepository: ModelRepository,
    private val providerRepository: ProviderRepository,
    private val conversationId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationSettingsUiState())
    val uiState: StateFlow<ConversationSettingsUiState> = _uiState.asStateFlow()

    val providers: StateFlow<List<Provider>> = providerRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val modelsForSelectedProvider: StateFlow<List<ChatModel>> = _uiState
        .map { it.selectedProviderId }
        .distinctUntilChanged()
        .flatMapLatest { providerId ->
            providerId?.let { pid ->
                modelRepository.getEnabledByProviderId(pid)
            } ?: flowOf(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val agent: StateFlow<Agent?> = _uiState
        .flatMapLatest { state ->
            if (state.agentId.isNotBlank()) {
                agentRepository.getById(state.agentId)
            } else {
                flowOf(null)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    init {
        viewModelScope.launch {
            val conv = conversationRepository.getById(conversationId).first()
            if (conv != null) {
                val agent = agentRepository.getById(conv.agentId).first()
                val defaultProviderId = determineProviderId(agent, conv)
                _uiState.value = _uiState.value.copy(
                    title = conv.title,
                    agentId = conv.agentId,
                    overrideModelEnabled = conv.overrideModelId != null,
                    overrideModelId = conv.overrideModelId,
                    overrideTemperatureEnabled = conv.overrideTemperature != null,
                    overrideTemperatureValue = conv.overrideTemperature,
                    overrideReasoningEffortEnabled = conv.overrideReasoningEffort != null,
                    overrideReasoningEffortValue = conv.overrideReasoningEffort,
                    overrideMaxTokensEnabled = conv.overrideMaxTokens != null,
                    overrideMaxTokensValue = conv.overrideMaxTokens,
                    selectedProviderId = conv.overrideModelId?.let { modelId ->
                        modelRepository.getAll().first().find { it.id == modelId }?.providerId
                    } ?: defaultProviderId
                )
            }
        }
    }

    private suspend fun determineProviderId(agent: Agent?, conv: Conversation): String? {
        if (agent == null) return null
        val allModels = modelRepository.getAll().first()
        val enabledModels = allModels.filter { it.isEnabled }
        if (enabledModels.isEmpty()) return null

        val defaultModel = agent.defaultModelId?.let { modelId ->
            enabledModels.find { it.id == modelId }
        }

        return (defaultModel ?: enabledModels.firstOrNull())?.providerId
    }

    fun onTitleChange(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
    }

    fun onProviderChange(providerId: String?) {
        _uiState.value = _uiState.value.copy(
            selectedProviderId = providerId,
            overrideModelId = null
        )
    }

    fun onModelChange(modelId: String?) {
        _uiState.value = _uiState.value.copy(overrideModelId = modelId)
    }

    /**
     * 模型选择页回传结果：按模型反查所属 Provider，并一次性原子更新
     * provider + model，避免"先清空模型再设置"的中间态与竞态。
     */
    fun onModelPicked(modelId: String) {
        viewModelScope.launch {
            val model = modelRepository.getById(modelId).first() ?: return@launch
            _uiState.value = _uiState.value.copy(
                selectedProviderId = model.providerId,
                overrideModelId = model.id
            )
        }
    }

    fun onOverrideModelChange(override: Boolean) {
        _uiState.value = _uiState.value.copy(overrideModelEnabled = override)
    }

    fun onOverrideTemperatureChange(override: Boolean, value: Float? = null) {
        _uiState.value = _uiState.value.copy(
            overrideTemperatureEnabled = override,
            overrideTemperatureValue = if (override) value ?: 0.7f else null
        )
    }

    fun onTemperatureChange(value: Float) {
        _uiState.value = _uiState.value.copy(overrideTemperatureValue = value)
    }

    fun onOverrideReasoningEffortChange(override: Boolean, value: String? = null) {
        _uiState.value = _uiState.value.copy(
            overrideReasoningEffortEnabled = override,
            overrideReasoningEffortValue = if (override) value else null
        )
    }

    fun onReasoningEffortChange(value: String?) {
        _uiState.value = _uiState.value.copy(overrideReasoningEffortValue = value)
    }

    fun onOverrideMaxTokensChange(override: Boolean, value: Int? = null) {
        // value 为 null 表示"不限"，需保留 override 状态由 boolean 跟踪
        _uiState.value = _uiState.value.copy(
            overrideMaxTokensEnabled = override,
            overrideMaxTokensValue = if (override) value else null
        )
    }

    fun onMaxTokensChange(value: Int?) {
        _uiState.value = _uiState.value.copy(overrideMaxTokensValue = value)
    }

    fun save() {
        viewModelScope.launch {
            val conv = conversationRepository.getById(conversationId).first() ?: return@launch
            val now = System.currentTimeMillis()
            val state = _uiState.value
            conversationRepository.update(
                conv.copy(
                    title = state.title,
                    overrideModelId = if (state.overrideModelEnabled) state.overrideModelId else null,
                    overrideTemperature = if (state.overrideTemperatureEnabled) state.overrideTemperatureValue else null,
                    overrideReasoningEffort = if (state.overrideReasoningEffortEnabled) state.overrideReasoningEffortValue else null,
                    overrideMaxTokens = if (state.overrideMaxTokensEnabled) state.overrideMaxTokensValue else null,
                    updatedAt = now
                )
            )
            _uiState.value = state.copy(isSaved = true)
        }
    }

    companion object {
        fun provideFactory(
            conversationRepository: ConversationRepository,
            agentRepository: AgentRepository,
            modelRepository: ModelRepository,
            providerRepository: ProviderRepository,
            conversationId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return ConversationSettingsViewModel(
                    conversationRepository,
                    agentRepository,
                    modelRepository,
                    providerRepository,
                    conversationId
                ) as T
            }
        }
    }
}
