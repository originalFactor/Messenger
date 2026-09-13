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

package cc.ptoe.messenger.presentation.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.ptoe.messenger.data.cloud.DEFAULT_CLOUD_SERVER_URL
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_save
import cc.ptoe.messenger.generated.resources.cloud_server_saved
import cc.ptoe.messenger.generated.resources.cloud_server_title
import cc.ptoe.messenger.generated.resources.cloud_settings_operation_failed
import cc.ptoe.messenger.generated.resources.cloud_settings_server_desc
import cc.ptoe.messenger.generated.resources.cloud_settings_server_url_label
import cc.ptoe.messenger.generated.resources.cloud_settings_use_default_server
import cc.ptoe.messenger.presentation.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Messenger Cloud 的二级页面：编辑云实例地址。从 CloudSettingsScreen
 * 的服务地址入口行进入，保存成功后返回上一页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudServerScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel
) {
    val savedServerUrl by viewModel.cloudServerUrl.collectAsStateWithLifecycle()
    var serverUrl by remember(savedServerUrl) { mutableStateOf(savedServerUrl) }
    var isBusy by remember { mutableStateOf(false) }

    val saved = stringResource(Res.string.cloud_server_saved)
    val operationFailed = stringResource(Res.string.cloud_settings_operation_failed)

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.cloud_server_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(Res.string.cloud_settings_server_desc),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text(stringResource(Res.string.cloud_settings_server_url_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isBusy
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { serverUrl = DEFAULT_CLOUD_SERVER_URL },
                        enabled = !isBusy
                    ) {
                        Text(stringResource(Res.string.cloud_settings_use_default_server))
                    }
                }
                Button(
                    onClick = {
                        isBusy = true
                        viewModel.setCloudServerUrl(serverUrl.trim()) { result ->
                            isBusy = false
                            result.onSuccess {
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(saved)
                                }
                                onBackClick()
                            }.onFailure { error ->
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar(error.message ?: operationFailed)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isBusy && serverUrl.isNotBlank()
                ) {
                    Text(stringResource(Res.string.action_save))
                }
                if (isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
