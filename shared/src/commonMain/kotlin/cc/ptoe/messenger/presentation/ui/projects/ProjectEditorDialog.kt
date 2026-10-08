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

package cc.ptoe.messenger.presentation.ui.projects

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_confirm
import cc.ptoe.messenger.generated.resources.projects_name_hint
import cc.ptoe.messenger.generated.resources.projects_name_label
import cc.ptoe.messenger.generated.resources.projects_pick_workspace
import cc.ptoe.messenger.generated.resources.projects_workspace_auto
import cc.ptoe.messenger.generated.resources.projects_workspace_hint
import cc.ptoe.messenger.generated.resources.projects_workspace_label
import cc.ptoe.messenger.presentation.viewmodel.ProjectEditorState
import org.jetbrains.compose.resources.stringResource

/**
 * 项目创建/编辑对话框。
 *
 * 工作区文件夹可留空：留空时按 [ProjectEditorState.autoWorkspaceFolder]
 * （项目名称规范化后的目录名）自动创建，界面上把这个将要创建的目录名
 * 显示出来，避免用户以为工作区是空的。
 */
@Composable
fun ProjectEditorDialog(
    title: String,
    state: ProjectEditorState,
    onNameChange: (String) -> Unit,
    onWorkspaceChange: (String) -> Unit,
    onPickWorkspace: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(Res.string.projects_name_label)) },
                    placeholder = { Text(stringResource(Res.string.projects_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(Res.string.projects_workspace_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.workspace,
                    onValueChange = onWorkspaceChange,
                    label = { Text(stringResource(Res.string.projects_workspace_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(onClick = onPickWorkspace) {
                            Text(stringResource(Res.string.projects_pick_workspace))
                        }
                    },
                    supportingText = {
                        if (state.workspace.isBlank()) {
                            Text(stringResource(Res.string.projects_workspace_auto, state.autoWorkspaceFolder))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = state.canSave) {
                Text(stringResource(Res.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        }
    )
}