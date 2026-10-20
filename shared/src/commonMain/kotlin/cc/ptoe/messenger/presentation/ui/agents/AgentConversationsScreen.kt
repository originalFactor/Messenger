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

package cc.ptoe.messenger.presentation.ui.agents

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.agent_conversations_empty
import cc.ptoe.messenger.generated.resources.agent_edit_title_edit
import cc.ptoe.messenger.generated.resources.conversations_new
import cc.ptoe.messenger.generated.resources.conversations_title
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.ui.components.projectSection
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.displayName
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor
import cc.ptoe.messenger.presentation.viewmodel.AgentConversationsViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * 某个 Agent 的聊天列表：Agent 页点击 Agent 进入的子页。
 *
 * 与主页一致，这里只展示文件夹（该 Agent 的项目 + “无项目”）；点击文件夹
 * 进入二级页看该文件夹里该 Agent 的会话。双栏布局下托管在 Agents 双栏
 * 右栏（隐藏返回键）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConversationsScreen(
    agentId: String,
    onBackClick: () -> Unit,
    onProjectClick: (String) -> Unit = {},
    onUnprojectedClick: () -> Unit = {},
    onEditClick: () -> Unit,
    onConversationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    showBackButton: Boolean = true,
    viewModel: AgentConversationsViewModel = viewModel(
        factory = AgentConversationsViewModel.provideFactory(
            agentId = agentId,
            agentRepository = AppContainerHolder.instance.agentRepository,
            conversationRepository = AppContainerHolder.instance.conversationRepository,
            modelRepository = AppContainerHolder.instance.modelRepository,
            currentAgentRepository = AppContainerHolder.instance.currentAgentRepository,
            projectRepository = AppContainerHolder.instance.projectRepository
        )
    )
) {
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val projectCounts by viewModel.projectConversationCounts.collectAsStateWithLifecycle()
    val unprojectedCount by viewModel.unprojectedConversationCount.collectAsStateWithLifecycle()

    // 进入列表页时触发一次空会话清理（规则在 Rust core）。
    LaunchedEffect(Unit) {
        viewModel.sweepEmptyConversations()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val enableContextMenu = windowSizeClassFor(maxWidth) != WindowSizeClass.Compact

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = agent?.displayName() ?: stringResource(Res.string.conversations_title),
                            maxLines = 1
                        )
                    },
                    navigationIcon = {
                        if (showBackButton) {
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(Res.string.action_back)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onEditClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(Res.string.agent_edit_title_edit)
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
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
        ) { innerPadding ->
            if (projects.isEmpty() && unprojectedCount == 0) {
                EmptyState(
                    icon = Icons.Default.ChatBubbleOutline,
                    message = stringResource(Res.string.agent_conversations_empty),
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
                        .navigationBarsPadding()
                ) {
                    projectSection(
                        projects = projects,
                        conversationCounts = projectCounts,
                        onProjectClick = onProjectClick,
                        enableContextMenu = enableContextMenu,
                        unprojectedConversationCount = unprojectedCount,
                        onUnprojectedClick = onUnprojectedClick
                    )
                }
            }
        }
    }
}
