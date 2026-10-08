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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.data.cloud.CloudMarketAgent
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.viewmodel.AgentMarketDetailViewModel
import cc.ptoe.messenger.presentation.viewmodel.AgentMarketViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.presentation.utils.formatOneDecimal
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_load_more
import cc.ptoe.messenger.generated.resources.action_refresh
import cc.ptoe.messenger.generated.resources.action_retry
import cc.ptoe.messenger.generated.resources.agent_edit_max_tokens_placeholder
import cc.ptoe.messenger.generated.resources.agent_edit_reasoning_effort_default
import cc.ptoe.messenger.generated.resources.agent_market_detail_title
import cc.ptoe.messenger.generated.resources.agent_market_empty
import cc.ptoe.messenger.generated.resources.agent_market_import_agent
import cc.ptoe.messenger.generated.resources.agent_market_import_button
import cc.ptoe.messenger.generated.resources.agent_market_import_failed
import cc.ptoe.messenger.generated.resources.agent_market_importing
import cc.ptoe.messenger.generated.resources.agent_market_max_tokens
import cc.ptoe.messenger.generated.resources.agent_market_search_label
import cc.ptoe.messenger.generated.resources.agent_market_system_prompt
import cc.ptoe.messenger.generated.resources.agent_market_temperature
import cc.ptoe.messenger.generated.resources.agent_market_thinking
import cc.ptoe.messenger.generated.resources.agent_market_title
import cc.ptoe.messenger.generated.resources.agents_no_system_prompt
import cc.ptoe.messenger.generated.resources.error_agent_not_found
import cc.ptoe.messenger.generated.resources.error_load_failed
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMarketScreen(
    onBackClick: () -> Unit,
    onAgentClick: (String) -> Unit,
    onImported: () -> Unit,
    viewModel: AgentMarketViewModel = viewModel(
        factory = AgentMarketViewModel.provideFactory(AppContainerHolder.instance.cloud)
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        if (query.isNotEmpty()) {
            delay(350)
            viewModel.refresh(query)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.agent_market_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh(query) }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(Res.string.action_refresh))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (it.isEmpty()) viewModel.refresh()
                },
                singleLine = true,
                label = { Text(stringResource(Res.string.agent_market_search_label)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            when {
                uiState.isLoading && uiState.agents.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) { CircularProgressIndicator() }
                uiState.error != null && uiState.agents.isEmpty() -> EmptyState(
                    icon = Icons.Default.Storefront,
                    message = uiState.error ?: stringResource(Res.string.error_load_failed),
                    actionLabel = stringResource(Res.string.action_retry),
                    onActionClick = { viewModel.refresh(query) }
                )
                uiState.agents.isEmpty() -> EmptyState(
                    icon = Icons.Default.Storefront,
                    message = stringResource(Res.string.agent_market_empty)
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.agents, key = { it.id }) { agent ->
                        val strImportFailed = stringResource(Res.string.agent_market_import_failed)
                        MarketAgentListItem(
                            agent = agent,
                            onClick = { onAgentClick(agent.id) },
                            isImporting = uiState.importingAgentId == agent.id,
                            onImportClick = {
                                viewModel.importAgent(agent.id) { result ->
                                    scope.launch {
                                        result.onSuccess { onImported() }.onFailure {
                                            snackbarHostState.showSnackbar(it.message ?: strImportFailed)
                                        }
                                    }
                                }
                            }
                        )
                    }
                    if (uiState.nextCursor != null) {
                        item {
                            OutlinedButton(
                                onClick = viewModel::loadMore,
                                enabled = !uiState.isLoadingMore,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                if (uiState.isLoadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(stringResource(Res.string.action_load_more))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketAgentListItem(
    agent: CloudMarketAgent,
    onClick: () -> Unit,
    isImporting: Boolean,
    onImportClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AgentAvatar(avatar = agent.avatarUrl, allowRemote = true, size = 48.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(agent.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                agent.systemPrompt.ifBlank { stringResource(Res.string.agents_no_system_prompt) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedButton(
            onClick = onImportClick,
            enabled = !isImporting,
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            if (isImporting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Download, contentDescription = null)
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.agent_market_import_button))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMarketDetailScreen(
    marketAgentId: String,
    onBackClick: () -> Unit,
    onImported: () -> Unit,
    viewModel: AgentMarketDetailViewModel = viewModel(
        factory = AgentMarketDetailViewModel.provideFactory(
            AppContainerHolder.instance.cloud,
            marketAgentId
        )
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val strImportFailed = stringResource(Res.string.agent_market_import_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.agent_market_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        when {
            uiState.isLoading -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { CircularProgressIndicator() }
            uiState.agent == null -> EmptyState(
                icon = Icons.Default.Storefront,
                message = uiState.error ?: stringResource(Res.string.error_agent_not_found),
                modifier = Modifier.padding(innerPadding),
                actionLabel = stringResource(Res.string.action_retry),
                onActionClick = viewModel::load
            )
            else -> {
                val agent = requireNotNull(uiState.agent)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgentAvatar(avatar = agent.avatarUrl, allowRemote = true, size = 72.dp)
                        Spacer(Modifier.width(16.dp))
                        Text(agent.name, style = MaterialTheme.typography.headlineSmall)
                    }
                    Spacer(Modifier.height(24.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.agent_market_system_prompt)) },
                        supportingContent = {
                            Text(agent.systemPrompt.ifBlank { stringResource(Res.string.agents_no_system_prompt) })
                        }
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.agent_market_temperature)) },
                        supportingContent = {
                            Text(formatOneDecimal(agent.temperature))
                        }
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.agent_market_max_tokens)) },
                        supportingContent = { Text(agent.maxTokens?.toString() ?: stringResource(Res.string.agent_edit_max_tokens_placeholder)) }
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(Res.string.agent_market_thinking)) },
                        supportingContent = {
                            Text(agent.reasoningEffort?.replaceFirstChar { it.uppercase() } ?: stringResource(Res.string.agent_edit_reasoning_effort_default))
                        }
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            viewModel.importAgent { result ->
                                scope.launch {
                                    result.onSuccess { onImported() }.onFailure {
                                        snackbarHostState.showSnackbar(it.message ?: strImportFailed)
                                    }
                                }
                            }
                        },
                        enabled = !uiState.isImporting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (uiState.isImporting) stringResource(Res.string.agent_market_importing) else stringResource(Res.string.agent_market_import_agent))
                    }
                }
            }
        }
    }
}
