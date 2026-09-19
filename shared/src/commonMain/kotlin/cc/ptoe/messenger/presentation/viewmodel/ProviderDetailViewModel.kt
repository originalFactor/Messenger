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
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.model.applyModelsDev
import cc.ptoe.messenger.domain.repository.ApiRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ModelsDevRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.error_load_failed
import cc.ptoe.messenger.generated.resources.error_sync_failed
import org.jetbrains.compose.resources.getString
import cc.ptoe.messenger.data.util.randomUuid

enum class SyncStatus {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR
}

data class ProviderDetailUiState(
    val provider: Provider? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    val syncError: String? = null,
    val syncedModels: List<ChatModel> = emptyList(),
    val isMultiSelectMode: Boolean = false,
    val selectedModelIds: Set<String> = emptySet()
)

class ProviderDetailViewModel(
    private val providerRepository: ProviderRepository,
    private val modelRepository: ModelRepository,
    private val apiRepository: ApiRepository,
    private val modelsDevRepository: ModelsDevRepository,
    providerId: String
) : ViewModel() {

    /**
     * 当前正在查看的 Provider id。双栏布局下切换 Provider 时由 [loadProvider] 更新，
     * `models` StateFlow 通过 flatMapLatest 响应此值变化。
     */
    private val _providerIdFlow = MutableStateFlow(providerId)
    private val currentProviderId: String get() = _providerIdFlow.value

    private val _uiState = MutableStateFlow(ProviderDetailUiState())
    val uiState: StateFlow<ProviderDetailUiState> = _uiState.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val models = _providerIdFlow
        .flatMapLatest { id -> modelRepository.getByProviderId(id) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 加载 Provider 的协程 job。切换 Provider 时取消旧 job，避免多个 collect 并发污染 UiState。
     */
    private var loadJob: Job? = null

    init {
        loadProvider(providerId)
    }

    /**
     * 加载指定 id 的 Provider 到 UiState。
     * 双栏布局下切换 Provider 时由 [ProviderDetailScreen] 的 `LaunchedEffect(providerId)` 调用。
     */
    fun loadProvider(id: String) {
        loadJob?.cancel()
        _providerIdFlow.value = id
        // 重置多选状态，避免切换 Provider 后残留旧选中项
        _uiState.value = _uiState.value.copy(
            provider = null,
            isLoading = true,
            error = null,
            isMultiSelectMode = false,
            selectedModelIds = emptySet(),
            syncStatus = SyncStatus.IDLE,
            syncError = null,
            syncedModels = emptyList()
        )
        loadJob = viewModelScope.launch {
            try {
                providerRepository.getById(id).collect { provider ->
                    _uiState.value = _uiState.value.copy(
                        provider = provider,
                        isLoading = false
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: getString(Res.string.error_load_failed)
                )
            }
        }
    }

    fun syncModels() {
        val provider = _uiState.value.provider ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                syncStatus = SyncStatus.LOADING,
                syncError = null,
                syncedModels = emptyList()
            )
            try {
                val fetchedModels = apiRepository.fetchModels(provider)
                _uiState.value = _uiState.value.copy(
                    syncStatus = SyncStatus.SUCCESS,
                    syncedModels = fetchedModels
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    syncStatus = SyncStatus.ERROR,
                    syncError = e.message ?: getString(Res.string.error_sync_failed)
                )
            }
        }
    }

    fun resetSyncState() {
        _uiState.value = _uiState.value.copy(
            syncStatus = SyncStatus.IDLE,
            syncError = null,
            syncedModels = emptyList()
        )
    }

    suspend fun saveSelectedModels(selectedModelIds: List<String>) {
        val provider = _uiState.value.provider ?: return
        val existingModels = modelRepository.getByProviderId(currentProviderId).first()
        val existingModelIds = existingModels.map { it.modelId }.toSet()
        val newModels = _uiState.value.syncedModels
            .filter { it.modelId in selectedModelIds && it.modelId !in existingModelIds }
            .map { model ->
                model.copy(
                    id = randomUuid(),
                    providerId = provider.id,
                    isEnabled = true,
                    createdAt = System.currentTimeMillis()
                )
            }
        if (newModels.isNotEmpty()) {
            modelRepository.insertAll(newModels)
        }
        resetSyncState()
    }

    fun toggleModelEnabled(modelId: String, enabled: Boolean) {
        viewModelScope.launch {
            modelRepository.setEnabled(modelId, enabled)
        }
    }

    fun addModelManually(modelId: String, displayName: String): Boolean {
        val provider = _uiState.value.provider ?: return false
        if (modelId.isBlank()) return false
        viewModelScope.launch {
            val existingModels = modelRepository.getByProviderId(currentProviderId).first()
            val exists = existingModels.any { it.modelId == modelId }
            if (!exists) {
                val newModel = ChatModel(
                    id = randomUuid(),
                    providerId = provider.id,
                    modelId = modelId,
                    displayName = displayName.ifBlank { modelId },
                    isEnabled = true,
                    createdAt = System.currentTimeMillis()
                ).applyModelsDev(runCatching { modelsDevRepository.getMetadata(modelId) }.getOrNull())
                modelRepository.insert(newModel)
            }
        }
        return true
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            modelRepository.delete(modelId)
        }
    }

    fun enterMultiSelectMode(modelId: String) {
        _uiState.value = _uiState.value.copy(
            isMultiSelectMode = true,
            selectedModelIds = setOf(modelId)
        )
    }

    fun exitMultiSelectMode() {
        _uiState.value = _uiState.value.copy(
            isMultiSelectMode = false,
            selectedModelIds = emptySet()
        )
    }

    fun toggleModelSelection(modelId: String) {
        val current = _uiState.value.selectedModelIds
        val newSet = if (modelId in current) {
            current - modelId
        } else {
            current + modelId
        }
        _uiState.value = _uiState.value.copy(selectedModelIds = newSet)
    }

    fun selectAllModels() {
        val allIds = models.value.map { it.id }.toSet()
        _uiState.value = _uiState.value.copy(selectedModelIds = allIds)
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedModelIds = emptySet())
    }

    fun enableSelectedModels() {
        val ids = _uiState.value.selectedModelIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            modelRepository.setEnabledBatch(ids, true)
        }
    }

    fun disableSelectedModels() {
        val ids = _uiState.value.selectedModelIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            modelRepository.setEnabledBatch(ids, false)
        }
    }

    fun deleteSelectedModels() {
        val ids = _uiState.value.selectedModelIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            modelRepository.deleteBatch(ids)
            exitMultiSelectMode()
        }
    }

    companion object {
        fun provideFactory(
            providerRepository: ProviderRepository,
            modelRepository: ModelRepository,
            apiRepository: ApiRepository,
            modelsDevRepository: ModelsDevRepository,
            providerId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return ProviderDetailViewModel(
                    providerRepository,
                    modelRepository,
                    apiRepository,
                    modelsDevRepository,
                    providerId
                ) as T
            }
        }
    }
}
