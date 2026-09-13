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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.ui.components.ConfirmationDialog
import cc.ptoe.messenger.presentation.ui.components.CursorDropdownMenu
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.ui.components.MultiSelectTopBar
import cc.ptoe.messenger.presentation.ui.components.onContextMenu
import cc.ptoe.messenger.presentation.ui.components.rememberContextMenuState
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor
import cc.ptoe.messenger.presentation.viewmodel.AgentWithModel
import cc.ptoe.messenger.presentation.viewmodel.AgentsViewModel
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_add
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_clone
import cc.ptoe.messenger.generated.resources.action_delete
import cc.ptoe.messenger.generated.resources.action_edit
import cc.ptoe.messenger.generated.resources.action_more
import cc.ptoe.messenger.generated.resources.agents_close_menu
import cc.ptoe.messenger.generated.resources.agents_default_badge
import cc.ptoe.messenger.generated.resources.agents_delete_batch_confirm
import cc.ptoe.messenger.generated.resources.agents_delete_confirm
import cc.ptoe.messenger.generated.resources.agents_delete_title
import cc.ptoe.messenger.generated.resources.agents_empty_message
import cc.ptoe.messenger.generated.resources.agents_market
import cc.ptoe.messenger.generated.resources.agents_model_default
import cc.ptoe.messenger.generated.resources.agents_model_label
import cc.ptoe.messenger.generated.resources.agents_new_agent
import cc.ptoe.messenger.generated.resources.agents_no_system_prompt
import cc.ptoe.messenger.generated.resources.agents_title
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onAddClick: () -> Unit,
    onMarketClick: () -> Unit,
    onEditClick: (String) -> Unit,
    onAgentClick: (String) -> Unit,
    viewModel: AgentsViewModel = viewModel(
        factory = AgentsViewModel.provideFactory(
            agentRepository = AppContainerHolder.instance.agentRepository,
            modelRepository = AppContainerHolder.instance.modelRepository
        )
    )
) {
    val agents by viewModel.agentsWithModel.collectAsStateWithLifecycle(
        initialValue = emptyList()
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var fabExpanded by remember { mutableStateOf(false) }
    val cloudUser by AppContainerHolder.instance.cloudSyncRepository.user.collectAsStateWithLifecycle(initialValue = null)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sizeClass = windowSizeClassFor(maxWidth)
        val enableContextMenu = sizeClass != WindowSizeClass.Compact

        Scaffold(
            topBar = {
                if (uiState.isMultiSelectMode) {
                    MultiSelectTopBar(
                        selectedCount = uiState.selectedAgentIds.size,
                        onExit = { viewModel.exitMultiSelectMode() },
                        onSelectAll = { viewModel.selectAll(agents.map { it.agent.id }) },
                        onDeselectAll = { viewModel.deselectAll() },
                        actions = {
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
                        title = { Text(text = stringResource(Res.string.agents_title)) }
                    )
                }
            },
            floatingActionButton = {
                if (!uiState.isMultiSelectMode) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (cloudUser != null && fabExpanded) {
                            SmallFloatingActionButton(onClick = {
                                fabExpanded = false
                                onMarketClick()
                            }) {
                                Icon(imageVector = Icons.Default.Storefront, contentDescription = stringResource(Res.string.agents_market))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            SmallFloatingActionButton(onClick = {
                                fabExpanded = false
                                onAddClick()
                            }) {
                                Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(Res.string.agents_new_agent))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                            tooltip = {
                                PlainTooltip {
                                    Text(
                                        if (fabExpanded) stringResource(Res.string.agents_close_menu)
                                        else stringResource(Res.string.action_add)
                                    )
                                }
                            },
                            state = rememberTooltipState()
                        ) {
                            FloatingActionButton(onClick = {
                                if (cloudUser == null) onAddClick() else fabExpanded = !fabExpanded
                            }) {
                                Icon(
                                    imageVector = if (fabExpanded) Icons.Default.Close else Icons.Default.Add,
                                    contentDescription = if (fabExpanded) stringResource(Res.string.agents_close_menu) else stringResource(Res.string.action_add)
                                )
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            if (agents.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.SmartToy,
                    message = stringResource(Res.string.agents_empty_message),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            } else {
                // Desktop / large window: constrain list to a centered 720 dp column.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 720.dp)
                    ) {
                    items(agents, key = { it.agent.id }) { item ->
                        AgentListItem(
                            item = item,
                            isMultiSelectMode = uiState.isMultiSelectMode,
                            isMultiSelected = item.agent.id in uiState.selectedAgentIds,
                            enableContextMenu = enableContextMenu,
                            onClick = {
                                if (uiState.isMultiSelectMode) {
                                    if (!item.agent.isDefault) {
                                        viewModel.toggleSelection(item.agent.id)
                                    }
                                } else {
                                    onAgentClick(item.agent.id)
                                }
                            },
                            onLongClick = { viewModel.enterMultiSelectMode(item.agent.id) },
                            onEditClick = { onEditClick(item.agent.id) },
                            onCloneClick = { viewModel.cloneAgent(item.agent.id) },
                            onDeleteClick = {
                                if (!item.agent.isDefault) {
                                    showDeleteDialog = item.agent.id
                                }
                            },
                            canDelete = !item.agent.isDefault
                        )
                    }
                    }
                }
            }

            if (showDeleteDialog != null) {
                ConfirmationDialog(
                    title = stringResource(Res.string.agents_delete_title),
                    text = stringResource(Res.string.agents_delete_confirm),
                    confirmButtonText = stringResource(Res.string.action_delete),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = {
                        showDeleteDialog?.let { id ->
                            viewModel.deleteAgent(id)
                        }
                        showDeleteDialog = null
                    },
                    onDismiss = { showDeleteDialog = null }
                )
            }

            if (showBatchDeleteDialog) {
                ConfirmationDialog(
                    title = stringResource(Res.string.agents_delete_title),
                    text = stringResource(
                        Res.string.agents_delete_batch_confirm,
                        uiState.selectedAgentIds.size
                    ),
                    confirmButtonText = stringResource(Res.string.action_delete),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = {
                        viewModel.deleteAgentsBatch(uiState.selectedAgentIds.toList())
                        showBatchDeleteDialog = false
                    },
                    onDismiss = { showBatchDeleteDialog = false }
                )
            }
        }
    }
}

@Composable
private fun AgentListItem(
    item: AgentWithModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEditClick: () -> Unit,
    onCloneClick: () -> Unit,
    onDeleteClick: () -> Unit,
    canDelete: Boolean,
    modifier: Modifier = Modifier,
    isMultiSelectMode: Boolean = false,
    isMultiSelected: Boolean = false,
    enableContextMenu: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val contextMenuState = rememberContextMenuState()
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val backgroundColor = when {
        isMultiSelected -> MaterialTheme.colorScheme.secondaryContainer
        hovered && enableContextMenu -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelectMode) {
            Checkbox(
                checked = isMultiSelected,
                onCheckedChange = null,
                enabled = !item.agent.isDefault,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        AgentAvatar(
            avatar = item.agent.avatar,
            size = 40.dp
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.agent.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.agent.isDefault) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(Res.string.agents_default_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.agent.systemPrompt.ifBlank { stringResource(Res.string.agents_no_system_prompt) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(Res.string.agents_model_label, item.model?.displayName ?: stringResource(Res.string.agents_model_default)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!isMultiSelectMode) {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(Res.string.action_more)
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_edit)) },
                        onClick = {
                            expanded = false
                            onEditClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_clone)) },
                        onClick = {
                            expanded = false
                            onCloneClick()
                        }
                    )
                    if (canDelete) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.action_delete)) },
                            onClick = {
                                expanded = false
                                onDeleteClick()
                            }
                        )
                    }
                }
            }
        }

        if (enableContextMenu) {
            CursorDropdownMenu(
                state = contextMenuState,
                onDismiss = {}
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_edit)) },
                    onClick = {
                        contextMenuState.hide()
                        onEditClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_clone)) },
                    onClick = {
                        contextMenuState.hide()
                        onCloneClick()
                    }
                )
                if (canDelete) {
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
}
