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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.ui.components.ConfirmationDialog
import cc.ptoe.messenger.presentation.ui.components.CursorDropdownMenu
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.ui.components.InputDialog
import cc.ptoe.messenger.presentation.ui.components.MultiSelectTopBar
import cc.ptoe.messenger.presentation.ui.components.SingleChoiceDialog
import cc.ptoe.messenger.presentation.ui.components.onContextMenu
import cc.ptoe.messenger.presentation.ui.components.rememberContextMenuState
import cc.ptoe.messenger.presentation.utils.DateTimeUtils
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.stripThinkBlock
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor
import cc.ptoe.messenger.presentation.viewmodel.ConversationsViewModel
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_clone
import cc.ptoe.messenger.generated.resources.action_confirm
import cc.ptoe.messenger.generated.resources.action_delete
import cc.ptoe.messenger.generated.resources.action_more
import cc.ptoe.messenger.generated.resources.action_rename
import cc.ptoe.messenger.generated.resources.conversations_all_agents
import cc.ptoe.messenger.generated.resources.conversations_delete_batch_confirm
import cc.ptoe.messenger.generated.resources.conversations_delete_confirm
import cc.ptoe.messenger.generated.resources.conversations_delete_title
import cc.ptoe.messenger.generated.resources.conversations_empty
import cc.ptoe.messenger.generated.resources.conversations_new
import cc.ptoe.messenger.generated.resources.conversations_no_message
import cc.ptoe.messenger.generated.resources.conversations_rename_hint
import cc.ptoe.messenger.generated.resources.conversations_rename_title
import cc.ptoe.messenger.generated.resources.conversations_select_agent_title
import cc.ptoe.messenger.generated.resources.conversations_switch_agent
import cc.ptoe.messenger.generated.resources.conversations_title
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onConversationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedConversationId: String? = null,
    viewModel: ConversationsViewModel = viewModel(
        factory = ConversationsViewModel.provideFactory(
            conversationRepository = AppContainerHolder.instance.conversationRepository,
            messageRepository = AppContainerHolder.instance.messageRepository,
            currentAgentRepository = AppContainerHolder.instance.currentAgentRepository,
            agentRepository = AppContainerHolder.instance.agentRepository,
            modelRepository = AppContainerHolder.instance.modelRepository
        )
    )
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val currentAgent by viewModel.currentAgent.collectAsStateWithLifecycle()
    val allAgents by viewModel.allAgents.collectAsStateWithLifecycle()
    val showAllAgents by viewModel.showAllAgents.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val agentsById = remember(allAgents) { allAgents.associateBy(Agent::id) }
    // 可选为聊天对象的 Agent：内置标题生成等功能型角色不出现在选择器中。
    val selectableAgents = remember(allAgents) { allAgents.filter { it.role != Agent.ROLE_TITLE } }

    var showAgentDropdown by remember { mutableStateOf(false) }
    var renameConversationId by remember { mutableStateOf<String?>(null) }
    var deleteConversationId by remember { mutableStateOf<String?>(null) }
    var renameInitialTitle by remember { mutableStateOf("") }
    var showAgentPicker by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var switchAgentTargetIds by remember { mutableStateOf<List<String>?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sizeClass = windowSizeClassFor(maxWidth)
        val enableContextMenu = sizeClass != WindowSizeClass.Compact

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                if (uiState.isMultiSelectMode) {
                    MultiSelectTopBar(
                        selectedCount = uiState.selectedConversationIds.size,
                        onExit = { viewModel.exitMultiSelectMode() },
                        onSelectAll = { viewModel.selectAll(conversations.map { it.id }) },
                        onDeselectAll = { viewModel.deselectAll() },
                        actions = {
                            IconButton(onClick = {
                                switchAgentTargetIds = uiState.selectedConversationIds.toList()
                            }) {
                                Icon(
                                    imageVector = Icons.Default.SwapHoriz,
                                    contentDescription = stringResource(Res.string.conversations_switch_agent)
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { showAgentDropdown = true }
                            ) {
                                Text(
                                    text = if (showAllAgents) stringResource(Res.string.conversations_all_agents) else (currentAgent?.name ?: stringResource(Res.string.conversations_title)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = stringResource(Res.string.conversations_switch_agent)
                                )

                                DropdownMenu(
                                    expanded = showAgentDropdown,
                                    onDismissRequest = { showAgentDropdown = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.conversations_all_agents)) },
                                        onClick = {
                                            viewModel.showAllAgents()
                                            showAgentDropdown = false
                                        }
                                    )
                                    selectableAgents.forEach { agent ->
                                        DropdownMenuItem(
                                            text = { Text(text = agent.name) },
                                            onClick = {
                                                viewModel.switchAgent(agent.id)
                                                showAgentDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            },
            floatingActionButton = {
                if (!uiState.isMultiSelectMode) {
                    FloatingActionButton(
                        onClick = {
                            if (showAllAgents) {
                                showAgentPicker = true
                            } else {
                                viewModel.createNewConversation { conversationId ->
                                    onConversationClick(conversationId)
                                }
                            }
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(Res.string.conversations_new))
                    }
                }
            }
        ) { innerPadding ->
            if (conversations.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.ChatBubbleOutline,
                    message = stringResource(Res.string.conversations_empty),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationListItem(
                            conversation = conversation,
                            avatar = agentsById[conversation.agentId]?.avatar,
                            isHighlighted = conversation.id == selectedConversationId,
                            isMultiSelectMode = uiState.isMultiSelectMode,
                            isMultiSelected = conversation.id in uiState.selectedConversationIds,
                            enableContextMenu = enableContextMenu,
                            onClick = {
                                if (uiState.isMultiSelectMode) {
                                    viewModel.toggleSelection(conversation.id)
                                } else {
                                    onConversationClick(conversation.id)
                                }
                            },
                            onLongClick = { viewModel.enterMultiSelectMode(conversation.id) },
                            onCloneClick = {
                                viewModel.cloneConversation(conversation.id, onConversationClick)
                            },
                            onRenameClick = {
                                renameInitialTitle = conversation.title
                                renameConversationId = conversation.id
                            },
                            onDeleteClick = {
                                deleteConversationId = conversation.id
                            },
                            onSwitchAgentClick = {
                                switchAgentTargetIds = listOf(conversation.id)
                            }
                        )
                    }
                }
            }

            if (renameConversationId != null) {
                InputDialog(
                    title = stringResource(Res.string.conversations_rename_title),
                    initialValue = renameInitialTitle,
                    hint = stringResource(Res.string.conversations_rename_hint),
                    confirmButtonText = stringResource(Res.string.action_confirm),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = { newTitle ->
                        renameConversationId?.let { id ->
                            viewModel.renameConversation(id, newTitle)
                        }
                        renameConversationId = null
                    },
                    onDismiss = { renameConversationId = null }
                )
            }

            if (deleteConversationId != null) {
                ConfirmationDialog(
                    title = stringResource(Res.string.conversations_delete_title),
                    text = stringResource(Res.string.conversations_delete_confirm),
                    confirmButtonText = stringResource(Res.string.action_delete),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = {
                        deleteConversationId?.let { id ->
                            viewModel.deleteConversation(id)
                        }
                        deleteConversationId = null
                    },
                    onDismiss = { deleteConversationId = null }
                )
            }

            if (showBatchDeleteDialog) {
                ConfirmationDialog(
                    title = stringResource(Res.string.conversations_delete_title),
                    text = stringResource(
                        Res.string.conversations_delete_batch_confirm,
                        uiState.selectedConversationIds.size
                    ),
                    confirmButtonText = stringResource(Res.string.action_delete),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = {
                        viewModel.deleteConversationsBatch(uiState.selectedConversationIds.toList())
                        showBatchDeleteDialog = false
                    },
                    onDismiss = { showBatchDeleteDialog = false }
                )
            }

            if (showAgentPicker) {
                SingleChoiceDialog(
                    title = stringResource(Res.string.conversations_select_agent_title),
                    items = selectableAgents,
                    initialSelectedId = currentAgent?.id ?: selectableAgents.firstOrNull()?.id,
                    itemId = { it.id },
                    itemLabel = { it.name },
                    confirmButtonText = stringResource(Res.string.action_confirm),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = { agent ->
                        showAgentPicker = false
                        if (agent != null) {
                            viewModel.createNewConversation(agentId = agent.id) { conversationId ->
                                onConversationClick(conversationId)
                            }
                        }
                    },
                    onDismiss = { showAgentPicker = false }
                )
            }

            if (switchAgentTargetIds != null) {
                val targetIds = switchAgentTargetIds!!
                SingleChoiceDialog(
                    title = stringResource(Res.string.conversations_select_agent_title),
                    items = allAgents,
                    initialSelectedId = null,
                    itemId = { it.id },
                    itemLabel = { it.name },
                    confirmButtonText = stringResource(Res.string.action_confirm),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = { agent ->
                        if (agent != null) {
                            viewModel.switchAgentForConversations(targetIds, agent.id)
                        }
                        switchAgentTargetIds = null
                    },
                    onDismiss = { switchAgentTargetIds = null }
                )
            }
        }
    }
}

@Composable
private fun ConversationListItem(
    conversation: Conversation,
    avatar: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCloneClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onSwitchAgentClick: () -> Unit,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false,
    isMultiSelectMode: Boolean = false,
    isMultiSelected: Boolean = false,
    enableContextMenu: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val contextMenuState = rememberContextMenuState()
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val backgroundColor = when {
        isHighlighted || isMultiSelected -> MaterialTheme.colorScheme.secondaryContainer
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
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        AgentAvatar(
            avatar = avatar,
            size = 40.dp
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = DateTimeUtils.formatMessageTime(conversation.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            val noMessage = stringResource(Res.string.conversations_no_message)
            Text(
                text = remember(conversation.lastMessage, noMessage) {
                    conversation.lastMessage?.let { stripThinkBlock(it) }?.ifBlank { null } ?: noMessage
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
                        text = { Text(stringResource(Res.string.action_clone)) },
                        onClick = {
                            expanded = false
                            onCloneClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_rename)) },
                        onClick = {
                            expanded = false
                            onRenameClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.conversations_switch_agent)) },
                        onClick = {
                            expanded = false
                            onSwitchAgentClick()
                        }
                    )
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

        if (enableContextMenu) {
            CursorDropdownMenu(
                state = contextMenuState,
                onDismiss = {}
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_rename)) },
                    onClick = {
                        contextMenuState.hide()
                        onRenameClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.action_clone)) },
                    onClick = {
                        contextMenuState.hide()
                        onCloneClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.conversations_switch_agent)) },
                    onClick = {
                        contextMenuState.hide()
                        onSwitchAgentClick()
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
