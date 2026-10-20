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

package cc.ptoe.messenger.presentation.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.data.cloud.CloudLoginOutcome
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.sync_choice_message
import cc.ptoe.messenger.generated.resources.sync_choice_title
import cc.ptoe.messenger.generated.resources.sync_choice_use_cloud
import cc.ptoe.messenger.generated.resources.sync_choice_use_local
import cc.ptoe.messenger.generated.resources.sync_choice_warning
import org.jetbrains.compose.resources.stringResource

@Composable
fun CloudSyncChoiceDialog(
    outcome: CloudLoginOutcome,
    onUseLocal: () -> Unit,
    onUseCloud: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sync_choice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.sync_choice_message))
                Text(
                    stringResource(Res.string.sync_choice_warning),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onUseLocal) { Text(stringResource(Res.string.sync_choice_use_local)) }
        },
        dismissButton = {
            TextButton(onClick = onUseCloud) { Text(stringResource(Res.string.sync_choice_use_cloud)) }
        }
    )
}
