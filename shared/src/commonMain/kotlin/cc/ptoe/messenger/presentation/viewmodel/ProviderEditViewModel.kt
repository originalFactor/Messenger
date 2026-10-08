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
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.ProviderRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.error_api_key_required
import cc.ptoe.messenger.generated.resources.error_api_url_required
import cc.ptoe.messenger.generated.resources.error_invalid_url
import cc.ptoe.messenger.generated.resources.error_name_required
import org.jetbrains.compose.resources.getString
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.randomUuid
import kotlin.reflect.KClass

data class ProviderEditUiState(
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val nameError: String? = null,
    val baseUrlError: String? = null,
    val apiKeyError: String? = null,
    val isEditing: Boolean = false,
    val isSaved: Boolean = false
)

class ProviderEditViewModel(
    private val providerRepository: ProviderRepository,
    providerId: String? = null
) : ViewModel() {

    /**
     * 当前正在编辑的 Provider id。null 表示新建 Provider。
     * 双栏布局下切换 Provider 时由 [loadProvider] 更新。
     */
    private var currentProviderId: String? = providerId

    private val _uiState = MutableStateFlow(ProviderEditUiState())
    val uiState: StateFlow<ProviderEditUiState> = _uiState.asStateFlow()

    /**
     * Pre-loaded localized validation messages. Compose Multiplatform's
     * `getString` is `suspend`, so resolve them once at construction time
     * and reuse from the synchronous [save] validator. The user can never
     * click Save before these complete (UI render + interaction is slower
     * than a handful of resource lookups).
     */
    private var errorMsgNameRequired: String = ""
    private var errorMsgApiUrlRequired: String = ""
    private var errorMsgApiKeyRequired: String = ""
    private var errorMsgInvalidUrl: String = ""

    /**
     * 加载 Provider 的协程 job。切换 Provider 时取消旧 job，避免多个 collect 并发污染 UiState。
     */
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            errorMsgNameRequired = getString(Res.string.error_name_required)
            errorMsgApiUrlRequired = getString(Res.string.error_api_url_required)
            errorMsgApiKeyRequired = getString(Res.string.error_api_key_required)
            errorMsgInvalidUrl = getString(Res.string.error_invalid_url)
        }
    }

    /**
     * 加载指定 id 的 Provider 到 UiState。传入 null 表示新建 Provider，重置为初始状态。
     * 双栏布局下切换 Provider 时由 [ProviderEditScreen] 的 `LaunchedEffect(providerId)` 调用。
     */
    fun loadProvider(id: String?) {
        loadJob?.cancel()
        currentProviderId = id
        if (id == null) {
            _uiState.value = ProviderEditUiState()
            return
        }
        loadJob = viewModelScope.launch {
            providerRepository.getById(id).collect { provider ->
                if (provider != null) {
                    _uiState.value = _uiState.value.copy(
                        name = provider.name,
                        baseUrl = provider.baseUrl,
                        apiKey = provider.apiKey,
                        isEditing = true
                    )
                }
            }
        }
    }

    fun onNameChange(name: String) {
        _uiState.value = _uiState.value.copy(
            name = name,
            nameError = null
        )
    }

    fun onBaseUrlChange(baseUrl: String) {
        _uiState.value = _uiState.value.copy(
            baseUrl = baseUrl,
            baseUrlError = null
        )
    }

    fun onApiKeyChange(apiKey: String) {
        _uiState.value = _uiState.value.copy(
            apiKey = apiKey,
            apiKeyError = null
        )
    }

    fun save(): Boolean {
        val currentState = _uiState.value
        var hasError = false

        val nameError = if (currentState.name.isBlank()) {
            errorMsgNameRequired
        } else null

        val baseUrlError = when {
            currentState.baseUrl.isBlank() -> errorMsgApiUrlRequired
            !isValidUrl(currentState.baseUrl) -> errorMsgInvalidUrl
            else -> null
        }

        val apiKeyError = if (currentState.apiKey.isBlank()) {
            errorMsgApiKeyRequired
        } else null

        if (nameError != null || baseUrlError != null || apiKeyError != null) {
            hasError = true
        }

        _uiState.value = currentState.copy(
            nameError = nameError,
            baseUrlError = baseUrlError,
            apiKeyError = apiKeyError
        )

        if (hasError) {
            return false
        }

        viewModelScope.launch {
            val now = currentTimeMillis()
            val editingId = currentProviderId
            if (editingId != null) {
                val existing = providerRepository.getById(editingId).first()
                if (existing != null) {
                    val updatedProvider = existing.copy(
                        name = currentState.name.trim(),
                        baseUrl = currentState.baseUrl.trim(),
                        apiKey = currentState.apiKey.trim(),
                        updatedAt = now
                    )
                    providerRepository.update(updatedProvider)
                    _uiState.value = _uiState.value.copy(isSaved = true)
                }
            } else {
                val newProvider = Provider(
                    id = randomUuid(),
                    name = currentState.name.trim(),
                    baseUrl = currentState.baseUrl.trim(),
                    apiKey = currentState.apiKey.trim(),
                    createdAt = now,
                    updatedAt = now
                )
                providerRepository.insert(newProvider)
                currentProviderId = newProvider.id
                _uiState.value = _uiState.value.copy(isSaved = true)
            }
        }

        return true
    }

    private fun isValidUrl(url: String): Boolean {
        // KMP-compatible URL validation — replaces the old android.net.Uri.parse check.
        // The startsWith check already covered the meaningful validation; the Uri.parse
        // call was effectively just a try/catch wrapper around it.
        return url.startsWith("http://") || url.startsWith("https://")
    }

    companion object {
        fun provideFactory(
            providerRepository: ProviderRepository,
            providerId: String? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return ProviderEditViewModel(providerRepository, providerId) as T
            }
        }
    }
}
