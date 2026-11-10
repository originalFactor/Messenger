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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
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
import cc.ptoe.messenger.presentation.platform.BackHandler
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.providers_select_to_view
import org.jetbrains.compose.resources.stringResource

@Composable
fun ProvidersDualPaneScreen(modifier: Modifier = Modifier) {
    ProvidersNavigation(dualPane = true, onBackClick = {}, modifier = modifier)
}

/** Provider navigation inside Settings' existing detail pane. */
@Composable
fun ProvidersSinglePaneScreen(onBackClick: () -> Unit, modifier: Modifier = Modifier) {
    ProvidersNavigation(dualPane = false, onBackClick = onBackClick, modifier = modifier)
}

@Composable
private fun ProvidersNavigation(
    dualPane: Boolean,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedModelId by rememberSaveable { mutableStateOf<String?>(null) }
    var editProviderId by rememberSaveable { mutableStateOf<String?>(null) }
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var modelSavedMessage by rememberSaveable { mutableStateOf(false) }

    val navigateBack: () -> Unit = {
        when {
            isEditing -> {
                isEditing = false
                editProviderId = null
            }
            selectedModelId != null -> selectedModelId = null
            selectedProviderId != null -> selectedProviderId = null
            else -> onBackClick()
        }
    }
    BackHandler(enabled = !dualPane, onBack = navigateBack) {}

    val listContent: @Composable () -> Unit = {
        ProvidersScreen(
            onBackClick = onBackClick,
            onAddClick = {
                isEditing = true
                editProviderId = null
            },
            onEditClick = { id ->
                isEditing = true
                editProviderId = id
            },
            onProviderClick = { id ->
                selectedProviderId = id
                selectedModelId = null
                isEditing = false
            }
        )
    }
    val detailContent: @Composable () -> Unit = {
        when {
            isEditing -> key(editProviderId) {
                ProviderEditScreen(
                    providerId = editProviderId,
                    onBackClick = navigateBack,
                    onSaved = {
                        val savedId = editProviderId
                        isEditing = false
                        editProviderId = null
                        if (savedId != null) selectedProviderId = savedId
                    }
                )
            }
            selectedModelId != null -> key(selectedModelId) {
                ModelDetailScreen(
                    modelId = selectedModelId!!,
                    onBackClick = navigateBack,
                    onSaved = {
                        modelSavedMessage = true
                        selectedModelId = null
                    },
                    onDeleted = { selectedModelId = null }
                )
            }
            selectedProviderId != null -> key(selectedProviderId) {
                ProviderDetailScreen(
                    providerId = selectedProviderId!!,
                    onBackClick = navigateBack,
                    onModelClick = { model -> selectedModelId = model.id },
                    onEditProviderClick = {
                        editProviderId = selectedProviderId
                        isEditing = true
                    },
                    showModelSavedMessage = modelSavedMessage,
                    onModelSavedMessageShown = { modelSavedMessage = false }
                )
            }
            else -> EmptyState(
                icon = Icons.Default.Dns,
                message = stringResource(Res.string.providers_select_to_view),
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (dualPane) {
        Row(modifier = modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxHeight().width(360.dp),
                content = listContent
            )
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxHeight().weight(1f),
                content = detailContent
            )
        }
    } else {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
            if (isEditing || selectedProviderId != null || selectedModelId != null) detailContent()
            else listContent()
        }
    }
}
