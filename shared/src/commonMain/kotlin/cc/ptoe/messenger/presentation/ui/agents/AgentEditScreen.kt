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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.ui.components.ListItem
import cc.ptoe.messenger.presentation.ui.components.SectionHeader
import cc.ptoe.messenger.presentation.utils.formatOneDecimal
import cc.ptoe.messenger.presentation.ui.components.ConfirmationDialog
import cc.ptoe.messenger.presentation.platform.copyAvatarToInternal
import cc.ptoe.messenger.presentation.platform.deleteAvatarFile
import cc.ptoe.messenger.presentation.platform.rememberAvatarImagePicker
import cc.ptoe.messenger.presentation.viewmodel.AgentEditViewModel
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_push
import cc.ptoe.messenger.generated.resources.action_save
import cc.ptoe.messenger.generated.resources.action_unpublish
import cc.ptoe.messenger.generated.resources.action_update
import cc.ptoe.messenger.generated.resources.agent_edit_advanced_settings
import cc.ptoe.messenger.generated.resources.agent_edit_already_up_to_date
import cc.ptoe.messenger.generated.resources.agent_edit_change_avatar
import cc.ptoe.messenger.generated.resources.agent_edit_follow_max_tokens
import cc.ptoe.messenger.generated.resources.agent_edit_follow_model
import cc.ptoe.messenger.generated.resources.agent_edit_follow_reasoning_effort
import cc.ptoe.messenger.generated.resources.agent_edit_follow_system_prompt
import cc.ptoe.messenger.generated.resources.agent_edit_follow_temperature
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
import cc.ptoe.messenger.generated.resources.agent_edit_remove_avatar
import cc.ptoe.messenger.generated.resources.agent_edit_select_model
import cc.ptoe.messenger.generated.resources.agent_edit_select_provider
import cc.ptoe.messenger.generated.resources.agent_edit_system_prompt_label
import cc.ptoe.messenger.generated.resources.agent_edit_system_prompt_placeholder
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
import cc.ptoe.messenger.generated.resources.provider_model_picker_label
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
            cloudSyncRepository = AppContainerHolder.instance.cloudSyncRepository,
            agentId = agentId
        )
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val models by viewModel.modelsForSelectedProvider.collectAsStateWithLifecycle(initialValue = emptyList())
    val cloudUser by AppContainerHolder.instance.cloudSyncRepository.user.collectAsStateWithLifecycle(initialValue = null)

    // 非默认 Agent 才显示"跟随默认 Agent"开关
    val showFollowToggles = !uiState.isDefault

    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPublishDialog by remember { mutableStateOf(false) }
    var showPushDialog by remember { mutableStateOf(false) }
    var showUnpublishDialog by remember { mutableStateOf(false) }
    var showPullDialog by remember { mutableStateOf(false) }

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

                // 系统提示词
                if (showFollowToggles) {
                    FollowToggleRow(
                        label = stringResource(Res.string.agent_edit_follow_system_prompt),
                        checked = uiState.followDefaultSystemPrompt,
                        onCheckedChange = { viewModel.onFollowSystemPromptChange(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val systemPromptValue = if (showFollowToggles && uiState.followDefaultSystemPrompt) {
                    uiState.defaultAgent?.systemPrompt ?: ""
                } else {
                    uiState.systemPrompt
                }
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

                Spacer(modifier = Modifier.height(16.dp))

                // Provider + Model
                if (showFollowToggles) {
                    FollowToggleRow(
                        label = stringResource(Res.string.agent_edit_follow_model),
                        checked = uiState.followDefaultModel,
                        onCheckedChange = { viewModel.onFollowModelChange(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val modelSectionEnabled = !showFollowToggles || !uiState.followDefaultModel
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

                Spacer(modifier = Modifier.height(24.dp))

                SectionHeader(title = stringResource(Res.string.agent_edit_advanced_settings))

                Spacer(modifier = Modifier.height(8.dp))

                // Temperature
                if (showFollowToggles) {
                    FollowToggleRow(
                        label = stringResource(Res.string.agent_edit_follow_temperature),
                        checked = uiState.followDefaultTemperature,
                        onCheckedChange = { viewModel.onFollowTemperatureChange(it) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                val tempEnabled = !showFollowToggles || !uiState.followDefaultTemperature
                val tempValue = if (showFollowToggles && uiState.followDefaultTemperature) {
                    uiState.defaultAgent?.temperature ?: uiState.temperature
                } else {
                    uiState.temperature
                }
                Text(
                    text = stringResource(Res.string.agent_edit_temperature_label, formatOneDecimal(tempValue)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tempEnabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Slider(
                    value = tempValue,
                    onValueChange = { viewModel.onTemperatureChange(it) },
                    enabled = tempEnabled,
                    valueRange = 0f..2f,
                    steps = 19,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Max Tokens
                if (showFollowToggles) {
                    FollowToggleRow(
                        label = stringResource(Res.string.agent_edit_follow_max_tokens),
                        checked = uiState.followDefaultMaxTokens,
                        onCheckedChange = { viewModel.onFollowMaxTokensChange(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val maxTokensEnabled = !showFollowToggles || !uiState.followDefaultMaxTokens
                val maxTokensValue = if (showFollowToggles && uiState.followDefaultMaxTokens) {
                    uiState.defaultAgent?.maxTokens?.toString() ?: ""
                } else {
                    uiState.maxTokens ?: ""
                }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Reasoning Effort
                if (showFollowToggles) {
                    FollowToggleRow(
                        label = stringResource(Res.string.agent_edit_follow_reasoning_effort),
                        checked = uiState.followDefaultReasoningEffort,
                        onCheckedChange = { viewModel.onFollowReasoningEffortChange(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                val reasoningEnabled = !showFollowToggles || !uiState.followDefaultReasoningEffort
                val reasoningValue = if (showFollowToggles && uiState.followDefaultReasoningEffort) {
                    uiState.defaultAgent?.reasoningEffort
                } else {
                    uiState.reasoningEffort
                }
                ReasoningEffortDropdown(
                    selectedEffort = reasoningValue,
                    enabled = reasoningEnabled,
                    onEffortChange = { viewModel.onReasoningEffortChange(it) }
                )

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

@Composable
private fun FollowToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
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

