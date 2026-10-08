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

package cc.ptoe.messenger.presentation.ui.agents

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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.ui.components.ListItem
import cc.ptoe.messenger.presentation.ui.components.SectionHeader
import cc.ptoe.messenger.presentation.ui.components.SingleChoiceDialog
import cc.ptoe.messenger.presentation.ui.components.toolIcon
import cc.ptoe.messenger.presentation.utils.formatOneDecimal
import cc.ptoe.messenger.presentation.ui.components.ConfirmationDialog
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.domain.tool.TerminalTool
import cc.ptoe.messenger.presentation.platform.BackHandler
import cc.ptoe.messenger.presentation.platform.copyAvatarToInternal
import cc.ptoe.messenger.presentation.platform.deleteAvatarFile
import cc.ptoe.messenger.presentation.platform.rememberAvatarImagePicker
import cc.ptoe.messenger.presentation.viewmodel.AgentEditUiState
import cc.ptoe.messenger.presentation.viewmodel.AgentEditViewModel
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_confirm
import cc.ptoe.messenger.generated.resources.action_push
import cc.ptoe.messenger.generated.resources.action_save
import cc.ptoe.messenger.generated.resources.action_unpublish
import cc.ptoe.messenger.generated.resources.action_update
import cc.ptoe.messenger.generated.resources.agent_edit_advanced_settings
import cc.ptoe.messenger.generated.resources.agent_edit_already_up_to_date
import cc.ptoe.messenger.generated.resources.agent_edit_change_avatar
import cc.ptoe.messenger.generated.resources.agent_edit_followed_model_label
import cc.ptoe.messenger.generated.resources.agent_edit_followed_model_value
import cc.ptoe.messenger.generated.resources.agent_edit_get_update
import cc.ptoe.messenger.generated.resources.agent_edit_get_update_desc
import cc.ptoe.messenger.generated.resources.agent_edit_get_update_failed
import cc.ptoe.messenger.generated.resources.agent_edit_get_update_title
import cc.ptoe.messenger.generated.resources.agent_edit_market_section
import cc.ptoe.messenger.generated.resources.agent_edit_max_tokens_label
import cc.ptoe.messenger.generated.resources.agent_edit_max_tokens_placeholder
import cc.ptoe.messenger.generated.resources.agent_edit_name_label
import cc.ptoe.messenger.generated.resources.agent_edit_description_label
import cc.ptoe.messenger.generated.resources.agent_edit_publish_confirm
import cc.ptoe.messenger.generated.resources.agent_edit_publish_desc
import cc.ptoe.messenger.generated.resources.agent_edit_publish_failed
import cc.ptoe.messenger.generated.resources.agent_edit_publish_title
import cc.ptoe.messenger.generated.resources.agent_edit_publish_to_market
import cc.ptoe.messenger.generated.resources.agent_edit_published_success
import cc.ptoe.messenger.generated.resources.agent_edit_push_desc
import cc.ptoe.messenger.generated.resources.agent_edit_push_failed
import cc.ptoe.messenger.generated.resources.agent_edit_push_title
import cc.ptoe.messenger.generated.resources.agent_edit_push_update
import cc.ptoe.messenger.generated.resources.agent_edit_pushed_success
import cc.ptoe.messenger.generated.resources.agent_edit_reasoning_effort_default
import cc.ptoe.messenger.generated.resources.agent_edit_reasoning_effort_label
import cc.ptoe.messenger.generated.resources.agent_edit_role_chat
import cc.ptoe.messenger.generated.resources.agent_edit_role_default
import cc.ptoe.messenger.generated.resources.agent_edit_role_label
import cc.ptoe.messenger.generated.resources.agent_edit_role_locked
import cc.ptoe.messenger.generated.resources.agent_edit_role_select_title
import cc.ptoe.messenger.generated.resources.agent_edit_role_title
import cc.ptoe.messenger.generated.resources.agent_edit_remove_avatar
import cc.ptoe.messenger.generated.resources.agent_edit_select_model
import cc.ptoe.messenger.generated.resources.agent_edit_select_provider
import cc.ptoe.messenger.generated.resources.agent_edit_system_prompt_label
import cc.ptoe.messenger.generated.resources.agent_edit_system_prompt_placeholder
import cc.ptoe.messenger.generated.resources.agent_edit_takeover_active
import cc.ptoe.messenger.generated.resources.agent_edit_takeover_hint
import cc.ptoe.messenger.generated.resources.agent_edit_temperature_label
import cc.ptoe.messenger.generated.resources.agent_edit_title_default
import cc.ptoe.messenger.generated.resources.agent_edit_title_edit
import cc.ptoe.messenger.generated.resources.agent_edit_title_new
import cc.ptoe.messenger.generated.resources.agent_edit_unpublish
import cc.ptoe.messenger.generated.resources.agent_edit_unpublish_desc
import cc.ptoe.messenger.generated.resources.agent_edit_unpublish_failed
import cc.ptoe.messenger.generated.resources.agent_edit_unpublish_title
import cc.ptoe.messenger.generated.resources.agent_edit_unpublished_success
import cc.ptoe.messenger.generated.resources.agent_edit_update_failed
import cc.ptoe.messenger.generated.resources.agent_edit_updated_success
import cc.ptoe.messenger.generated.resources.agent_edit_enable_tools
import cc.ptoe.messenger.generated.resources.agent_edit_tools_empty
import cc.ptoe.messenger.generated.resources.agent_edit_tools_summary_off
import cc.ptoe.messenger.generated.resources.agent_edit_tools_summary_on
import cc.ptoe.messenger.generated.resources.agent_edit_tools_title
import cc.ptoe.messenger.generated.resources.provider_model_picker_label
import cc.ptoe.messenger.generated.resources.tool_terminal_desc
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentEditScreen(
    agentId: String? = null,
    onBackClick: () -> Unit,
    onSaved: () -> Unit,
    onPickProvider: (String?) -> Unit = {},
    pickedModelId: String? = null,
    onPickedModelConsumed: () -> Unit = {},
    viewModel: AgentEditViewModel = viewModel(
        factory = AgentEditViewModel.provideFactory(
            agentRepository = AppContainerHolder.instance.agentRepository,
            modelRepository = AppContainerHolder.instance.modelRepository,
            providerRepository = AppContainerHolder.instance.providerRepository,
            cloudSyncRepository = AppContainerHolder.instance.cloud,
            agentId = agentId
        )
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val models by viewModel.modelsForSelectedProvider.collectAsStateWithLifecycle(initialValue = emptyList())
    val cloudUser by AppContainerHolder.instance.cloud.user.collectAsStateWithLifecycle(initialValue = null)

    // 非默认、非标题生成器才允许接管默认 Agent 的配置
    val showFollowToggles = !uiState.isDefault && uiState.role != Agent.ROLE_TITLE

    // 角色选择器：默认与标题生成器为单持有角色，持有者的选择器锁定，
    // 只能由其他 Agent 认领该角色后自动转移（原持有者回退为普通 Agent）。
    val roleLocked = uiState.isDefault || uiState.role == Agent.ROLE_TITLE
    val persistedRoleOption = when {
        uiState.isDefault -> AgentEditViewModel.ROLE_OPTION_DEFAULT
        uiState.role == Agent.ROLE_TITLE -> AgentEditViewModel.ROLE_OPTION_TITLE
        else -> AgentEditViewModel.ROLE_OPTION_CHAT
    }
    val displayRoleOption = when (uiState.pendingRole) {
        AgentEditViewModel.ROLE_OPTION_DEFAULT -> AgentEditViewModel.ROLE_OPTION_DEFAULT
        AgentEditViewModel.ROLE_OPTION_TITLE -> AgentEditViewModel.ROLE_OPTION_TITLE
        AgentEditViewModel.ROLE_OPTION_CHAT -> AgentEditViewModel.ROLE_OPTION_CHAT
        else -> persistedRoleOption
    }
    val displayRoleLabel = when (displayRoleOption) {
        AgentEditViewModel.ROLE_OPTION_DEFAULT -> stringResource(Res.string.agent_edit_role_default)
        AgentEditViewModel.ROLE_OPTION_TITLE -> stringResource(Res.string.agent_edit_role_title)
        else -> stringResource(Res.string.agent_edit_role_chat)
    }

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showRolePicker by remember { mutableStateOf(false) }
    var showPublishDialog by remember { mutableStateOf(false) }
    var showPushDialog by remember { mutableStateOf(false) }
    var showUnpublishDialog by remember { mutableStateOf(false) }
    var showPullDialog by remember { mutableStateOf(false) }
    var showToolsPage by remember { mutableStateOf(false) }

    // 平台注册的可用工具（内置 + MCP）；每工具开关在工具配置子页中维护
    val tools = remember { AppContainerHolder.instance.availableTools }

    val strPublishedSuccess = stringResource(Res.string.agent_edit_published_success)
    val strPublishFailed = stringResource(Res.string.agent_edit_publish_failed)
    val strPushedSuccess = stringResource(Res.string.agent_edit_pushed_success)
    val strPushFailed = stringResource(Res.string.agent_edit_push_failed)
    val strUnpublishedSuccess = stringResource(Res.string.agent_edit_unpublished_success)
    val strUnpublishFailed = stringResource(Res.string.agent_edit_unpublish_failed)
    val strUpdatedSuccess = stringResource(Res.string.agent_edit_updated_success)
    val strUpdateFailed = stringResource(Res.string.agent_edit_update_failed)
    val strAlreadyUpToDate = stringResource(Res.string.agent_edit_already_up_to_date)
    val strGetUpdateFailed = stringResource(Res.string.agent_edit_get_update_failed)

    // 选图后裁剪（Android uCrop / Desktop 直接拷贝），再复制到内部存储持久化
    val avatarPicker = rememberAvatarImagePicker { croppedPath ->
        val previousAvatar = uiState.avatar
        coroutineScope.launch {
            val path = copyAvatarToInternal(croppedPath, "agent_avatars")
            if (path != null) {
                previousAvatar?.let { deleteAvatarFile(it) }
                viewModel.onAvatarChange(path)
            }
        }
    }

    // 双栏布局下切换 Agent 时按 id 重新加载，避免依赖 ViewModel 重建
    //（viewModel() 按 ViewModelStoreOwner 缓存，position 不变时不会重建）
    LaunchedEffect(agentId) {
        viewModel.loadAgent(agentId)
    }

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
                    title = {
                        Text(
                            text = when {
                                uiState.isDefault -> stringResource(Res.string.agent_edit_title_default)
                                uiState.isEditing -> stringResource(Res.string.agent_edit_title_edit)
                                else -> stringResource(Res.string.agent_edit_title_new)
                            }
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
                        TextButton(onClick = {
                            if (viewModel.save()) {
                            }
                        }) {
                            Text(stringResource(Res.string.action_save))
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    // 头像
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clickable {
                                    avatarPicker.launch()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AgentAvatar(
                                avatar = uiState.avatar,
                                size = 96.dp
                            )
                            // 右下角相机徽标，提示可点击更换
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(28.dp)
                                    .zIndex(1f)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddAPhoto,
                                    contentDescription = stringResource(Res.string.agent_edit_change_avatar),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }

                    if (uiState.avatar != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                uiState.avatar?.let { deleteAvatarFile(it) }
                                viewModel.onAvatarChange(null)
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(stringResource(Res.string.agent_edit_remove_avatar))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = { viewModel.onNameChange(it) },
                        label = { Text(stringResource(Res.string.agent_edit_name_label)) },
                        isError = uiState.nameError != null,
                        supportingText = {
                            uiState.nameError?.let { error ->
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        singleLine = true,
                        enabled = !uiState.isDefault, // 默认 Agent 名称不允许修改（保持"默认 Agent"标识）
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = uiState.description,
                        onValueChange = { viewModel.onDescriptionChange(it) },
                        label = { Text(stringResource(Res.string.agent_edit_description_label)) },
                        minLines = 1,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 角色（普通 / 默认 / 标题生成器）
                    ListItem(
                        horizontalMargin = 0.dp,
                        title = stringResource(Res.string.agent_edit_role_label),
                        subtitle = if (roleLocked) {
                            stringResource(Res.string.agent_edit_role_locked)
                        } else {
                            displayRoleLabel
                        },
                        icon = Icons.Default.Person,
                        titleColor = if (roleLocked) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        onClick = if (roleLocked) null else {
                            { showRolePicker = true }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val systemPromptValue = if (showFollowToggles && uiState.followDefaultSystemPrompt) {
                        uiState.defaultAgent?.systemPrompt ?: ""
                    } else {
                        uiState.systemPrompt
                    }
                    FollowableTarget(
                        enabled = showFollowToggles,
                        followed = uiState.followDefaultSystemPrompt,
                        onFollowChange = viewModel::onFollowSystemPromptChange
                    ) {
                        OutlinedTextField(
                            value = systemPromptValue,
                            onValueChange = { viewModel.onSystemPromptChange(it) },
                            label = { Text(stringResource(Res.string.agent_edit_system_prompt_label)) },
                            placeholder = { Text(stringResource(Res.string.agent_edit_system_prompt_placeholder)) },
                            minLines = 3,
                            maxLines = 8,
                            enabled = !showFollowToggles || !uiState.followDefaultSystemPrompt,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val modelSectionEnabled = !showFollowToggles || !uiState.followDefaultModel
                    FollowableTarget(
                        enabled = showFollowToggles,
                        followed = uiState.followDefaultModel,
                        onFollowChange = viewModel::onFollowModelChange
                    ) {
                        if (modelSectionEnabled) {
                            // Provider + 模型合并为一个设置条目，subtitle 展示「Provider 名 · 模型 ID」
                            val selectedProvider = providers.find { it.id == uiState.selectedProviderId }
                            val selectedModel = models.find { it.id == uiState.defaultModelId }
                            val subtitle = when {
                                selectedModel != null && selectedProvider != null ->
                                    "${selectedProvider.name} · ${selectedModel.modelId}"
                                selectedProvider != null -> selectedProvider.name
                                else -> stringResource(Res.string.agent_edit_select_provider)
                            }
                            ListItem(
                                horizontalMargin = 0.dp,
                                title = stringResource(Res.string.provider_model_picker_label),
                                subtitle = subtitle,
                                icon = Icons.Default.SmartToy,
                                onClick = { onPickProvider(uiState.selectedProviderId) }
                            )
                        } else {
                            // 跟随默认 Agent：只读展示默认 Agent 的模型信息
                            FollowedValueBox(
                                label = stringResource(Res.string.agent_edit_followed_model_label),
                                value = stringResource(Res.string.agent_edit_followed_model_value)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    SectionHeader(title = stringResource(Res.string.agent_edit_advanced_settings))

                    Spacer(modifier = Modifier.height(8.dp))

                    val tempEnabled = !showFollowToggles || !uiState.followDefaultTemperature
                    val tempValue = if (showFollowToggles && uiState.followDefaultTemperature) {
                        uiState.defaultAgent?.temperature ?: uiState.temperature
                    } else {
                        uiState.temperature
                    }
                    FollowableTarget(
                        enabled = showFollowToggles,
                        followed = uiState.followDefaultTemperature,
                        onFollowChange = viewModel::onFollowTemperatureChange
                    ) {
                        Column {
                            Text(
                                text = stringResource(Res.string.agent_edit_temperature_label, formatOneDecimal(tempValue)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (tempEnabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = tempValue,
                                onValueChange = { viewModel.onTemperatureChange(it) },
                                enabled = tempEnabled,
                                valueRange = 0f..2f,
                                steps = 19
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val maxTokensEnabled = !showFollowToggles || !uiState.followDefaultMaxTokens
                    val maxTokensValue = if (showFollowToggles && uiState.followDefaultMaxTokens) {
                        uiState.defaultAgent?.maxTokens?.toString() ?: ""
                    } else {
                        uiState.maxTokens ?: ""
                    }
                    FollowableTarget(
                        enabled = showFollowToggles,
                        followed = uiState.followDefaultMaxTokens,
                        onFollowChange = viewModel::onFollowMaxTokensChange
                    ) {
                        OutlinedTextField(
                            value = maxTokensValue,
                            onValueChange = { value ->
                                viewModel.onMaxTokensChange(value.ifBlank { null })
                            },
                            label = { Text(stringResource(Res.string.agent_edit_max_tokens_label)) },
                            placeholder = { Text(stringResource(Res.string.agent_edit_max_tokens_placeholder)) },
                            singleLine = true,
                            enabled = maxTokensEnabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val reasoningEnabled = !showFollowToggles || !uiState.followDefaultReasoningEffort
                    val reasoningValue = if (showFollowToggles && uiState.followDefaultReasoningEffort) {
                        uiState.defaultAgent?.reasoningEffort
                    } else {
                        uiState.reasoningEffort
                    }
                    FollowableTarget(
                        enabled = showFollowToggles,
                        followed = uiState.followDefaultReasoningEffort,
                        onFollowChange = viewModel::onFollowReasoningEffortChange
                    ) {
                        ReasoningEffortDropdown(
                            selectedEffort = reasoningValue,
                            enabled = reasoningEnabled,
                            onEffortChange = { viewModel.onReasoningEffortChange(it) }
                        )
                    }

                    // 工具入口：进入页内工具配置子页（总开关 + 每工具开关；
                    // 标题生成智能体是后台功能角色，不暴露）。
                    // 与角色 / 模型选择条目同款 ListItem 卡片样式。
                    if (uiState.role != Agent.ROLE_TITLE) {
                        Spacer(modifier = Modifier.height(16.dp))
                        val toolsMasterOn = if (!uiState.isDefault && uiState.toolsFollowDefault) {
                            uiState.defaultAgent?.toolsEnabled ?: uiState.toolsEnabled
                        } else {
                            uiState.toolsEnabled
                        }
                        val enabledCount = tools.count { tool ->
                            val config = if (!uiState.isDefault && uiState.toolsFollowDefault) {
                                uiState.defaultAgent?.toolsConfig
                            } else {
                                uiState.toolsConfig
                            }
                            config?.get(tool.name) ?: true
                        }
                        ListItem(
                            horizontalMargin = 0.dp,
                            title = stringResource(Res.string.agent_edit_tools_title),
                            subtitle = if (toolsMasterOn) {
                                stringResource(Res.string.agent_edit_tools_summary_on, enabledCount, tools.size)
                            } else {
                                stringResource(Res.string.agent_edit_tools_summary_off)
                            },
                            icon = Icons.Default.Build,
                            onClick = { showToolsPage = true }
                        )
                    }

                    if (cloudUser != null && uiState.isEditing && !uiState.isDefault) {
                        Spacer(modifier = Modifier.height(24.dp))
                        SectionHeader(title = stringResource(Res.string.agent_edit_market_section))
                        Spacer(modifier = Modifier.height(8.dp))
                        when (uiState.marketAgentRole) {
                            "publisher" -> {
                                Button(
                                    onClick = { showPushDialog = true },
                                    enabled = !uiState.marketActionInProgress,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.FileUpload, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(Res.string.agent_edit_push_update))
                                }
                                TextButton(
                                    onClick = { showUnpublishDialog = true },
                                    enabled = !uiState.marketActionInProgress,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(Res.string.agent_edit_unpublish))
                                }
                            }
                            "importer" -> {
                                Button(
                                    onClick = {
                                        viewModel.checkMarketAgentUpdate { result ->
                                            coroutineScope.launch {
                                                result.onSuccess { update ->
                                                    if (update.hasUpdate) showPullDialog = true
                                                    else snackbarHostState.showSnackbar(strAlreadyUpToDate)
                                                }.onFailure { error ->
                                                    snackbarHostState.showSnackbar(error.message ?: strGetUpdateFailed)
                                                }
                                            }
                                        }
                                    },
                                    enabled = !uiState.marketActionInProgress,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.SystemUpdateAlt, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(Res.string.agent_edit_get_update))
                                }
                            }
                            else -> Button(
                                onClick = { showPublishDialog = true },
                                enabled = !uiState.marketActionInProgress,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(Res.string.agent_edit_publish_to_market))
                            }
                        }
                    }
                }
            }
        }

        // 工具配置子页：页内状态切换的覆盖层 + 预测性返回（划出时露出底层编辑主页面，
        // 与 Cloud 设置页的实例地址子页同模式）。退场为「滑向右 + 渐淡」（与导航 pop
        // 一致），覆盖程序化关闭；预测性返回手势提交时内容已推到进度 1（不可见），
        // 不会与该退场过渡叠加闪烁。
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
                AgentToolsPage(
                    state = uiState,
                    tools = tools,
                    onBack = { showToolsPage = false },
                    onToolsEnabledChange = viewModel::onToolsEnabledChange,
                    onToolsFollowDefaultChange = viewModel::onToolsFollowDefaultChange,
                    onToolEnabledChange = viewModel::onToolEnabledChange
                )
            }
        }
    }

    if (showRolePicker) {
        val roleOptions = listOf(
            AgentEditViewModel.ROLE_OPTION_CHAT to stringResource(Res.string.agent_edit_role_chat),
            AgentEditViewModel.ROLE_OPTION_DEFAULT to stringResource(Res.string.agent_edit_role_default),
            AgentEditViewModel.ROLE_OPTION_TITLE to stringResource(Res.string.agent_edit_role_title)
        )
        SingleChoiceDialog(
            title = stringResource(Res.string.agent_edit_role_select_title),
            items = roleOptions,
            initialSelectedId = displayRoleOption,
            itemId = { it.first },
            itemLabel = { it.second },
            confirmButtonText = stringResource(Res.string.action_confirm),
            dismissButtonText = stringResource(Res.string.action_cancel),
            onConfirm = { selected ->
                showRolePicker = false
                selected?.let { viewModel.onRoleSelected(it.first) }
            },
            onDismiss = { showRolePicker = false }
        )
    }

    if (showPublishDialog) {
        ConfirmationDialog(
            title = stringResource(Res.string.agent_edit_publish_title),
            text = stringResource(Res.string.agent_edit_publish_desc),
            confirmButtonText = stringResource(Res.string.agent_edit_publish_confirm),
            dismissButtonText = stringResource(Res.string.action_cancel),
            onConfirm = {
                showPublishDialog = false
                viewModel.publishMarketAgent { result ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(result.fold({ strPublishedSuccess }, { it.message ?: strPublishFailed }))
                    }
                }
            },
            onDismiss = { showPublishDialog = false }
        )
    }
    if (showPushDialog) {
        ConfirmationDialog(
            title = stringResource(Res.string.agent_edit_push_title),
            text = stringResource(Res.string.agent_edit_push_desc),
            confirmButtonText = stringResource(Res.string.action_push),
            dismissButtonText = stringResource(Res.string.action_cancel),
            onConfirm = {
                showPushDialog = false
                viewModel.pushMarketAgentUpdate { result ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(result.fold({ strPushedSuccess }, { it.message ?: strPushFailed }))
                    }
                }
            },
            onDismiss = { showPushDialog = false }
        )
    }
    if (showUnpublishDialog) {
        ConfirmationDialog(
            title = stringResource(Res.string.agent_edit_unpublish_title),
            text = stringResource(Res.string.agent_edit_unpublish_desc),
            confirmButtonText = stringResource(Res.string.action_unpublish),
            dismissButtonText = stringResource(Res.string.action_cancel),
            onConfirm = {
                showUnpublishDialog = false
                viewModel.removeMarketAgent { result ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(result.fold({ strUnpublishedSuccess }, { it.message ?: strUnpublishFailed }))
                    }
                }
            },
            onDismiss = { showUnpublishDialog = false }
        )
    }
    if (showPullDialog) {
        ConfirmationDialog(
            title = stringResource(Res.string.agent_edit_get_update_title),
            text = stringResource(Res.string.agent_edit_get_update_desc),
            confirmButtonText = stringResource(Res.string.action_update),
            dismissButtonText = stringResource(Res.string.action_cancel),
            onConfirm = {
                showPullDialog = false
                viewModel.applyMarketAgentUpdate { result ->
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(result.fold({ strUpdatedSuccess }, { it.message ?: strUpdateFailed }))
                    }
                }
            },
            onDismiss = { showPullDialog = false }
        )
    }
}

/**
 * 工具配置子页的开关卡片行，与工具设置页（ToolsSettingsScreen）的 MCP 服务器
 * 卡片同款：surfaceContainer 卡片 + 标题/说明 + 尾部 Switch。
 */
@Composable
private fun ToolToggleCard(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
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
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
            }
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
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

/**
 * A configurable target whose value can be taken over from the default Agent.
 * Swipe right to take over the default value and swipe left to restore the
 * editable value. When taken over, the target is deliberately masked so the
 * ownership of the value is clear and the child cannot be edited accidentally.
 */
@Composable
private fun FollowableTarget(
    enabled: Boolean,
    followed: Boolean,
    onFollowChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val takeoverActive = enabled && followed
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
                        if (enabled) {
                            when {
                                totalDrag >= 48f -> onFollowChange(true)
                                totalDrag <= -48f -> onFollowChange(false)
                            }
                        }
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f }
                )
            }
    ) {
        content()
        if (takeoverActive) {
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
                            text = stringResource(Res.string.agent_edit_takeover_active),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(Res.string.agent_edit_takeover_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowedValueBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = { },
        readOnly = true,
        enabled = false,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth()
    )
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
private fun ReasoningEffortDropdown(
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
        onExpandedChange = { newExpanded ->
            if (enabled) expanded = newExpanded
        },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = { },
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(Res.string.agent_edit_reasoning_effort_label)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
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
 * Agent 工具配置子页：总开关 + 「跟随默认 Agent」+ 每工具开关列表。
 * 「默认全开」以缺失键表达：[AgentEditUiState.toolsConfig] 只记录显式关闭的工具，
 * 之后新增的工具自动可用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentToolsPage(
    state: AgentEditUiState,
    tools: List<ChatTool>,
    onBack: () -> Unit,
    onToolsEnabledChange: (Boolean) -> Unit,
    onToolsFollowDefaultChange: (Boolean) -> Unit,
    onToolEnabledChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // 开启跟随时展示默认 Agent 的配置且不可编辑
    val following = !state.isDefault && state.toolsFollowDefault
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
            // 跟随默认 Agent 采用与模型/参数一致的左右滑动接管交互：
            // 右滑接管默认 Agent 的工具配置（蒙层只读），左滑恢复自定义。
            // 默认 Agent 自身与标题生成角色没有可跟随的对象，手势关闭。
            val toolsFollowable = !state.isDefault && state.role != Agent.ROLE_TITLE
            val toolsMasterChecked = if (following) {
                state.defaultAgent?.toolsEnabled ?: state.toolsEnabled
            } else {
                state.toolsEnabled
            }
            FollowableTarget(
                enabled = toolsFollowable,
                followed = following,
                onFollowChange = onToolsFollowDefaultChange
            ) {
                // FollowableTarget 的内容容器是 Box（子元素层叠），卡片列表
                // 必须显式纵排。
                Column {
                    ToolToggleCard(
                        title = stringResource(Res.string.agent_edit_enable_tools),
                        subtitle = null,
                        checked = toolsMasterChecked,
                        // 总开关自身不能以 toolsEnabled 为启用条件，否则关闭状态下
                        // 永远点不开（自锁死循环）。仅在跟随默认 Agent 的接管蒙层
                        // 下只读。
                        enabled = !following,
                        onCheckedChange = onToolsEnabledChange
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
                            val checked = if (following) {
                                state.defaultAgent?.toolsConfig?.get(tool.name) ?: true
                            } else {
                                state.toolsConfig[tool.name] ?: true
                            }
                            ToolToggleCard(
                                title = tool.name,
                                // 内置终端工具展示本地化说明；其余工具直接展示其模型侧描述
                                subtitle = if (tool.name == TerminalTool.TOOL_NAME) {
                                    stringResource(Res.string.tool_terminal_desc)
                                } else {
                                    tool.description
                                },
                                checked = checked,
                                enabled = state.toolsEnabled && !following,
                                icon = toolIcon(tool.name),
                                onCheckedChange = { onToolEnabledChange(tool.name, it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SUBPAGE_TRANSITION_MS = 350
