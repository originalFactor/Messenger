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

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.settings_select_section
import cc.ptoe.messenger.presentation.ui.components.EmptyState
import cc.ptoe.messenger.presentation.ui.providers.ProvidersDualPaneScreen
import org.jetbrains.compose.resources.stringResource

private enum class SettingsDetailPane { Empty, Cloud, Providers, Licenses }

@Composable
fun SettingsDualPaneScreen(
    modifier: Modifier = Modifier
) {
    var pane by rememberSaveable { mutableStateOf(SettingsDetailPane.Empty) }

    Row(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxHeight()
                .width(360.dp)
        ) {
            SettingsScreen(
                onProvidersClick = { pane = SettingsDetailPane.Providers },
                onCloudSettingsClick = { pane = SettingsDetailPane.Cloud },
                onLicensesClick = { pane = SettingsDetailPane.Licenses }
            )
        }

        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )

        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
        ) {
            when (pane) {
                SettingsDetailPane.Empty -> EmptyState(
                    icon = Icons.Default.Settings,
                    message = stringResource(Res.string.settings_select_section),
                    modifier = Modifier.fillMaxSize()
                )

                SettingsDetailPane.Cloud -> CloudSettingsScreen(
                    onBackClick = { pane = SettingsDetailPane.Empty },
                    cloudSyncRepository = AppContainerHolder.instance.cloudSyncRepository
                )

                SettingsDetailPane.Providers -> ProvidersDualPaneScreen()

                SettingsDetailPane.Licenses -> LicensesScreen(
                    onBackClick = { pane = SettingsDetailPane.Empty }
                )
            }
        }
    }
}
