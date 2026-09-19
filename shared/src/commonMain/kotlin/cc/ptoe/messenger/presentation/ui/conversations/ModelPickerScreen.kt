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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.conversation_settings_no_model
import cc.ptoe.messenger.generated.resources.conversation_settings_pick_model_title
import cc.ptoe.messenger.generated.resources.conversation_settings_search
import cc.ptoe.messenger.generated.resources.conversation_settings_search_empty
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerScreen(
    providerId: String,
    selectedModelId: String?,
    onBackClick: () -> Unit,
    onModelSelected: (String) -> Unit,
    modelRepository: ModelRepository = AppContainerHolder.instance.modelRepository,
    providerRepository: ProviderRepository = AppContainerHolder.instance.providerRepository
) {
    val models by remember(modelRepository, providerId) { modelRepository.getEnabledByProviderId(providerId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val provider by remember(providerRepository, providerId) { providerRepository.getById(providerId) }
        .collectAsStateWithLifecycle(initialValue = null)

    var query by remember { mutableStateOf("") }
    val searchHint = stringResource(Res.string.conversation_settings_search)
    val filtered = remember(models, query) {
        if (query.isBlank()) {
            models
        } else {
            models.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.modelId.contains(query, ignoreCase = true)
            }
        }
    }

    val baseTitle = stringResource(Res.string.conversation_settings_pick_model_title)
    val providerName = provider?.name
    val title = if (providerName.isNullOrBlank()) baseTitle else "$baseTitle · $providerName"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(searchHint) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            when {
                models.isEmpty() -> EmptyState(
                    icon = Icons.Default.Search,
                    message = stringResource(Res.string.conversation_settings_no_model)
                )
                filtered.isEmpty() -> EmptyState(
                    icon = Icons.Default.Search,
                    message = stringResource(Res.string.conversation_settings_search_empty)
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { model ->
                        ListItem(
                            headlineContent = { Text(model.displayName) },
                            supportingContent = {
                                if (model.displayName != model.modelId) {
                                    Text(
                                        text = model.modelId,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            trailingContent = {
                                if (model.id == selectedModelId) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onModelSelected(model.id) }
                        )
                    }
                }
            }
        }
    }
}