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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.agents_select_to_edit
import org.jetbrains.compose.resources.stringResource

/**
 * Material 3 List-Detail layout for Desktop / large windows.
 *
 * Left pane  : `AgentsScreen` (list, fixed ~360 dp wide).
 * Right pane : `AgentEditScreen` for the currently selected Agent, or an
 *              empty placeholder prompting the user to pick an Agent to edit.
 *
 * The Agent list stays visible while editing — no back-stack push.
 * Mirrors `ConversationsDualPaneScreen` for the Agents surface.
 *
 * Selection state is `rememberSaveable` so it survives rail navigation
 * to Conversations / Settings and back.
 */
@Composable
fun AgentsDualPaneScreen(
    onOpenAgentEdit: (String) -> Unit,
    onSelectCurrentAgent: (String) -> Unit,
    onMarketClick: () -> Unit,
    onPickProvider: (String?) -> Unit = {},
    pickedModelId: String? = null,
    onPickedModelConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedAgentId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingNewAgent by rememberSaveable { mutableStateOf(false) }

    Row(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxHeight()
                .width(360.dp)
        ) {
            AgentsScreen(
                onAddClick = { creatingNewAgent = true },
                onMarketClick = onMarketClick,
                onEditClick = { id ->
                    creatingNewAgent = false
                    selectedAgentId = id
                    onOpenAgentEdit(id)
                },
                onAgentClick = { id ->
                    creatingNewAgent = false
                    onSelectCurrentAgent(id)
                    selectedAgentId = id
                }
            )
        }

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
        ) {
            if (creatingNewAgent) {
                // 新建 Agent：右栏打开空白编辑器，agentId = null。
                key("new-agent") {
                    AgentEditScreen(
                        agentId = null,
                        onBackClick = { creatingNewAgent = false },
                        onSaved = { creatingNewAgent = false },
                        onPickProvider = onPickProvider,
                        pickedModelId = pickedModelId,
                        onPickedModelConsumed = onPickedModelConsumed
                    )
                }
            } else {
                val agentId = selectedAgentId
                if (agentId == null) {
                    EmptyState(
                        icon = Icons.Default.SmartToy,
                        message = stringResource(Res.string.agents_select_to_edit),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 用 agentId 作 key，切换 Agent 时销毁旧 ViewModel 子树并重建，
                    // 否则 viewModel() 会按 composable 位置缓存第一次创建的 ViewModel，
                    // 导致双栏下点击任意 Agent 始终显示首个 Agent 的内容。
                    key(agentId) {
                        AgentEditScreen(
                            agentId = agentId,
                            onBackClick = { selectedAgentId = null },
                            onSaved = { /* 保持在右栏，不清空 selectedAgentId */ },
                            onPickProvider = onPickProvider,
                            pickedModelId = pickedModelId,
                            onPickedModelConsumed = onPickedModelConsumed
                        )
                    }
                }
            }
        }
    }
}
