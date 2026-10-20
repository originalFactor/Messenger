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

package cc.ptoe.messenger.presentation.ui.projects

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_confirm
import cc.ptoe.messenger.generated.resources.action_delete
import cc.ptoe.messenger.generated.resources.conversations_delete_batch_confirm
import cc.ptoe.messenger.generated.resources.conversations_delete_confirm
import cc.ptoe.messenger.generated.resources.conversations_delete_title
import cc.ptoe.messenger.generated.resources.conversations_new
import cc.ptoe.messenger.generated.resources.conversations_rename_hint
import cc.ptoe.messenger.generated.resources.conversations_rename_title
import cc.ptoe.messenger.generated.resources.conversations_select_agent_title
import cc.ptoe.messenger.generated.resources.conversations_switch_agent
import cc.ptoe.messenger.generated.resources.projects_chats_title
import cc.ptoe.messenger.generated.resources.projects_folder_empty
import cc.ptoe.messenger.generated.resources.projects_moved_to_chat
import cc.ptoe.messenger.generated.resources.projects_moved_to_chat_confirm
import cc.ptoe.messenger.generated.resources.projects_no_project_folder
import cc.ptoe.messenger.presentation.ui.components.ConfirmationDialog
import cc.ptoe.messenger.presentation.ui.components.ConversationListItem
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.ui.components.InputDialog
import cc.ptoe.messenger.presentation.ui.components.MultiSelectTopBar
import cc.ptoe.messenger.presentation.ui.components.SingleChoiceDialog
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor
import cc.ptoe.messenger.presentation.viewmodel.ProjectConversationsViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * 文件夹二级页：某个项目的会话列表，或 `projectId == null` 时的“无项目”
 * 文件夹（不属于任何项目的普通对话）。
 *
 * [agentId] 非 null 时只展示该 Agent 的会话（从 Agent 聊天列表点击文件夹进入），
 * 否则展示文件夹下的全部会话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectConversationsScreen(
    projectId: String?,
    onBackClick: () -> Unit,
    onConversationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    agentId: String? = null,
    showBackButton: Boolean = true,
    viewModel: ProjectConversationsViewModel = viewModel(
        factory = ProjectConversationsViewModel.provideFactory(
            projectId = projectId,
            agentId = agentId,
            projectRepository = AppContainerHolder.instance.projectRepository,
            conversationRepository = AppContainerHolder.instance.conversationRepository,
            agentRepository = AppContainerHolder.instance.agentRepository,
            messageRepository = AppContainerHolder.instance.messageRepository,
            modelRepository = AppContainerHolder.instance.modelRepository,
            currentAgentRepository = AppContainerHolder.instance.currentAgentRepository
        )
    )
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val allAgents by viewModel.allAgents.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val agentsById = remember(allAgents) { allAgents.associateBy { it.id } }

    // 进入列表页时触发一次空会话清理（规则在 Rust core）。
    LaunchedEffect(Unit) {
        viewModel.sweepEmptyConversations()
    }

    var renameConversationId by remember { mutableStateOf<String?>(null) }
    var renameInitialTitle by remember { mutableStateOf("") }
    var deleteConversationId by remember { mutableStateOf<String?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var moveToPlainTargetId by remember { mutableStateOf<String?>(null) }
    var switchAgentTargetIds by remember { mutableStateOf<List<String>?>(null) }

    val isProjectFolder = projectId != null
    val title = when {
        project != null -> project!!.name
        isProjectFolder -> stringResource(Res.string.projects_chats_title)
        else -> stringResource(Res.string.projects_no_project_folder)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val enableContextMenu = windowSizeClassFor(maxWidth) != WindowSizeClass.Compact

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
                        title = { Text(text = title, maxLines = 1) },
                        navigationIcon = {
                            if (showBackButton) {
                                IconButton(onClick = onBackClick) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(Res.string.action_back)
                                    )
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
                            viewModel.createNewConversation { conversationId ->
                                onConversationClick(conversationId)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(Res.string.conversations_new)
                        )
                    }
                }
            }
        ) { innerPadding ->
            if (conversations.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.ChatBubbleOutline,
                    message = stringResource(Res.string.projects_folder_empty),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .navigationBarsPadding()
                ) {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationListItem(
                            conversation = conversation,
                            avatar = agentsById[conversation.agentId]?.avatar,
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
                            onDeleteClick = { deleteConversationId = conversation.id },
                            // 项目文件夹里这一项是"移出项目"；"无项目"文件夹里
                            // 没有项目可移出，改成切换 Agent。
                            onSwitchAgentClick = {
                                if (isProjectFolder) {
                                    moveToPlainTargetId = conversation.id
                                } else {
                                    switchAgentTargetIds = listOf(conversation.id)
                                }
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
                        renameConversationId?.let { viewModel.renameConversation(it, newTitle) }
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
                        deleteConversationId?.let { viewModel.deleteConversation(it) }
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

            if (moveToPlainTargetId != null) {
                ConfirmationDialog(
                    title = stringResource(Res.string.projects_moved_to_chat),
                    text = stringResource(Res.string.projects_moved_to_chat_confirm),
                    confirmButtonText = stringResource(Res.string.action_confirm),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = {
                        moveToPlainTargetId?.let { viewModel.moveToPlainChats(it) }
                        moveToPlainTargetId = null
                    },
                    onDismiss = { moveToPlainTargetId = null }
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
