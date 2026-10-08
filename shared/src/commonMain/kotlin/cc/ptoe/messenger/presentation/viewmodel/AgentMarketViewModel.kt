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
import cc.ptoe.messenger.data.cloud.CloudMarketAgent
import cc.ptoe.messenger.domain.repository.CloudFacade
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.error_load_agent_failed
import cc.ptoe.messenger.generated.resources.error_load_market_failed
import cc.ptoe.messenger.generated.resources.error_load_more_failed
import org.jetbrains.compose.resources.getString
import kotlin.reflect.KClass

data class AgentMarketUiState(
    val isLoading: Boolean = true,
    val agents: List<CloudMarketAgent> = emptyList(),
    val error: String? = null,
    val nextCursor: String? = null,
    val isLoadingMore: Boolean = false,
    val importingAgentId: String? = null
)

class AgentMarketViewModel(
    private val cloudSyncRepository: CloudFacade
) : ViewModel() {
    private val _uiState = MutableStateFlow(AgentMarketUiState())
    val uiState: StateFlow<AgentMarketUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null
    private var query: String = ""

    init {
        refresh()
    }

    fun refresh(query: String = "") {
        this.query = query
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { cloudSyncRepository.listMarketAgents(query) }
                .onSuccess { response ->
                    _uiState.value = AgentMarketUiState(
                        isLoading = false,
                        agents = response.agents,
                        nextCursor = response.nextCursor
                    )
                }
                .onFailure { error ->
                    _uiState.value = AgentMarketUiState(isLoading = false, error = error.message ?: getString(Res.string.error_load_market_failed))
                }
        }
    }

    fun loadMore() {
        val cursor = _uiState.value.nextCursor ?: return
        if (_uiState.value.isLoadingMore) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true, error = null)
            runCatching { cloudSyncRepository.listMarketAgents(query, cursor) }
                .onSuccess { response ->
                    _uiState.value = _uiState.value.copy(
                        agents = _uiState.value.agents + response.agents,
                        nextCursor = response.nextCursor,
                        isLoadingMore = false
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoadingMore = false,
                        error = error.message ?: getString(Res.string.error_load_more_failed)
                    )
                }
        }
    }

    fun importAgent(marketAgentId: String, onResult: (Result<Unit>) -> Unit) {
        if (_uiState.value.importingAgentId != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(importingAgentId = marketAgentId)
            val result = runCatching { cloudSyncRepository.importMarketAgent(marketAgentId) }
            _uiState.value = _uiState.value.copy(importingAgentId = null)
            onResult(result.map { Unit })
        }
    }

    companion object {
        fun provideFactory(cloudSyncRepository: CloudFacade): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                    AgentMarketViewModel(cloudSyncRepository) as T
            }
    }
}

data class AgentMarketDetailUiState(
    val isLoading: Boolean = true,
    val agent: CloudMarketAgent? = null,
    val error: String? = null,
    val isImporting: Boolean = false
)

class AgentMarketDetailViewModel(
    private val cloudSyncRepository: CloudFacade,
    private val marketAgentId: String
) : ViewModel() {
    private val _uiState = MutableStateFlow(AgentMarketDetailUiState())
    val uiState: StateFlow<AgentMarketDetailUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { cloudSyncRepository.marketAgent(marketAgentId) }
                .onSuccess { agent -> _uiState.value = AgentMarketDetailUiState(isLoading = false, agent = agent) }
                .onFailure { error ->
                    _uiState.value = AgentMarketDetailUiState(isLoading = false, error = error.message ?: getString(Res.string.error_load_agent_failed))
                }
        }
    }

    fun importAgent(onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true)
            val result = runCatching { cloudSyncRepository.importMarketAgent(marketAgentId) }
            _uiState.value = _uiState.value.copy(isImporting = false)
            onResult(result.map { Unit })
        }
    }

    companion object {
        fun provideFactory(
            cloudSyncRepository: CloudFacade,
            marketAgentId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                AgentMarketDetailViewModel(cloudSyncRepository, marketAgentId) as T
        }
    }
}
