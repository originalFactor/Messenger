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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.presentation.ui.components.ConfirmationDialog
import cc.ptoe.messenger.presentation.ui.components.ConversationListItem
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.ui.components.InputDialog
import cc.ptoe.messenger.presentation.ui.components.LocalBottomNavClearance
import cc.ptoe.messenger.presentation.ui.components.MultiSelectTopBar
import cc.ptoe.messenger.presentation.ui.components.SingleChoiceDialog
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor
import cc.ptoe.messenger.presentation.viewmodel.ConversationsViewModel
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_confirm
import cc.ptoe.messenger.generated.resources.action_delete
import cc.ptoe.messenger.generated.resources.conversations_delete_batch_confirm
import cc.ptoe.messenger.generated.resources.conversations_delete_confirm
import cc.ptoe.messenger.generated.resources.conversations_delete_title
import cc.ptoe.messenger.generated.resources.conversations_empty
import cc.ptoe.messenger.generated.resources.conversations_new
import cc.ptoe.messenger.generated.resources.conversations_rename_hint
import cc.ptoe.messenger.generated.resources.conversations_rename_title
import cc.ptoe.messenger.generated.resources.conversations_select_agent_title
import cc.ptoe.messenger.generated.resources.conversations_switch_agent
import cc.ptoe.messenger.generated.resources.conversations_title
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder

/**
 * 会话主页：固定展示全部聊天（按 Agent 的过滤入口在 Agent 页的
 * 聊天列表子页），不做 Agent 筛选。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
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
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val agentsById = remember(allAgents) { allAgents.associateBy(Agent::id) }
    // 可选为聊天对象的 Agent：内置标题生成等功能型角色不出现在选择器中。
    val selectableAgents = remember(allAgents) { allAgents.filter { it.role != Agent.ROLE_TITLE } }

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
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
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
                    androidx.compose.material3.LargeFlexibleTopAppBar(
                        title = {
                            Text(text = stringResource(Res.string.conversations_title))
                        },
                        colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        modifier = Modifier.statusBarsPadding()
                    )
                }
            },
            floatingActionButton = {
                if (!uiState.isMultiSelectMode) {
                    // Keep the FAB above the floating bottom navigation pill
                    // (80 dp bar + 12 dp gap) on Compact layouts; 0 dp on the
                    // rail layout (desktop / large windows), which has no pill.
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(bottom = LocalBottomNavClearance.current)
                    ) {
                        FloatingActionButton(
                            onClick = { showAgentPicker = true }
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(Res.string.conversations_new))
                        }
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
                        .navigationBarsPadding()
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .navigationBarsPadding(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = LocalBottomNavClearance.current
                    )
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
