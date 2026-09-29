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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.presentation.platform.appVersionName
import cc.ptoe.messenger.presentation.platform.copyAvatarToInternal
import cc.ptoe.messenger.presentation.platform.deleteAvatarFile
import cc.ptoe.messenger.presentation.platform.rememberAvatarImagePicker
import cc.ptoe.messenger.presentation.platform.showPlatformToast
import cc.ptoe.messenger.presentation.theme.ThemeMode
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.ui.components.ConfirmationDialog
import cc.ptoe.messenger.presentation.ui.components.ListItem
import cc.ptoe.messenger.presentation.ui.components.SectionHeader
import cc.ptoe.messenger.presentation.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_change_avatar
import cc.ptoe.messenger.generated.resources.agent_edit_remove_avatar
import cc.ptoe.messenger.generated.resources.settings_about
import cc.ptoe.messenger.generated.resources.settings_appearance
import cc.ptoe.messenger.generated.resources.settings_avatar_desc
import cc.ptoe.messenger.generated.resources.settings_clear_data
import cc.ptoe.messenger.generated.resources.settings_clear_data_button
import cc.ptoe.messenger.generated.resources.settings_clear_data_confirm
import cc.ptoe.messenger.generated.resources.settings_clear_data_desc
import cc.ptoe.messenger.generated.resources.settings_cloud
import cc.ptoe.messenger.generated.resources.settings_cloud_not_logged_in
import cc.ptoe.messenger.generated.resources.settings_data
import cc.ptoe.messenger.generated.resources.settings_licenses
import cc.ptoe.messenger.generated.resources.settings_my_avatar
import cc.ptoe.messenger.generated.resources.settings_personal
import cc.ptoe.messenger.generated.resources.settings_providers
import cc.ptoe.messenger.generated.resources.settings_providers_desc
import cc.ptoe.messenger.generated.resources.settings_theme
import cc.ptoe.messenger.generated.resources.settings_theme_dark
import cc.ptoe.messenger.generated.resources.settings_theme_light
import cc.ptoe.messenger.generated.resources.settings_theme_system
import cc.ptoe.messenger.generated.resources.settings_title
import cc.ptoe.messenger.generated.resources.settings_version
import cc.ptoe.messenger.generated.resources.settings_version_unknown
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    onProvidersClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onCloudSettingsClick: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.provideFactory(
            themePreferences = AppContainerHolder.instance.themePreferences,
            appPreferences = AppContainerHolder.instance.appPreferences,
            cloudSyncRepository = AppContainerHolder.instance.cloudSyncRepository
        )
    )
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val userAvatar by viewModel.userAvatar.collectAsStateWithLifecycle()
    val cloudUser by viewModel.cloudUser.collectAsStateWithLifecycle()
    val cloudServerUrl by viewModel.cloudServerUrl.collectAsStateWithLifecycle()
    val cloudSyncError by viewModel.cloudSyncError.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var showThemeDialog by remember { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    LaunchedEffect(cloudSyncError) {
        cloudSyncError?.let { showPlatformToast(it) }
    }

    val avatarPicker = rememberAvatarImagePicker { croppedPath ->
        val previousAvatar = userAvatar
        coroutineScope.launch {
            val path = copyAvatarToInternal(croppedPath, "user_avatars")
            if (path != null) {
                previousAvatar?.let { deleteAvatarFile(it) }
                viewModel.setUserAvatar(path)
            }
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(text = stringResource(Res.string.settings_title)) },
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        // On desktop / large windows, constrain the settings list to a
        // centered 720 dp column so rows don't stretch across the full width.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp),
                // Keep the last setting clear of the app navigation surface when
                // the list is scrolled to the end.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
            ) {
            item {
                SectionHeader(title = stringResource(Res.string.settings_personal))
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clickable { avatarPicker.launch() },
                        contentAlignment = Alignment.Center
                    ) {
                        AgentAvatar(
                            avatar = userAvatar,
                            size = 96.dp,
                            fallbackIcon = Icons.Default.AccountCircle
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(28.dp)
                                .zIndex(1f)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = stringResource(Res.string.action_change_avatar),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }

                    Text(
                        text = stringResource(Res.string.settings_my_avatar),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = stringResource(Res.string.settings_avatar_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (userAvatar != null) {
                        TextButton(
                            onClick = {
                                userAvatar?.let { deleteAvatarFile(it) }
                                viewModel.setUserAvatar(null)
                            }
                        ) {
                            Text(stringResource(Res.string.agent_edit_remove_avatar))
                        }
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(Res.string.settings_appearance))
            }
            item {
                ListItem(
                    title = stringResource(Res.string.settings_theme),
                    subtitle = getThemeLabel(themeMode),
                    icon = Icons.Default.Palette,
                    onClick = { showThemeDialog = true }
                )
            }

            item {
                SectionHeader(title = stringResource(Res.string.settings_data))
            }
            item {
                ListItem(
                    title = stringResource(Res.string.settings_cloud),
                    subtitle = cloudUser?.email ?: stringResource(Res.string.settings_cloud_not_logged_in, cloudServerUrl),
                    icon = Icons.Default.Cloud,
                    onClick = onCloudSettingsClick
                )
            }
            item {
                ListItem(
                    title = stringResource(Res.string.settings_providers),
                    subtitle = stringResource(Res.string.settings_providers_desc),
                    icon = Icons.Default.Cloud,
                    onClick = onProvidersClick
                )
            }
            item {
                ListItem(
                    title = stringResource(Res.string.settings_clear_data),
                    subtitle = stringResource(Res.string.settings_clear_data_desc),
                    icon = Icons.Default.Delete,
                    titleColor = MaterialTheme.colorScheme.error,
                    subtitleColor = MaterialTheme.colorScheme.error,
                    iconColor = MaterialTheme.colorScheme.error,
                    onClick = { showClearDataDialog = true }
                )
            }

            item {
                SectionHeader(title = stringResource(Res.string.settings_about))
            }
            item {
                ListItem(
                    title = stringResource(Res.string.settings_version),
                    subtitle = appVersionName() ?: stringResource(Res.string.settings_version_unknown),
                    icon = Icons.Default.Info,
                    showArrow = false,
                    onClick = null
                )
            }
            item {
                ListItem(
                    title = stringResource(Res.string.settings_licenses),
                    subtitle = null,
                    icon = Icons.Default.Code,
                    onClick = onLicensesClick
                )
            }
        }

        if (showThemeDialog) {
            ThemePickerDialog(
                currentTheme = themeMode,
                onDismiss = { showThemeDialog = false },
                onConfirm = { mode ->
                    viewModel.setThemeMode(mode)
                    showThemeDialog = false
                }
            )
        }

        if (showClearDataDialog) {
            ConfirmationDialog(
                title = stringResource(Res.string.settings_clear_data),
                 text = stringResource(Res.string.settings_clear_data_confirm),
                confirmButtonText = stringResource(Res.string.settings_clear_data_button),
                dismissButtonText = stringResource(Res.string.action_cancel),
                onConfirm = {
                    viewModel.clearAllData()
                    showClearDataDialog = false
                },
                onDismiss = { showClearDataDialog = false }
            )
        }
        } // close centered Box
    }
}

@Composable
private fun getThemeLabel(themeMode: ThemeMode): String {
    return when (themeMode) {
        ThemeMode.SYSTEM -> stringResource(Res.string.settings_theme_system)
        ThemeMode.LIGHT -> stringResource(Res.string.settings_theme_light)
        ThemeMode.DARK -> stringResource(Res.string.settings_theme_dark)
    }
}
