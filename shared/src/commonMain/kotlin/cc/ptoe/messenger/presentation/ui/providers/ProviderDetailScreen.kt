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

package cc.ptoe.messenger.presentation.ui.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.presentation.ui.components.ConfirmationDialog
import cc.ptoe.messenger.presentation.ui.components.CursorDropdownMenu
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.ui.components.LoadingIndicator
import cc.ptoe.messenger.presentation.ui.components.MultiSelectTopBar
import cc.ptoe.messenger.presentation.ui.components.onContextMenu
import cc.ptoe.messenger.presentation.ui.components.rememberContextMenuState
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.formatContextWindow
import cc.ptoe.messenger.presentation.utils.formatRate
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor
import cc.ptoe.messenger.presentation.viewmodel.ProviderDetailViewModel
import cc.ptoe.messenger.presentation.viewmodel.SyncStatus
import kotlinx.coroutines.launch
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_add
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_delete
import cc.ptoe.messenger.generated.resources.action_disable
import cc.ptoe.messenger.generated.resources.action_enable
import cc.ptoe.messenger.generated.resources.action_manual_add
import cc.ptoe.messenger.generated.resources.action_sync_models
import cc.ptoe.messenger.generated.resources.error_load_failed
import cc.ptoe.messenger.generated.resources.providers_delete_model_confirm
import cc.ptoe.messenger.generated.resources.providers_delete_model_title
import cc.ptoe.messenger.generated.resources.providers_delete_models_confirm
import cc.ptoe.messenger.generated.resources.providers_delete_models_title
import cc.ptoe.messenger.generated.resources.providers_manual_add_title
import cc.ptoe.messenger.generated.resources.providers_model_display_name_label
import cc.ptoe.messenger.generated.resources.providers_model_display_name_placeholder
import cc.ptoe.messenger.generated.resources.providers_model_id_empty
import cc.ptoe.messenger.generated.resources.providers_model_id_label
import cc.ptoe.messenger.generated.resources.providers_model_id_placeholder
import cc.ptoe.messenger.generated.resources.providers_model_context_window
import cc.ptoe.messenger.generated.resources.providers_model_disabled
import cc.ptoe.messenger.generated.resources.providers_model_rates
import cc.ptoe.messenger.generated.resources.providers_model_rates_cache
import cc.ptoe.messenger.generated.resources.providers_model_rates_free
import cc.ptoe.messenger.generated.resources.providers_model_list
import cc.ptoe.messenger.generated.resources.providers_model_management
import cc.ptoe.messenger.generated.resources.providers_model_saved
import cc.ptoe.messenger.generated.resources.providers_models_available
import cc.ptoe.messenger.generated.resources.providers_no_models
import cc.ptoe.messenger.generated.resources.providers_save_selected
import cc.ptoe.messenger.generated.resources.providers_select_models_title
import cc.ptoe.messenger.generated.resources.providers_selected_count
import cc.ptoe.messenger.generated.resources.providers_sync_models_title
import cc.ptoe.messenger.generated.resources.providers_syncing
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
    providerId: String,
    onBackClick: () -> Unit,
    onModelClick: (ChatModel) -> Unit = {},
    viewModel: ProviderDetailViewModel = viewModel(
        factory = ProviderDetailViewModel.provideFactory(
            providerRepository = AppContainerHolder.instance.providerRepository,
            modelRepository = AppContainerHolder.instance.modelRepository,
            coreBridge = AppContainerHolder.instance.coreBridge,
            providerId = providerId
        )
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle(initialValue = emptyList())
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val strModelIdEmpty = stringResource(Res.string.providers_model_id_empty)
    val strModelSaved = stringResource(Res.string.providers_model_saved)

    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<ChatModel?>(null) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var selectedModelIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    // 双栏布局下切换 Provider 时按 id 重新加载，避免依赖 ViewModel 重建
    LaunchedEffect(providerId) {
        viewModel.loadProvider(providerId)
    }

    LaunchedEffect(uiState.syncStatus) {
        if (uiState.syncStatus == SyncStatus.SUCCESS && uiState.syncedModels.isNotEmpty()) {
            selectedModelIds = uiState.syncedModels.map { it.modelId }.toSet()
            showSyncDialog = true
        } else if (uiState.syncStatus == SyncStatus.ERROR) {
            uiState.syncError?.let { error ->
                snackbarHostState.showSnackbar(error)
                viewModel.resetSyncState()
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sizeClass = windowSizeClassFor(maxWidth)
        val enableContextMenu = sizeClass != WindowSizeClass.Compact

        Scaffold(
            topBar = {
                if (uiState.isMultiSelectMode) {
                    MultiSelectTopBar(
                        selectedCount = uiState.selectedModelIds.size,
                        onExit = { viewModel.exitMultiSelectMode() },
                        onSelectAll = { viewModel.selectAllModels() },
                        onDeselectAll = { viewModel.clearSelection() },
                        actions = {
                            IconButton(onClick = { viewModel.enableSelectedModels() }) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = stringResource(Res.string.action_enable)
                                )
                            }
                            IconButton(onClick = { viewModel.disableSelectedModels() }) {
                                Icon(
                                    imageVector = Icons.Default.VisibilityOff,
                                    contentDescription = stringResource(Res.string.action_disable)
                                )
                            }
                            IconButton(onClick = { showBatchDeleteDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = stringResource(Res.string.action_delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    )
                } else {
                    TopAppBar(
                        title = {
                            Text(
                                text = uiState.provider?.name
                                    ?: stringResource(Res.string.providers_model_management),
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
                        }
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            if (uiState.isLoading) {
                LoadingIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            } else if (uiState.error != null) {
                EmptyState(
                    icon = Icons.Default.Cloud,
                    message = uiState.error ?: stringResource(Res.string.error_load_failed),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    uiState.provider?.let { provider ->
                        ProviderInfoCard(
                            name = provider.name,
                            baseUrl = provider.baseUrl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }

                    ProviderModelList(
                        models = models,
                        isMultiSelectMode = uiState.isMultiSelectMode,
                        selectedModelIds = uiState.selectedModelIds,
                        onSyncClick = { viewModel.syncModels() },
                        onAddClick = { showAddDialog = true },
                        onToggleEnabled = { model, enabled ->
                            viewModel.toggleModelEnabled(model.id, enabled)
                        },
                        onDeleteClick = { model -> showDeleteDialog = model },
                        onLongClick = { model -> viewModel.enterMultiSelectMode(model.id) },
                        onClick = { model ->
                            if (uiState.isMultiSelectMode) {
                                viewModel.toggleModelSelection(model.id)
                            } else {
                                onModelClick(model)
                            }
                        },
                        enableContextMenu = enableContextMenu,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }

            if (uiState.syncStatus == SyncStatus.LOADING) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text(stringResource(Res.string.providers_sync_models_title)) },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(24.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(stringResource(Res.string.providers_syncing))
                        }
                    },
                    confirmButton = {},
                    dismissButton = {}
                )
            }

            if (showAddDialog) {
                AddModelDialog(
                    onConfirm = { modelId, displayName ->
                        val success = viewModel.addModelManually(modelId, displayName)
                        if (success) {
                            showAddDialog = false
                        } else {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(strModelIdEmpty)
                            }
                        }
                    },
                    onDismiss = { showAddDialog = false }
                )
            }

            if (showDeleteDialog != null) {
                ConfirmationDialog(
                    title = stringResource(Res.string.providers_delete_model_title),
                    text = stringResource(Res.string.providers_delete_model_confirm, showDeleteDialog?.displayName ?: ""),
                    confirmButtonText = stringResource(Res.string.action_delete),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = {
                        showDeleteDialog?.let { model ->
                            viewModel.deleteModel(model.id)
                        }
                        showDeleteDialog = null
                    },
                    onDismiss = { showDeleteDialog = null }
                )
            }

            if (showBatchDeleteDialog) {
                ConfirmationDialog(
                    title = stringResource(Res.string.providers_delete_models_title),
                    text = stringResource(Res.string.providers_delete_models_confirm, uiState.selectedModelIds.size),
                    confirmButtonText = stringResource(Res.string.action_delete),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = {
                        viewModel.deleteSelectedModels()
                        showBatchDeleteDialog = false
                    },
                    onDismiss = { showBatchDeleteDialog = false }
                )
            }

            if (showSyncDialog && uiState.syncedModels.isNotEmpty()) {
                SyncModelSelectionDialog(
                    models = uiState.syncedModels,
                    selectedModelIds = selectedModelIds,
                    onToggleSelection = { modelId ->
                        selectedModelIds = if (modelId in selectedModelIds) {
                            selectedModelIds - modelId
                        } else {
                            selectedModelIds + modelId
                        }
                    },
                    onConfirm = {
                        coroutineScope.launch {
                            viewModel.saveSelectedModels(selectedModelIds.toList())
                            showSyncDialog = false
                            snackbarHostState.showSnackbar(strModelSaved)
                        }
                    },
                    onDismiss = {
                        showSyncDialog = false
                        viewModel.resetSyncState()
                    }
                )
            }
        }
    }
}

@Composable
private fun ProviderInfoCard(
    name: String,
    baseUrl: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = baseUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Provider 详情里的模型列表：标题行 + 模型行。
 *
 * 标题行始终存在，多选时只禁用同步/添加按钮——此前整行在多选态被移除，
 * 列表整体上移约 56dp，长按的一瞬间画面会明显跳动。
 */
@Composable
internal fun ProviderModelList(
    models: List<ChatModel>,
    isMultiSelectMode: Boolean,
    selectedModelIds: Set<String>,
    onSyncClick: () -> Unit,
    onAddClick: () -> Unit,
    onToggleEnabled: (ChatModel, Boolean) -> Unit,
    onDeleteClick: (ChatModel) -> Unit,
    onLongClick: (ChatModel) -> Unit,
    onClick: (ChatModel) -> Unit,
    modifier: Modifier = Modifier,
    enableContextMenu: Boolean = false
) {
    Column(modifier = modifier) {
        ModelListHeader(
            onSyncClick = onSyncClick,
            onAddClick = onAddClick,
            actionsEnabled = !isMultiSelectMode,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (models.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Cloud,
                message = stringResource(Res.string.providers_no_models),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(models, key = { it.id }) { model ->
                    ModelListItem(
                        model = model,
                        isMultiSelectMode = isMultiSelectMode,
                        isSelected = model.id in selectedModelIds,
                        onToggleEnabled = { enabled -> onToggleEnabled(model, enabled) },
                        onDeleteClick = { onDeleteClick(model) },
                        onLongClick = { onLongClick(model) },
                        onClick = { onClick(model) },
                        enableContextMenu = enableContextMenu,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelListHeader(
    onSyncClick: () -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(Res.string.providers_model_list),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = {
                    PlainTooltip { Text(stringResource(Res.string.action_sync_models)) }
                },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onSyncClick, enabled = actionsEnabled) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(Res.string.action_sync_models)
                    )
                }
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = {
                    PlainTooltip { Text(stringResource(Res.string.action_manual_add)) }
                },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onAddClick, enabled = actionsEnabled) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(Res.string.action_manual_add)
                    )
                }
            }
        }
    }
}

@Composable
internal fun ModelListItem(
    model: ChatModel,
    isMultiSelectMode: Boolean,
    isSelected: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onDeleteClick: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enableContextMenu: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val contextMenuState = rememberContextMenuState()
    val hovered by interactionSource.collectIsHoveredAsState()
    // 多选只改变整行底色：行内控件与排版保持不变，避免长按后列表“跳版”。
    val backgroundColor = when {
        isMultiSelectMode && isSelected -> MaterialTheme.colorScheme.secondaryContainer
        hovered && enableContextMenu -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(backgroundColor)
            .hoverable(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .then(
                if (enableContextMenu) Modifier.onContextMenu(contextMenuState)
                else Modifier
            )
            .semantics { if (isMultiSelectMode) selected = isSelected }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (model.displayName != model.modelId) {
                Text(
                    text = model.modelId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ModelMetadataBadges(
                contextWindow = model.contextWindow,
                inputRate = model.inputRate,
                outputRate = model.outputRate,
                cacheReadRate = model.cacheReadRate,
                isEnabled = model.isEnabled,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (enableContextMenu) {
            CursorDropdownMenu(
                state = contextMenuState,
                onDismiss = {}
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_enable)) },
                    onClick = {
                        contextMenuState.hide()
                        onToggleEnabled(true)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_disable)) },
                    onClick = {
                        contextMenuState.hide()
                        onToggleEnabled(false)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_delete)) },
                    onClick = {
                        contextMenuState.hide()
                        onDeleteClick()
                    }
                )
            }
        }
    }
}

