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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.data.cloud.CloudLoginOutcome
import cc.ptoe.messenger.data.cloud.CloudQuotaEntitlement
import cc.ptoe.messenger.data.cloud.CloudSyncRepository
import cc.ptoe.messenger.presentation.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_login
import cc.ptoe.messenger.generated.resources.action_save
import cc.ptoe.messenger.generated.resources.cloud_server_title
import cc.ptoe.messenger.generated.resources.cloud_server_edit_hint
import cc.ptoe.messenger.generated.resources.cloud_settings_account
import cc.ptoe.messenger.generated.resources.cloud_settings_account_deleted
import cc.ptoe.messenger.generated.resources.cloud_settings_account_security
import cc.ptoe.messenger.generated.resources.cloud_settings_account_sync
import cc.ptoe.messenger.generated.resources.cloud_settings_change_password
import cc.ptoe.messenger.generated.resources.cloud_settings_change_password_title
import cc.ptoe.messenger.generated.resources.cloud_settings_cloud_data_restored
import cc.ptoe.messenger.generated.resources.cloud_settings_connected
import cc.ptoe.messenger.generated.resources.cloud_settings_create_account
import cc.ptoe.messenger.generated.resources.cloud_settings_create_account_desc
import cc.ptoe.messenger.generated.resources.cloud_settings_current_password
import cc.ptoe.messenger.generated.resources.cloud_settings_current_password_label
import cc.ptoe.messenger.generated.resources.cloud_settings_delete_account
import cc.ptoe.messenger.generated.resources.cloud_settings_delete_account_confirm
import cc.ptoe.messenger.generated.resources.cloud_settings_delete_account_desc
import cc.ptoe.messenger.generated.resources.cloud_settings_delete_account_title
import cc.ptoe.messenger.generated.resources.cloud_settings_email_label
import cc.ptoe.messenger.generated.resources.cloud_settings_have_account
import cc.ptoe.messenger.generated.resources.cloud_settings_local_data_uploaded
import cc.ptoe.messenger.generated.resources.cloud_settings_login_account
import cc.ptoe.messenger.generated.resources.cloud_settings_login_account_desc
import cc.ptoe.messenger.generated.resources.cloud_settings_login_failed
import cc.ptoe.messenger.generated.resources.cloud_settings_login_success
import cc.ptoe.messenger.generated.resources.cloud_settings_logout
import cc.ptoe.messenger.generated.resources.cloud_settings_logout_success
import cc.ptoe.messenger.generated.resources.cloud_settings_new_password_label
import cc.ptoe.messenger.generated.resources.cloud_settings_no_account
import cc.ptoe.messenger.generated.resources.cloud_settings_operation_failed
import cc.ptoe.messenger.generated.resources.cloud_settings_password_label
import cc.ptoe.messenger.generated.resources.cloud_settings_password_min_length
import cc.ptoe.messenger.generated.resources.cloud_settings_password_updated
import cc.ptoe.messenger.generated.resources.cloud_settings_permanently_delete
import cc.ptoe.messenger.generated.resources.cloud_settings_permanently_delete_button
import cc.ptoe.messenger.generated.resources.cloud_settings_plan_balance_label
import cc.ptoe.messenger.generated.resources.cloud_settings_plan_empty
import cc.ptoe.messenger.generated.resources.cloud_settings_plan_expires_label
import cc.ptoe.messenger.generated.resources.cloud_settings_plan_no_expiry
import cc.ptoe.messenger.generated.resources.cloud_settings_plan_source_admin
import cc.ptoe.messenger.generated.resources.cloud_settings_plan_source_card
import cc.ptoe.messenger.generated.resources.cloud_settings_plan_source_migrated
import cc.ptoe.messenger.generated.resources.cloud_settings_plan_title
import cc.ptoe.messenger.generated.resources.cloud_settings_register_and_login
import cc.ptoe.messenger.generated.resources.cloud_settings_restore
import cc.ptoe.messenger.generated.resources.cloud_settings_server
import cc.ptoe.messenger.generated.resources.cloud_settings_server_desc
import cc.ptoe.messenger.generated.resources.cloud_settings_sync_data
import cc.ptoe.messenger.generated.resources.cloud_settings_sync_data_desc
import cc.ptoe.messenger.generated.resources.cloud_settings_sync_desc
import cc.ptoe.messenger.generated.resources.cloud_settings_title
import cc.ptoe.messenger.generated.resources.cloud_settings_upload_sync
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.presentation.platform.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSettingsScreen(
    onBackClick: () -> Unit,
    cloudSyncRepository: CloudSyncRepository,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.provideFactory(
            AppContainerHolder.instance.themePreferences,
            AppContainerHolder.instance.appPreferences,
            cloudSyncRepository
        )
    )
) {
    val user by viewModel.cloudUser.collectAsStateWithLifecycle()
    val savedServerUrl by viewModel.cloudServerUrl.collectAsStateWithLifecycle()
    var showServerPage by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var register by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var deletePassword by remember { mutableStateOf("") }
    var pendingLogin by remember { mutableStateOf<CloudLoginOutcome?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    val loginSuccess = stringResource(Res.string.cloud_settings_login_success)
    val localDataUploaded = stringResource(Res.string.cloud_settings_local_data_uploaded)
    val cloudDataRestored = stringResource(Res.string.cloud_settings_cloud_data_restored)
    val loginFailed = stringResource(Res.string.cloud_settings_login_failed)
    val operationFailed = stringResource(Res.string.cloud_settings_operation_failed)
    val logoutSuccess = stringResource(Res.string.cloud_settings_logout_success)
    val passwordUpdated = stringResource(Res.string.cloud_settings_password_updated)
    val accountDeleted = stringResource(Res.string.cloud_settings_account_deleted)

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    fun showMessage(message: String) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    fun notify(result: Result<*>, success: String, close: Boolean = false) {
        result.onSuccess {
            isBusy = false
            showMessage(success)
            if (close) onBackClick()
        }.onFailure {
            isBusy = false
            showMessage(it.message ?: operationFailed)
        }
    }

    fun completeLogin(
        outcome: CloudLoginOutcome,
        useLocalData: Boolean,
        successMessage: String
    ) {
        viewModel.completeLogin(outcome, useLocalData) { result ->
            notify(result, successMessage, close = true)
        }
    }

    if (showServerPage) {
        // 子页面是页内状态切换（无导航栈条目），拦截系统返回，避免直接退出 Cloud 设置页。
        BackHandler(enabled = true) { showServerPage = false }
        CloudServerScreen(
            onBackClick = { showServerPage = false },
            viewModel = viewModel
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.cloud_settings_title)) },
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
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(Res.string.cloud_settings_account_sync),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = stringResource(Res.string.cloud_settings_sync_desc),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                CloudSectionCard(
                    title = stringResource(Res.string.cloud_settings_server),
                    icon = Icons.Default.Cloud
                ) {
                    Text(
                        text = stringResource(Res.string.cloud_settings_server_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isBusy) { showServerPage = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = savedServerUrl,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(Res.string.cloud_server_edit_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(Res.string.cloud_server_title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (user == null) {
                    CloudSectionCard(
                        title = stringResource(if (register) Res.string.cloud_settings_create_account else Res.string.cloud_settings_login_account),
                        icon = Icons.Default.Lock
                    ) {
                        Text(
                            text = stringResource(if (register) Res.string.cloud_settings_create_account_desc else Res.string.cloud_settings_login_account_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text(stringResource(Res.string.cloud_settings_email_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isBusy
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(Res.string.cloud_settings_password_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isBusy,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Button(
                            onClick = {
                                isBusy = true
                                val normalizedServerUrl = savedServerUrl.trim()
                                val callback: (Result<CloudLoginOutcome>) -> Unit = { result ->
                                    result.onSuccess { outcome ->
                                        when {
                                            !outcome.hasLocalData -> {
                                                completeLogin(outcome, useLocalData = false, loginSuccess)
                                            }
                                            outcome.cloudVersion == 0L -> {
                                                completeLogin(outcome, useLocalData = true, localDataUploaded)
                                            }
                                            else -> {
                                                isBusy = false
                                                pendingLogin = outcome
                                            }
                                        }
                                    }.onFailure {
                                        isBusy = false
                                        showMessage(it.message ?: loginFailed)
                                    }
                                }
                                if (register) {
                                    viewModel.register(email.trim(), password, normalizedServerUrl, callback)
                                } else {
                                    viewModel.login(email.trim(), password, normalizedServerUrl, callback)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBusy &&
                                email.isNotBlank() &&
                                password.isNotBlank() &&
                                savedServerUrl.isNotBlank()
                        ) {
                            Text(if (register) stringResource(Res.string.cloud_settings_register_and_login) else stringResource(Res.string.action_login))
                        }
                        if (isBusy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            TextButton(
                                onClick = { register = !register },
                                enabled = !isBusy
                            ) {
                                Text(if (register) stringResource(Res.string.cloud_settings_have_account) else stringResource(Res.string.cloud_settings_no_account))
                            }
                        }
                    }
                } else {
                    CloudSectionCard(
                        title = stringResource(Res.string.cloud_settings_account),
                        icon = Icons.Default.Cloud
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CloudIconContainer(icon = Icons.Default.Cloud)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = user!!.email,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(Res.string.cloud_settings_connected),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    CloudSectionCard(
                        title = stringResource(Res.string.cloud_settings_plan_title),
                        icon = Icons.Default.WorkspacePremium
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = formatQuota(user!!.quotaBalance ?: 0L),
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Text(
                                    text = stringResource(Res.string.cloud_settings_plan_balance_label),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = stringResource(Res.string.cloud_settings_plan_expires_label),
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = user!!.quotaExpiresAt?.let { formatQuotaExpiry(it) }
                                        ?: stringResource(Res.string.cloud_settings_plan_no_expiry),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        val entitlements = user!!.quotaEntitlements
                        if (entitlements.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.cloud_settings_plan_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            HorizontalDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                entitlements.forEach { entitlement ->
                                    PlanEntitlementRow(entitlement)
                                }
                            }
                        }
                    }

                    CloudSectionCard(
                        title = stringResource(Res.string.cloud_settings_sync_data),
                        icon = Icons.Default.Sync
                    ) {
                        Text(
                            text = stringResource(Res.string.cloud_settings_sync_data_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                isBusy = true
                                viewModel.upload { notify(it, localDataUploaded) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBusy
                        ) {
                            Text(stringResource(Res.string.cloud_settings_upload_sync))
                        }
                        FilledTonalButton(
                            onClick = {
                                isBusy = true
                                viewModel.restore { notify(it, cloudDataRestored) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBusy
                        ) {
                            Text(stringResource(Res.string.cloud_settings_restore))
                        }
                        if (isBusy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }

                    CloudSectionCard(
                        title = stringResource(Res.string.cloud_settings_account_security),
                        icon = Icons.Default.Lock
                    ) {
                        OutlinedButton(
                            onClick = { showPasswordDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBusy
                        ) {
                            Text(stringResource(Res.string.cloud_settings_change_password))
                        }
                        TextButton(
                            onClick = { viewModel.logout { notify(it, logoutSuccess) } },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBusy
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.cloud_settings_logout))
                        }
                        androidx.compose.material3.HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(Res.string.cloud_settings_delete_account),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = stringResource(Res.string.cloud_settings_delete_account_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isBusy
                        ) {
                            Icon(Icons.Default.DeleteForever, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.cloud_settings_permanently_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBusy) showPasswordDialog = false },
            title = { Text(stringResource(Res.string.cloud_settings_change_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = { currentPassword = it },
                        label = { Text(stringResource(Res.string.cloud_settings_current_password_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isBusy,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text(stringResource(Res.string.cloud_settings_new_password_label)) },
                        supportingText = { Text(stringResource(Res.string.cloud_settings_password_min_length)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isBusy,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isBusy = true
                        viewModel.changePassword(currentPassword, newPassword) { result ->
                            notify(result, passwordUpdated)
                            if (result.isSuccess) {
                                currentPassword = ""
                                newPassword = ""
                                showPasswordDialog = false
                            }
                        }
                    },
                    enabled = !isBusy && currentPassword.isNotBlank() && newPassword.length >= 8
                ) {
                    Text(stringResource(Res.string.action_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPasswordDialog = false },
                    enabled = !isBusy
                ) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isBusy) showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text(stringResource(Res.string.cloud_settings_delete_account_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(Res.string.cloud_settings_delete_account_confirm))
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        label = { Text(stringResource(Res.string.cloud_settings_current_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isBusy,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isBusy = true
                        viewModel.deleteAccount(deletePassword) { result ->
                            notify(result, accountDeleted, close = true)
                            if (result.isSuccess) {
                                deletePassword = ""
                                showDeleteDialog = false
                            }
                        }
                    },
                    enabled = !isBusy && deletePassword.isNotBlank()
                ) {
                    Text(stringResource(Res.string.cloud_settings_permanently_delete_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    enabled = !isBusy
                ) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }

    pendingLogin?.let { outcome ->
        CloudSyncChoiceDialog(
            outcome = outcome,
            onUseLocal = {
                pendingLogin = null
                isBusy = true
                completeLogin(outcome, useLocalData = true, localDataUploaded)
            },
            onUseCloud = {
                pendingLogin = null
                isBusy = true
                completeLogin(outcome, useLocalData = false, cloudDataRestored)
            },
            onDismiss = { pendingLogin = null }
        )
    }
}

@Composable
private fun CloudSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CloudIconContainer(icon = icon)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                content()
            }
        )
    }
}

@Composable
private fun CloudIconContainer(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null)
        }
    }
}

/** 千分位分组展示额度 token 数。 */
private fun formatQuota(value: Long): String {
    val text = value.toString()
    if (text.length <= 3) return text
    return text.reversed().chunked(3).joinToString(",").reversed()
}

/** 到期日期统一展示为 ISO 日期（yyyy-MM-dd），避免内嵌硬编码中文。 */
private fun formatQuotaExpiry(timestamp: Long): String =
    Instant.fromEpochMilliseconds(timestamp)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .date
        .toString()

@Composable
private fun entitlementLabel(entitlement: CloudQuotaEntitlement): String =
    entitlement.planName?.takeIf { it.isNotBlank() } ?: when (entitlement.source) {
        "card" -> stringResource(Res.string.cloud_settings_plan_source_card)
        "admin" -> stringResource(Res.string.cloud_settings_plan_source_admin)
        else -> stringResource(Res.string.cloud_settings_plan_source_migrated)
    }

@Composable
private fun PlanEntitlementRow(entitlement: CloudQuotaEntitlement) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = entitlementLabel(entitlement),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = formatQuota(entitlement.balance) + " · " +
                (entitlement.expiresAt?.let { formatQuotaExpiry(it) }
                    ?: stringResource(Res.string.cloud_settings_plan_no_expiry)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
