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

package cc.ptoe.messenger.presentation.ui.conversations

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_close
import cc.ptoe.messenger.generated.resources.action_confirm
import cc.ptoe.messenger.generated.resources.action_delete
import cc.ptoe.messenger.generated.resources.conversations_empty
import cc.ptoe.messenger.generated.resources.conversations_new
import cc.ptoe.messenger.generated.resources.conversations_new_menu
import cc.ptoe.messenger.generated.resources.conversations_select_agent_title
import cc.ptoe.messenger.generated.resources.conversations_title
import cc.ptoe.messenger.generated.resources.projects_create_title
import cc.ptoe.messenger.generated.resources.projects_delete_confirm
import cc.ptoe.messenger.generated.resources.projects_delete_title
import cc.ptoe.messenger.generated.resources.projects_edit_title
import cc.ptoe.messenger.generated.resources.projects_new
import cc.ptoe.messenger.presentation.platform.BackHandler
import cc.ptoe.messenger.presentation.ui.components.ConfirmationDialog
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.ui.components.LocalBottomNavClearance
import cc.ptoe.messenger.presentation.ui.components.SingleChoiceDialog
import cc.ptoe.messenger.presentation.ui.components.projectSection
import cc.ptoe.messenger.presentation.ui.components.recentChatSection
import cc.ptoe.messenger.presentation.ui.projects.ProjectEditorDialog
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.agentDisplayNames
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor
import cc.ptoe.messenger.presentation.viewmodel.ConversationsViewModel
import cc.ptoe.messenger.presentation.viewmodel.ProjectEditorState
import cc.ptoe.messenger.presentation.viewmodel.ProjectsViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * 会话主页：文件夹（项目 + “无项目”）负责归类，Recent Chats 是最近
 * 会话的快捷入口。
 *
 * 每个项目的会话进项目文件夹；不属于任何项目的普通对话统一收进
 * “无项目”文件夹。Recent Chats 只是跳转入口，不承担会话管理，因此同一
 * 会话不会在主页出现两份可操作的行。
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConversationsScreen(
    onConversationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onProjectClick: (String) -> Unit = {},
    onUnprojectedClick: () -> Unit = {},
    selectedConversationId: String? = null,
    viewModel: ConversationsViewModel = viewModel(
        factory = ConversationsViewModel.provideFactory(
            conversationRepository = AppContainerHolder.instance.conversationRepository,
            currentAgentRepository = AppContainerHolder.instance.currentAgentRepository,
            agentRepository = AppContainerHolder.instance.agentRepository,
            modelRepository = AppContainerHolder.instance.modelRepository,
            projectRepository = AppContainerHolder.instance.projectRepository
        )
    )
) {
    val currentAgent by viewModel.currentAgent.collectAsStateWithLifecycle()
    val allAgents by viewModel.allAgents.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val projectCounts by viewModel.projectConversationCounts.collectAsStateWithLifecycle()
    val unprojectedCount by viewModel.unprojectedConversationCount.collectAsStateWithLifecycle()
    val recentConversations by viewModel.recentConversations.collectAsStateWithLifecycle()
    val agentAvatars = remember(allAgents) { allAgents.associate { it.id to it.avatar } }
    val selectableAgents = remember(allAgents) { allAgents.filter { it.role != Agent.ROLE_TITLE } }
    // 内置 Agent 的种子名按系统语言展示（用户改过名的照原样）。
    val agentNames = agentDisplayNames(allAgents)

    // 进入列表页时触发一次空会话清理（规则在 Rust core）；双栏布局下右栏
    // 正在展示的会话要豁免，否则会被从脚下删掉。
    LaunchedEffect(Unit) {
        viewModel.sweepEmptyConversations(selectedConversationId)
    }

    var showAgentPicker by remember { mutableStateOf(false) }
    var fabMenuExpanded by remember { mutableStateOf(false) }
    var projectEditor by remember { mutableStateOf<ProjectEditorState?>(null) }
    var deleteProjectId by remember { mutableStateOf<String?>(null) }
    // 展开时按返回键先收起菜单，而不是直接退出页面。
    BackHandler(enabled = fabMenuExpanded, onBack = { fabMenuExpanded = false }) {}
    val projectsViewModel: ProjectsViewModel = viewModel(
        factory = ProjectsViewModel.provideFactory(
            projectRepository = AppContainerHolder.instance.projectRepository,
            conversationRepository = AppContainerHolder.instance.conversationRepository
        )
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sizeClass = windowSizeClassFor(maxWidth)
        val enableContextMenu = sizeClass != WindowSizeClass.Compact

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                androidx.compose.material3.LargeFlexibleTopAppBar(
                    title = { Text(text = stringResource(Res.string.conversations_title)) },
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.statusBarsPadding()
                )
            },
            floatingActionButton = {
                // 单个 FAB 展开为 M3 Expressive FAB menu：新建对话 / 新建项目。
                //
                // Keep the FAB above the floating bottom navigation pill
                // (80 dp bar + 12 dp gap) on Compact layouts; 0 dp on the
                // rail layout (desktop / large windows), which has no pill.
                //
                // FloatingActionButtonMenu 自带 16dp 横向内边距和 16dp 底部
                // 内边距，而 Scaffold 的 FAB 槽位也留了 16dp；用等量反向偏移
                // 抵消，让 FAB 与菜单都回到常规的 16dp 屏幕边距。
                val fabMenuOffsetX =
                    if (LocalLayoutDirection.current == LayoutDirection.Ltr) 16.dp else (-16).dp
                FloatingActionButtonMenu(
                    expanded = fabMenuExpanded,
                    button = {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                                if (fabMenuExpanded) TooltipAnchorPosition.Start
                                else TooltipAnchorPosition.Above
                            ),
                            tooltip = {
                                PlainTooltip {
                                    Text(
                                        stringResource(
                                            if (fabMenuExpanded) Res.string.action_close
                                            else Res.string.conversations_new_menu
                                        )
                                    )
                                }
                            },
                            state = rememberTooltipState()
                        ) {
                            ToggleFloatingActionButton(
                                checked = fabMenuExpanded,
                                onCheckedChange = { fabMenuExpanded = it }
                            ) {
                                val expanded = checkedProgress > 0.5f
                                Icon(
                                    imageVector = if (expanded) Icons.Default.Close
                                    else Icons.Default.Add,
                                    contentDescription = stringResource(
                                        if (expanded) Res.string.action_close
                                        else Res.string.conversations_new_menu
                                    ),
                                    modifier = Modifier.animateIcon({ checkedProgress })
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = LocalBottomNavClearance.current)
                        .offset(x = fabMenuOffsetX, y = 16.dp)
                ) {
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            projectEditor = projectsViewModel.startCreate()
                        },
                        text = { Text(stringResource(Res.string.projects_new)) },
                        icon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) }
                    )
                    FloatingActionButtonMenuItem(
                        onClick = {
                            fabMenuExpanded = false
                            showAgentPicker = true
                        },
                        text = { Text(stringResource(Res.string.conversations_new)) },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) }
                    )
                }
            }
        ) { innerPadding ->
            if (projects.isEmpty() && unprojectedCount == 0) {
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
                    contentPadding = PaddingValues(bottom = LocalBottomNavClearance.current)
                ) {
                    projectSection(
                        projects = projects,
                        conversationCounts = projectCounts,
                        onProjectClick = onProjectClick,
                        onProjectEditClick = { projectId ->
                            projects.firstOrNull { it.id == projectId }?.let {
                                projectEditor = projectsViewModel.startEdit(it)
                            }
                        },
                        onProjectDeleteClick = { deleteProjectId = it },
                        enableContextMenu = enableContextMenu,
                        unprojectedConversationCount = unprojectedCount,
                        onUnprojectedClick = onUnprojectedClick
                    )
                    recentChatSection(
                        conversations = recentConversations,
                        avatars = agentAvatars,
                        selectedConversationId = selectedConversationId,
                        onClick = onConversationClick
                    )
                }
            }

            if (showAgentPicker) {
                SingleChoiceDialog(
                    title = stringResource(Res.string.conversations_select_agent_title),
                    items = selectableAgents,
                    initialSelectedId = currentAgent?.id ?: selectableAgents.firstOrNull()?.id,
                    itemId = { it.id },
                    itemLabel = { agentNames[it.id] ?: it.name },
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

            projectEditor?.let { editor ->
                ProjectEditorDialog(
                    title = stringResource(
                        if (editor.id == null) Res.string.projects_create_title
                        else Res.string.projects_edit_title
                    ),
                    state = editor,
                    onNameChange = { projectEditor = projectsViewModel.nameChanged(editor, it) },
                    onWorkspaceChange = { projectEditor = projectsViewModel.workspaceChanged(editor, it) },
                    // 文件夹选择依赖平台实现；未接入的平台保持手动输入。
                    onPickWorkspace = {},
                    onConfirm = {
                        projectsViewModel.save(editor)
                        projectEditor = null
                    },
                    onDismiss = { projectEditor = null }
                )
            }

            if (deleteProjectId != null) {
                ConfirmationDialog(
                    title = stringResource(Res.string.projects_delete_title),
                    text = stringResource(Res.string.projects_delete_confirm),
                    confirmButtonText = stringResource(Res.string.action_delete),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = {
                        deleteProjectId?.let { projectsViewModel.delete(it) }
                        deleteProjectId = null
                    },
                    onDismiss = { deleteProjectId = null }
                )
            }

            // 菜单展开时点击空白处收起。Scaffold 的 FAB 槽位最后绘制，因此这层
            // 覆盖物位于列表之上、FAB 菜单之下。
            if (fabMenuExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { fabMenuExpanded = false }
                        }
                )
            }
        }
    }
}