/**
 * 模型元数据标签行：上下文窗口 + 倍率。单独占一行、排在模型名称下方，
 * 名称因此拿到整行宽度（此前标签与名称同排，窄屏上名称只剩省略号）。
 * 用 FlowRow 让标签在极窄屏上逐个换行，而不是把标签内部文字挤断。
 */
@Composable
private fun ModelMetadataBadges(
    contextWindow: Long,
    inputRate: Double?,
    outputRate: Double?,
    cacheReadRate: Double?,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true
) {
    val hasContext = formatContextWindow(contextWindow).isNotEmpty()
    val hasRate = inputRate != null && outputRate != null
    if (!hasContext && !hasRate && isEnabled) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ContextWindowBadge(contextWindow = contextWindow)
        RateBadge(
            inputRate = inputRate,
            outputRate = outputRate,
            cacheReadRate = cacheReadRate
        )
        if (!isEnabled) {
            DisabledBadge()
        }
    }
}

/**
 * 「已禁用」标签：行内不再有开关，列表里必须仍能看出模型当前不可用。
 */
@Composable
private fun DisabledBadge() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = stringResource(Res.string.providers_model_disabled),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/**
 * 模型上下文窗口紧凑标签（272K / 1M / 1.5M）。0 = 未知/不限，不显示。
 */
