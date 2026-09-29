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

package cc.ptoe.messenger.presentation.ui.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.presentation.ui.components.ConfirmationDialog
import cc.ptoe.messenger.presentation.ui.components.CursorDropdownMenu
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.ui.components.onContextMenu
import cc.ptoe.messenger.presentation.ui.components.rememberContextMenuState
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor
import cc.ptoe.messenger.presentation.viewmodel.ProvidersViewModel
import cc.ptoe.messenger.presentation.viewmodel.ProviderWithModelCount
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_add
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_delete
import cc.ptoe.messenger.generated.resources.action_edit
import cc.ptoe.messenger.generated.resources.action_more
import cc.ptoe.messenger.generated.resources.providers_delete_confirm
import cc.ptoe.messenger.generated.resources.providers_delete_title
import cc.ptoe.messenger.generated.resources.providers_empty
import cc.ptoe.messenger.generated.resources.providers_model_count
import cc.ptoe.messenger.generated.resources.providers_title
import cc.ptoe.messenger.generated.resources.providers_view_models
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.data.cloud.BUILTIN_PROVIDER_ID
import cc.ptoe.messenger.di.AppContainerHolder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProvidersScreen(
    onBackClick: () -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (String) -> Unit,
    onProviderClick: (String) -> Unit,
    viewModel: ProvidersViewModel = viewModel(
        factory = ProvidersViewModel.provideFactory(
            providerRepository = AppContainerHolder.instance.providerRepository,
            modelRepository = AppContainerHolder.instance.modelRepository
        )
    )
) {
    val providers by viewModel.providersWithModelCount.collectAsStateWithLifecycle(
        initialValue = emptyList()
    )

    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val sizeClass = windowSizeClassFor(maxWidth)
        val enableContextMenu = sizeClass != WindowSizeClass.Compact

        Scaffold(
            topBar = {
                LargeFlexibleTopAppBar(
                    title = { Text(text = stringResource(Res.string.providers_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.action_back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onAddClick) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(Res.string.action_add))
                }
            },
            modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            if (providers.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Cloud,
                    message = stringResource(Res.string.providers_empty),
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
                        items(providers, key = { it.provider.id }) { item ->
                            ProviderListItem(
                                item = item,
                                onClick = { onProviderClick(item.provider.id) },
                                onEditClick = { onEditClick(item.provider.id) },
                                onDeleteClick = { showDeleteDialog = item.provider.id },
                                enableContextMenu = enableContextMenu
                            )
                        }
                    }
                }
            }

            if (showDeleteDialog != null) {
                ConfirmationDialog(
                    title = stringResource(Res.string.providers_delete_title),
                    text = stringResource(Res.string.providers_delete_confirm),
                    confirmButtonText = stringResource(Res.string.action_delete),
                    dismissButtonText = stringResource(Res.string.action_cancel),
                    onConfirm = {
                        showDeleteDialog?.let { id ->
                            viewModel.deleteProvider(id)
                        }
                        showDeleteDialog = null
                    },
                    onDismiss = { showDeleteDialog = null }
                )
            }
        }
    }
}

@Composable
private fun ProviderListItem(
    item: ProviderWithModelCount,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    enableContextMenu: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val contextMenuState = rememberContextMenuState()
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // 内置云 AI 服务商由登录流程自动配置，不提供编辑与删除入口。
    val isBuiltin = item.provider.id == BUILTIN_PROVIDER_ID
    val backgroundColor = when {
        hovered && enableContextMenu -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(backgroundColor)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .then(
                if (enableContextMenu) Modifier.onContextMenu(contextMenuState)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Cloud,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = item.provider.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.provider.baseUrl,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(Res.string.providers_model_count, item.modelCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
                    text = { Text(stringResource(Res.string.providers_view_models)) },
                    onClick = {
                        expanded = false
                        onClick()
                    }
                )
                if (!isBuiltin) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_edit)) },
                        onClick = {
                            expanded = false
                            onEditClick()
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
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(Res.string.providers_view_models),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (enableContextMenu) {
            CursorDropdownMenu(
                state = contextMenuState,
                onDismiss = {}
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.providers_view_models)) },
                    onClick = {
                        contextMenuState.hide()
                        onClick()
                    }
                )
                if (!isBuiltin) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.action_edit)) },
                        onClick = {
                            contextMenuState.hide()
                            onEditClick()
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
}
