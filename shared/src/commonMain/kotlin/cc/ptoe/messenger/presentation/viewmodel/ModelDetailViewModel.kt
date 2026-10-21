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
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.ModelModality
import cc.ptoe.messenger.domain.repository.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.error_load_failed
import org.jetbrains.compose.resources.getString

data class ModelDetailUiState(
    val model: ChatModel? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** 上下文窗口输入框内容。空串 = 未知/不限（保存为 0）。 */
    val contextWindowText: String = "",
    val inputModalities: Set<ModelModality> = setOf(ModelModality.TEXT),
    val outputModalities: Set<ModelModality> = setOf(ModelModality.TEXT),
    val supportsToolCalling: Boolean = false,
    val supportsThinking: Boolean = false,
    val supportsJsonOutput: Boolean = false,
    val supportsTemperature: Boolean = false,
    /** 模型是否启用（禁用的模型不会出现在模型选择器中）。 */
    val isEnabled: Boolean = true,
    val isSaved: Boolean = false,
    val isDeleted: Boolean = false
)

class ModelDetailViewModel(
    private val modelRepository: ModelRepository,
    modelId: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelDetailUiState())
    val uiState: StateFlow<ModelDetailUiState> = _uiState.asStateFlow()

    init {
        loadModel(modelId)
    }

    fun loadModel(modelId: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                modelRepository.getById(modelId).collect { model ->
                    // 删除后 store 会推一次 null，不要把它当成加载失败。
                    if (_uiState.value.isDeleted) return@collect
                    // 保存后 store 会回推新值；不要用它清掉 isSaved，
                    // 否则界面可能在观察到保存成功之前就被重置。
                    if (_uiState.value.isSaved) return@collect
                    if (model != null) {
                        _uiState.value = ModelDetailUiState(
                            model = model,
                            isLoading = false,
                            contextWindowText =
                                if (model.contextWindow > 0) model.contextWindow.toString() else "",
                            // 旧数据可能为空集，兜底为默认文本模态
                            inputModalities = model.inputModalities.ifEmpty { setOf(ModelModality.TEXT) },
                            outputModalities = model.outputModalities.ifEmpty { setOf(ModelModality.TEXT) },
                            supportsToolCalling = model.supportsToolCalling,
                            supportsThinking = model.supportsThinking,
                            supportsJsonOutput = model.supportsJsonOutput,
                            supportsTemperature = model.supportsTemperature,
                            isEnabled = model.isEnabled
                        )
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = getString(Res.string.error_load_failed)
                        )
                    }
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

    fun onContextWindowChange(text: String) {
        // 只允许数字与空串
        if (text.isNotEmpty() && text.any { !it.isDigit() }) return
        _uiState.value = _uiState.value.copy(
            contextWindowText = text
        )
    }

    fun toggleInputModality(modality: ModelModality) {
        val current = _uiState.value.inputModalities
        // 至少保留一个模态，避免清空
        if (modality in current && current.size == 1) return
        _uiState.value = _uiState.value.copy(
            inputModalities = if (modality in current) current - modality else current + modality
        )
    }

    fun toggleOutputModality(modality: ModelModality) {
        val current = _uiState.value.outputModalities
        // 至少保留一个模态，避免清空
        if (modality in current && current.size == 1) return
        _uiState.value = _uiState.value.copy(
            outputModalities = if (modality in current) current - modality else current + modality
        )
    }

    fun onToolCallingChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(supportsToolCalling = enabled)
    }

    fun onThinkingChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(supportsThinking = enabled)
    }

    fun onJsonOutputChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(supportsJsonOutput = enabled)
    }

    fun onTemperatureChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(supportsTemperature = enabled)
    }

    fun onEnabledChange(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isEnabled = enabled)
    }

    fun save() {
        val state = _uiState.value
        val model = state.model ?: return

        val contextWindowValue = state.contextWindowText.toLongOrNull() ?: 0
        if (contextWindowValue < 0) return

        viewModelScope.launch {
            val updated = model.copy(
                contextWindow = contextWindowValue,
                inputModalities = state.inputModalities,
                outputModalities = state.outputModalities,
                supportsToolCalling = state.supportsToolCalling,
                supportsThinking = state.supportsThinking,
                supportsJsonOutput = state.supportsJsonOutput,
                supportsTemperature = state.supportsTemperature,
                isEnabled = state.isEnabled
            )
            modelRepository.update(updated)
            _uiState.value = state.copy(
                model = updated,
                isSaved = true
            )
        }
    }

    /**
     * 删除当前模型。删除即时生效（不像表单字段那样等保存），
     * 成功后界面直接退出编辑页。
     */
    fun delete() {
        val model = _uiState.value.model ?: return
        viewModelScope.launch {
            modelRepository.delete(model.id)
            _uiState.value = _uiState.value.copy(isDeleted = true)
        }
    }

    companion object {
        fun provideFactory(
            modelRepository: ModelRepository,
            modelId: String
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return ModelDetailViewModel(
                    modelRepository,
                    modelId
                ) as T
            }
        }
    }
}