@Composable
private fun ContextWindowBadge(contextWindow: Long) {
    val label = formatContextWindow(contextWindow)
    if (label.isEmpty()) return
    val description = stringResource(Res.string.providers_model_context_window, label)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.semantics { contentDescription = description }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/**
 * 模型倍率紧凑标签：依次为输入 / 缓存读取 / 输出（如「×0.33 · ×4.35 · ×0.99」）。
 * 三者均为 0 时显示「免费」；输入/输出任一缺失（服务商未提供元数据）不显示。
 * 标签不带文字前缀（完整含义见无障碍描述），以保持一行、不与模型名抢宽度。
 */
@Composable
private fun RateBadge(inputRate: Double?, outputRate: Double?, cacheReadRate: Double?) {
    if (inputRate == null || outputRate == null) return
    val isFree = inputRate <= 0.0 && outputRate <= 0.0 && (cacheReadRate == null || cacheReadRate <= 0.0)
    val label = if (isFree) {
        stringResource(Res.string.providers_model_rates_free)
    } else if (cacheReadRate != null) {
        "×${formatRate(inputRate)} · ×${formatRate(cacheReadRate)} · ×${formatRate(outputRate)}"
    } else {
        "×${formatRate(inputRate)} / ×${formatRate(outputRate)}"
    }
    val description = if (isFree) {
        stringResource(Res.string.providers_model_rates_free)
    } else if (cacheReadRate != null) {
        stringResource(
            Res.string.providers_model_rates_cache,
            formatRate(inputRate),
            formatRate(cacheReadRate),
            formatRate(outputRate)
        )
    } else {
        stringResource(Res.string.providers_model_rates, formatRate(inputRate), formatRate(outputRate))
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.semantics { contentDescription = description }
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun AddModelDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var modelId by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.providers_manual_add_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text(stringResource(Res.string.providers_model_id_label)) },
                    placeholder = { Text(stringResource(Res.string.providers_model_id_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(Res.string.providers_model_display_name_label)) },
                    placeholder = { Text(stringResource(Res.string.providers_model_display_name_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(modelId.trim(), displayName.trim()) },
                enabled = modelId.isNotBlank()
            ) {
                Text(stringResource(Res.string.action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}

@Composable
private fun SyncModelSelectionDialog(
    models: List<ChatModel>,
    selectedModelIds: Set<String>,
    onToggleSelection: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.providers_select_models_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text(
                    text = stringResource(Res.string.providers_models_available, models.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                ) {
                    items(models, key = { it.modelId }) { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = model.modelId in selectedModelIds,
                                onCheckedChange = { onToggleSelection(model.modelId) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                ModelMetadataBadges(
                                    contextWindow = model.contextWindow,
                                    inputRate = model.inputRate,
                                    outputRate = model.outputRate,
                                    cacheReadRate = model.cacheReadRate,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = selectedModelIds.isNotEmpty()
            ) {
                Text(stringResource(Res.string.providers_save_selected, selectedModelIds.size))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}
