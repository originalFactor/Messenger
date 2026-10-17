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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.domain.model.ApiFormat
import cc.ptoe.messenger.presentation.viewmodel.ProviderEditViewModel
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_hide
import cc.ptoe.messenger.generated.resources.action_save
import cc.ptoe.messenger.generated.resources.action_show
import cc.ptoe.messenger.generated.resources.providers_api_format_label
import cc.ptoe.messenger.generated.resources.providers_api_key_label
import cc.ptoe.messenger.generated.resources.providers_api_url_label
import cc.ptoe.messenger.generated.resources.providers_api_url_placeholder
import cc.ptoe.messenger.generated.resources.providers_edit_title
import cc.ptoe.messenger.generated.resources.providers_name_label
import cc.ptoe.messenger.generated.resources.providers_new_title
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(
    providerId: String? = null,
    onBackClick: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ProviderEditViewModel = viewModel(
        factory = ProviderEditViewModel.provideFactory(
            providerRepository = AppContainerHolder.instance.providerRepository,
            providerId = providerId
        )
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var apiKeyVisible by remember { mutableStateOf(false) }

    // 双栏布局下切换 Provider 时按 id 重新加载，避免依赖 ViewModel 重建
    LaunchedEffect(providerId) {
        viewModel.loadProvider(providerId)
    }

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onSaved()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.isEditing) stringResource(Res.string.providers_edit_title) else stringResource(Res.string.providers_new_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back)
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        if (viewModel.save()) {
                        }
                    }) {
                        Text(stringResource(Res.string.action_save))
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = { viewModel.onNameChange(it) },
                    label = { Text(stringResource(Res.string.providers_name_label)) },
                    isError = uiState.nameError != null,
                    supportingText = {
                        uiState.nameError?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = uiState.baseUrl,
                    onValueChange = { viewModel.onBaseUrlChange(it) },
                    label = { Text(stringResource(Res.string.providers_api_url_label)) },
                    placeholder = { Text(stringResource(Res.string.providers_api_url_placeholder)) },
                    isError = uiState.baseUrlError != null,
                    supportingText = {
                        uiState.baseUrlError?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = uiState.apiKey,
                    onValueChange = { viewModel.onApiKeyChange(it) },
                    label = { Text(stringResource(Res.string.providers_api_key_label)) },
                    isError = uiState.apiKeyError != null,
                    supportingText = {
                        uiState.apiKeyError?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector = if (apiKeyVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (apiKeyVisible) stringResource(Res.string.action_hide) else stringResource(Res.string.action_show)
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                ApiFormatDropdown(
                    selected = uiState.apiFormat,
                    onSelect = viewModel::onApiFormatChange
                )
            }
        }
    }
}

/** Protocol labels: the API name itself is the clearest description. */
private val apiFormatOptions = listOf(
    ApiFormat.CHAT to "Chat Completions",
    ApiFormat.RESPONSES to "Responses",
    ApiFormat.MESSAGES to "Messages",
    ApiFormat.INTERACTIONS to "Interactions",
    ApiFormat.AUTO to "Auto (follow request)"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiFormatDropdown(
    selected: ApiFormat,
    onSelect: (ApiFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val displayText = apiFormatOptions.find { it.first == selected }?.second
        ?: apiFormatOptions.first().second

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = { },
            readOnly = true,
            label = { Text(stringResource(Res.string.providers_api_format_label)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            apiFormatOptions.forEach { (format, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(format)
                        expanded = false
                    }
                )
            }
        }
    }
}
