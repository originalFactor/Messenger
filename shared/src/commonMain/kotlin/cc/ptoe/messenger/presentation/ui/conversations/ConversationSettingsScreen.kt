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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.presentation.ui.components.ListItem
import cc.ptoe.messenger.presentation.ui.components.SectionHeader
import cc.ptoe.messenger.presentation.platform.BackHandler
import cc.ptoe.messenger.presentation.utils.formatOneDecimal
import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.domain.tool.TerminalTool
import cc.ptoe.messenger.presentation.viewmodel.ConversationSettingsViewModel
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_save
import cc.ptoe.messenger.generated.resources.agent_edit_enable_tools
import cc.ptoe.messenger.generated.resources.agent_edit_max_tokens_label
import cc.ptoe.messenger.generated.resources.agent_edit_max_tokens_placeholder
import cc.ptoe.messenger.generated.resources.agent_edit_reasoning_effort_default
import cc.ptoe.messenger.generated.resources.agent_edit_reasoning_effort_label
import cc.ptoe.messenger.generated.resources.agent_edit_tools_empty
import cc.ptoe.messenger.generated.resources.agent_edit_tools_summary_off
import cc.ptoe.messenger.generated.resources.agent_edit_tools_summary_on
import cc.ptoe.messenger.generated.resources.agent_edit_tools_title
import cc.ptoe.messenger.generated.resources.conversation_settings_agent_label
import cc.ptoe.messenger.generated.resources.conversation_settings_override_desc
import cc.ptoe.messenger.generated.resources.conversation_settings_override_section
import cc.ptoe.messenger.generated.resources.conversation_settings_override_active
import cc.ptoe.messenger.generated.resources.conversation_settings_override_hint
import cc.ptoe.messenger.generated.resources.conversation_settings_select_model
import cc.ptoe.messenger.generated.resources.conversation_settings_select_provider
import cc.ptoe.messenger.generated.resources.conversation_settings_temperature_value
import cc.ptoe.messenger.generated.resources.conversation_settings_title
import cc.ptoe.messenger.generated.resources.conversation_settings_title_label
import cc.ptoe.messenger.generated.resources.provider_model_picker_label
import cc.ptoe.messenger.generated.resources.tool_terminal_desc
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

    var showToolsPage by remember { mutableStateOf(false) }

    // 平台注册的可用工具（内置 + MCP）；会话级每工具开关在工具配置子页中维护
    val tools = remember { AppContainerHolder.instance.availableTools }

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

    Box(modifier = Modifier.fillMaxSize()) {
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
                    val selectedProvider = providers.find { it.id == uiState.selectedProviderId }
                    val selectedModel = models.find { it.id == uiState.overrideModelId }
                    val modelSubtitle = when {
                        selectedModel != null && selectedProvider != null ->
                            "${selectedProvider.name} · ${selectedModel.modelId}"
                        selectedProvider != null -> selectedProvider.name
                        else -> stringResource(Res.string.conversation_settings_select_provider)
                    }
                    SwipeOverrideTarget(
                        enabled = uiState.overrideModelEnabled,
                        onEnabledChange = viewModel::onOverrideModelChange
                    ) {
                        ListItem(
                            title = stringResource(Res.string.provider_model_picker_label),
                            subtitle = modelSubtitle,
                            icon = Icons.Default.SmartToy,
                            onClick = if (uiState.overrideModelEnabled) {
                                { onPickProvider(uiState.selectedProviderId) }
                            } else null
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Temperature 覆盖
                    val tempValue = if (uiState.overrideTemperatureEnabled) {
                        uiState.overrideTemperatureValue ?: agent?.temperature ?: 0.7f
                    } else {
                        agent?.temperature ?: 0.7f
                    }
                    SwipeOverrideTarget(
                        enabled = uiState.overrideTemperatureEnabled,
                        onEnabledChange = { enabled ->
                            viewModel.onOverrideTemperatureChange(enabled, agent?.temperature)
                        }
                    ) {
                        Column {
                            Text(
                                text = stringResource(Res.string.conversation_settings_temperature_value, formatOneDecimal(tempValue)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Slider(
                                value = tempValue,
                                onValueChange = { viewModel.onTemperatureChange(it) },
                                enabled = uiState.overrideTemperatureEnabled,
                                valueRange = 0f..2f,
                                steps = 19,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Reasoning Effort 覆盖
                    val reasoningValue = if (uiState.overrideReasoningEffortEnabled) {
                        uiState.overrideReasoningEffortValue
                    } else {
                        agent?.reasoningEffort
                    }
                    SwipeOverrideTarget(
                        enabled = uiState.overrideReasoningEffortEnabled,
                        onEnabledChange = { enabled ->
                            viewModel.onOverrideReasoningEffortChange(enabled, agent?.reasoningEffort)
                        }
                    ) {
                        ReasoningEffortOverrideDropdown(
                            selectedEffort = reasoningValue,
                            enabled = uiState.overrideReasoningEffortEnabled,
                            onEffortChange = { viewModel.onReasoningEffortChange(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Max Tokens 覆盖
                    SwipeOverrideTarget(
                        enabled = uiState.overrideMaxTokensEnabled,
                        onEnabledChange = { enabled ->
                            viewModel.onOverrideMaxTokensChange(enabled, agent?.maxTokens)
                        }
                    ) {
                        OutlinedTextField(
                            value = (if (uiState.overrideMaxTokensEnabled) {
                                uiState.overrideMaxTokensValue
                            } else {
                                agent?.maxTokens
                            })?.toString() ?: "",
                            onValueChange = { value ->
                                viewModel.onMaxTokensChange(value.toIntOrNull())
                            },
                            label = { Text(stringResource(Res.string.agent_edit_max_tokens_label)) },
                            placeholder = { Text(stringResource(Res.string.agent_edit_max_tokens_placeholder)) },
                            singleLine = true,
                            enabled = uiState.overrideMaxTokensEnabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 工具入口：进入页内工具配置子页（总开关 + 每工具开关的会话级覆盖；
                    // 未覆盖时沿用 Agent 的生效配置）。与 Agent 编辑页的工具入口同款。
                    ListItem(
                        horizontalMargin = 0.dp,
                        title = stringResource(Res.string.agent_edit_tools_title),
                        subtitle = if (uiState.overrideToolsEnabled) {
                            if (uiState.overrideToolsValue) {
                                stringResource(
                                    Res.string.agent_edit_tools_summary_on,
                                    tools.count { uiState.overrideToolsConfig[it.name] ?: true },
                                    tools.size
                                )
                            } else {
                                stringResource(Res.string.agent_edit_tools_summary_off)
                            }
                        } else {
                            stringResource(Res.string.conversation_settings_override_active)
                        },
                        icon = Icons.Default.Build,
                        onClick = { showToolsPage = true }
                    )
                }
            }
        }
        // 工具配置子页：页内状态切换的覆盖层 + 预测性返回（划出时露出底层设置页，
        // 与 Agent 编辑页的工具配置子页同模式）。退场为「滑向右 + 渐淡」。
        AnimatedVisibility(
            visible = showToolsPage,
            enter = slideInHorizontally(
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = IntOffset.VisibilityThreshold
                )
            ) { it } + fadeIn(tween(SUBPAGE_TRANSITION_MS)),
            exit = slideOutHorizontally(tween(SUBPAGE_TRANSITION_MS)) { it } +
                fadeOut(tween(SUBPAGE_TRANSITION_MS))
        ) {
            BackHandler(enabled = true, onBack = { showToolsPage = false }) {
                ConversationToolsPage(
                    overriding = uiState.overrideToolsEnabled,
                    masterValue = uiState.overrideToolsValue,
                    config = uiState.overrideToolsConfig,
                    agentMasterValue = agent?.toolsEnabled ?: false,
                    agentConfig = agent?.toolsConfig,
                    tools = tools,
                    onBack = { showToolsPage = false },
                    onOverrideChange = {
                        viewModel.onOverrideToolsChange(
                            it,
                            agent?.toolsEnabled ?: false,
                            agent?.toolsConfig ?: emptyMap()
                        )
                    },
                    onMasterChange = viewModel::onToolsValueChange,
                    onToolEnabledChange = viewModel::onToolEnabledChange
                )
            }
        }
    }
}

@Composable
private fun SwipeOverrideTarget(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(enabled) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, dragAmount ->
                        totalDrag += dragAmount
                        change.consume()
                    },
                    onDragEnd = {
                        when {
                            totalDrag >= 48f -> onEnabledChange(true)
                            totalDrag <= -48f -> onEnabledChange(false)
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f }
                )
            }
    ) {
        content()
        if (!enabled) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(Res.string.conversation_settings_override_active),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.conversation_settings_override_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
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
    enabled: Boolean,
    onEffortChange: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = reasoningEffortOptions.find { it.first == selectedEffort }?.second
        ?: stringResource(Res.string.agent_edit_reasoning_effort_default)

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = { },
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(Res.string.agent_edit_reasoning_effort_label)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
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

/**
 * 会话级工具配置子页：总开关 + 每工具开关列表，整体包在左右滑动覆盖手势里——
 * 右滑开启覆盖后，这里的配置即为本会话的生效值；左滑（或蒙层状态下右滑前）
 * 恢复跟随 Agent。「默认全开」以缺失键表达：[ConversationSettingsUiState.overrideToolsConfig]
 * 只记录显式关闭的工具，之后新增的工具自动可用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationToolsPage(
    overriding: Boolean,
    masterValue: Boolean,
    config: Map<String, Boolean>,
    agentMasterValue: Boolean,
    agentConfig: Map<String, Boolean>?,
    tools: List<ChatTool>,
    onBack: () -> Unit,
    onOverrideChange: (Boolean) -> Unit,
    onMasterChange: (Boolean) -> Unit,
    onToolEnabledChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.agent_edit_tools_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SwipeOverrideTarget(
                enabled = overriding,
                onEnabledChange = onOverrideChange
            ) {
                ToolToggleCard(
                    title = stringResource(Res.string.agent_edit_enable_tools),
                    subtitle = null,
                    checked = if (overriding) masterValue else agentMasterValue,
                    enabled = overriding,
                    onCheckedChange = onMasterChange
                )

                if (tools.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(Res.string.agent_edit_tools_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    tools.forEach { tool ->
                        ToolToggleCard(
                            title = tool.name,
                            // 内置终端工具展示本地化说明；其余工具直接展示其模型侧描述
                            subtitle = if (tool.name == TerminalTool.TOOL_NAME) {
                                stringResource(Res.string.tool_terminal_desc)
                            } else {
                                tool.description
                            },
                            checked = if (overriding) {
                                config[tool.name] ?: true
                            } else {
                                agentConfig?.get(tool.name) ?: true
                            },
                            enabled = overriding,
                            onCheckedChange = { onToolEnabledChange(tool.name, it) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工具配置子页的开关卡片行，与 Agent 编辑页同款：surfaceContainer 卡片 +
 * 标题/说明 + 尾部 Switch。
 */
@Composable
private fun ToolToggleCard(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}

private const val SUBPAGE_TRANSITION_MS = 350
