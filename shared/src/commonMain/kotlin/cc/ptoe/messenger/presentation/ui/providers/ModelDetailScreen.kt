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

package cc.ptoe.messenger.presentation.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.domain.model.ModelModality
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.ui.components.LoadingIndicator
import cc.ptoe.messenger.presentation.ui.components.SectionHeader
import cc.ptoe.messenger.presentation.viewmodel.ModelDetailViewModel
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_save
import cc.ptoe.messenger.generated.resources.error_load_failed
import cc.ptoe.messenger.generated.resources.modality_audio
import cc.ptoe.messenger.generated.resources.modality_image
import cc.ptoe.messenger.generated.resources.modality_text
import cc.ptoe.messenger.generated.resources.modality_video
import cc.ptoe.messenger.generated.resources.providers_model_capabilities
import cc.ptoe.messenger.generated.resources.providers_model_context_window_hint
import cc.ptoe.messenger.generated.resources.providers_model_context_window_label
import cc.ptoe.messenger.generated.resources.providers_model_detail_title
import cc.ptoe.messenger.generated.resources.providers_model_id_label
import cc.ptoe.messenger.generated.resources.providers_model_input_modalities
import cc.ptoe.messenger.generated.resources.providers_model_output_modalities
import cc.ptoe.messenger.generated.resources.providers_model_saved
import cc.ptoe.messenger.generated.resources.providers_model_supports_json_output
import cc.ptoe.messenger.generated.resources.providers_model_supports_temperature
import cc.ptoe.messenger.generated.resources.providers_model_supports_thinking
import cc.ptoe.messenger.generated.resources.providers_model_supports_tool_calling
import cc.ptoe.messenger.di.AppContainerHolder
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDetailScreen(
    modelId: String,
    onBackClick: () -> Unit,
    onSaved: () -> Unit = onBackClick,
    viewModel: ModelDetailViewModel = viewModel(
        factory = ModelDetailViewModel.provideFactory(
            modelRepository = AppContainerHolder.instance.modelRepository,
            modelId = modelId
        )
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val strSaved = stringResource(Res.string.providers_model_saved)
    val strLoadFailed = stringResource(Res.string.error_load_failed)

    // 双栏布局下切换模型时按 id 重新加载，避免依赖 ViewModel 重建
    LaunchedEffect(modelId) {
        viewModel.loadModel(modelId)
    }

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            snackbarHostState.showSnackbar(strSaved)
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.model?.displayName
                            ?: stringResource(Res.string.providers_model_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.save() },
                        enabled = uiState.model != null
                    ) {
                        Text(stringResource(Res.string.action_save))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        if (uiState.isLoading) {
            LoadingIndicator(modifier = contentModifier)
        } else if (uiState.error != null) {
            EmptyState(
                icon = Icons.Default.Cloud,
                message = uiState.error ?: strLoadFailed,
                modifier = contentModifier
            )
        } else {
            Column(
                modifier = contentModifier
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 只读：模型 ID
                Column {
                    Text(
                        text = stringResource(Res.string.providers_model_id_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = uiState.model?.modelId.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                SectionHeader(title = stringResource(Res.string.providers_model_context_window_label))

                Spacer(modifier = Modifier.height(8.dp))

                // 上下文窗口
                OutlinedTextField(
                    value = uiState.contextWindowText,
                    onValueChange = { viewModel.onContextWindowChange(it) },
                    label = { Text(stringResource(Res.string.providers_model_context_window_label)) },
                    placeholder = { Text(stringResource(Res.string.providers_model_context_window_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                SectionHeader(title = stringResource(Res.string.providers_model_capabilities))

                Spacer(modifier = Modifier.height(8.dp))

                // 输入模态
                ModalityChipGroup(
                    label = stringResource(Res.string.providers_model_input_modalities),
                    selected = uiState.inputModalities,
                    onToggle = { viewModel.toggleInputModality(it) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 输出模态
                ModalityChipGroup(
                    label = stringResource(Res.string.providers_model_output_modalities),
                    selected = uiState.outputModalities,
                    onToggle = { viewModel.toggleOutputModality(it) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 能力开关
                CapabilitySwitchRow(
                    label = stringResource(Res.string.providers_model_supports_tool_calling),
                    checked = uiState.supportsToolCalling,
                    onCheckedChange = { viewModel.onToolCallingChange(it) }
                )
                CapabilitySwitchRow(
                    label = stringResource(Res.string.providers_model_supports_thinking),
                    checked = uiState.supportsThinking,
                    onCheckedChange = { viewModel.onThinkingChange(it) }
                )
                CapabilitySwitchRow(
                    label = stringResource(Res.string.providers_model_supports_json_output),
                    checked = uiState.supportsJsonOutput,
                    onCheckedChange = { viewModel.onJsonOutputChange(it) }
                )
                CapabilitySwitchRow(
                    label = stringResource(Res.string.providers_model_supports_temperature),
                    checked = uiState.supportsTemperature,
                    onCheckedChange = { viewModel.onTemperatureChange(it) }
                )
            }
        }
    }
}

/**
 * 模态多选标签组。
 */
@Composable
private fun ModalityChipGroup(
    label: String,
    selected: Set<ModelModality>,
    onToggle: (ModelModality) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModelModality.entries.forEach { modality ->
                FilterChip(
                    selected = modality in selected,
                    onClick = { onToggle(modality) },
                    label = { Text(text = modalityLabel(modality)) },
                    leadingIcon = if (modality in selected) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.width(16.dp)
                            )
                        }
                    } else null
                )
            }
        }
    }
}

@Composable
private fun modalityLabel(modality: ModelModality): String = when (modality) {
    ModelModality.TEXT -> stringResource(Res.string.modality_text)
    ModelModality.IMAGE -> stringResource(Res.string.modality_image)
    ModelModality.AUDIO -> stringResource(Res.string.modality_audio)
    ModelModality.VIDEO -> stringResource(Res.string.modality_video)
}

@Composable
private fun CapabilitySwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}