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

package cc.ptoe.messenger.presentation.ui.conversations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.presentation.ui.components.ListItem
import cc.ptoe.messenger.presentation.ui.components.SectionHeader
import cc.ptoe.messenger.presentation.utils.formatOneDecimal
import cc.ptoe.messenger.presentation.viewmodel.ConversationSettingsViewModel
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_save
import cc.ptoe.messenger.generated.resources.agent_edit_max_tokens_label
import cc.ptoe.messenger.generated.resources.agent_edit_max_tokens_placeholder
import cc.ptoe.messenger.generated.resources.agent_edit_reasoning_effort_default
import cc.ptoe.messenger.generated.resources.agent_edit_reasoning_effort_label
import cc.ptoe.messenger.generated.resources.conversation_settings_agent_label
import cc.ptoe.messenger.generated.resources.conversation_settings_override_desc
import cc.ptoe.messenger.generated.resources.conversation_settings_override_max_tokens
import cc.ptoe.messenger.generated.resources.conversation_settings_override_model
import cc.ptoe.messenger.generated.resources.conversation_settings_override_reasoning_effort
import cc.ptoe.messenger.generated.resources.conversation_settings_override_section
import cc.ptoe.messenger.generated.resources.conversation_settings_override_temperature
import cc.ptoe.messenger.generated.resources.conversation_settings_select_model
import cc.ptoe.messenger.generated.resources.conversation_settings_select_provider
import cc.ptoe.messenger.generated.resources.conversation_settings_temperature_value
import cc.ptoe.messenger.generated.resources.conversation_settings_title
import cc.ptoe.messenger.generated.resources.conversation_settings_title_label
import cc.ptoe.messenger.generated.resources.provider_model_picker_label
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSettingsScreen(
    conversationId: String,
    onBackClick: () -> Unit,
    onSaved: () -> Unit,
    onPickProvider: (String?) -> Unit = {},
    pickedModelId: String? = null,
    onPickedModelConsumed: () -> Unit = {},
    viewModel: ConversationSettingsViewModel = viewModel(
        factory = ConversationSettingsViewModel.provideFactory(
            conversationRepository = AppContainerHolder.instance.conversationRepository,
            agentRepository = AppContainerHolder.instance.agentRepository,
            modelRepository = AppContainerHolder.instance.modelRepository,
            providerRepository = AppContainerHolder.instance.providerRepository,
            conversationId = conversationId
        )
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val models by viewModel.modelsForSelectedProvider.collectAsStateWithLifecycle(initialValue = emptyList())
    val agent by viewModel.agent.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onSaved()
        }
    }

    LaunchedEffect(pickedModelId) {
        val modelId = pickedModelId
        if (modelId != null) {
            // 原子应用：ViewModel 按模型反查所属 Provider 并一次更新，避免清空再设置的中间态
            viewModel.onModelPicked(modelId)
            onPickedModelConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.conversation_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.save() }) {
                        Text(stringResource(Res.string.action_save))
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = uiState.title,
                    onValueChange = { viewModel.onTitleChange(it) },
                    label = { Text(stringResource(Res.string.conversation_settings_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.conversation_settings_agent_label, agent?.name ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                SectionHeader(title = stringResource(Res.string.conversation_settings_override_section))
                Text(
                    text = stringResource(Res.string.conversation_settings_override_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 模型覆盖
                OverrideToggleRow(
                    label = stringResource(Res.string.conversation_settings_override_model),
                    checked = uiState.overrideModelEnabled,
                    onCheckedChange = { checked ->
                        viewModel.onOverrideModelChange(checked)
                    }
                )
                if (uiState.overrideModelEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    // Provider + 模型合并为一个设置条目，subtitle 展示「Provider 名 · 模型 ID」
                    val selectedProvider = providers.find { it.id == uiState.selectedProviderId }
                    val selectedModel = models.find { it.id == uiState.overrideModelId }
                    val subtitle = when {
                        selectedModel != null && selectedProvider != null ->
                            "${selectedProvider.name} · ${selectedModel.modelId}"
                        selectedProvider != null -> selectedProvider.name
                        else -> stringResource(Res.string.conversation_settings_select_provider)
                    }
                    ListItem(
                        title = stringResource(Res.string.provider_model_picker_label),
                        subtitle = subtitle,
                        icon = Icons.Default.SmartToy,
                        onClick = { onPickProvider(uiState.selectedProviderId) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Temperature 覆盖
                OverrideToggleRow(
                    label = stringResource(Res.string.conversation_settings_override_temperature),
                    checked = uiState.overrideTemperatureEnabled,
                    onCheckedChange = { checked ->
                        viewModel.onOverrideTemperatureChange(checked, agent?.temperature)
                    }
                )
                if (uiState.overrideTemperatureEnabled) {
                    val tempValue = uiState.overrideTemperatureValue ?: 0.7f
                    Text(
                        text = stringResource(Res.string.conversation_settings_temperature_value, formatOneDecimal(tempValue)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Slider(
                        value = tempValue,
                        onValueChange = { viewModel.onTemperatureChange(it) },
                        valueRange = 0f..2f,
                        steps = 19,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Reasoning Effort 覆盖
                OverrideToggleRow(
                    label = stringResource(Res.string.conversation_settings_override_reasoning_effort),
                    checked = uiState.overrideReasoningEffortEnabled,
                    onCheckedChange = { checked ->
                        viewModel.onOverrideReasoningEffortChange(checked, agent?.reasoningEffort)
                    }
                )
                if (uiState.overrideReasoningEffortEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ReasoningEffortOverrideDropdown(
                        selectedEffort = uiState.overrideReasoningEffortValue,
                        onEffortChange = { viewModel.onReasoningEffortChange(it) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Max Tokens 覆盖
                OverrideToggleRow(
                    label = stringResource(Res.string.conversation_settings_override_max_tokens),
                    checked = uiState.overrideMaxTokensEnabled,
                    onCheckedChange = { checked ->
                        viewModel.onOverrideMaxTokensChange(checked, agent?.maxTokens)
                    }
                )
                if (uiState.overrideMaxTokensEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = uiState.overrideMaxTokensValue?.toString() ?: "",
                        onValueChange = { value ->
                            viewModel.onMaxTokensChange(value.toIntOrNull())
                        },
                        label = { Text(stringResource(Res.string.agent_edit_max_tokens_label)) },
                        placeholder = { Text(stringResource(Res.string.agent_edit_max_tokens_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OverrideToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private val reasoningEffortOptions = listOf(
    Pair(null, "Default"),
    Pair("none", "None"),
    Pair("minimal", "Minimal"),
    Pair("low", "Low"),
    Pair("medium", "Medium"),
    Pair("high", "High"),
    Pair("xhigh", "xHigh"),
    Pair("max", "Max"),
    Pair("ultra", "Ultra")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningEffortOverrideDropdown(
    selectedEffort: String?,
    onEffortChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = reasoningEffortOptions.find { it.first == selectedEffort }?.second
        ?: stringResource(Res.string.agent_edit_reasoning_effort_default)

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = { },
            readOnly = true,
            label = { Text(stringResource(Res.string.agent_edit_reasoning_effort_label)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            reasoningEffortOptions.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onEffortChange(value)
                        expanded = false
                    }
                )
            }
        }
    }
}
