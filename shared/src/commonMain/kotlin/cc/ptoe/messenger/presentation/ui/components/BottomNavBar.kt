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

package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.presentation.navigation.Screen
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.bottom_nav_agents
import cc.ptoe.messenger.generated.resources.bottom_nav_conversations
import cc.ptoe.messenger.generated.resources.bottom_nav_settings
import cc.ptoe.messenger.generated.resources.tooltip_nav_agents
import cc.ptoe.messenger.generated.resources.tooltip_nav_conversations
import cc.ptoe.messenger.generated.resources.tooltip_nav_settings
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

sealed class BottomNavItem(
    val screen: Screen,
    val labelRes: StringResource,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    data object Conversations : BottomNavItem(
        screen = Screen.Conversations,
        labelRes = Res.string.bottom_nav_conversations,
        icon = Icons.Filled.ChatBubble
    )

    data object Agents : BottomNavItem(
        screen = Screen.Agents,
        labelRes = Res.string.bottom_nav_agents,
        icon = Icons.Filled.SmartToy
    )

    data object Settings : BottomNavItem(
        screen = Screen.Settings,
        labelRes = Res.string.bottom_nav_settings,
        icon = Icons.Filled.Settings
    )

    companion object {
        val items = listOf(Conversations, Agents, Settings)
    }
}

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onItemClick: (BottomNavItem) -> Unit
) {
    NavigationBar {
        BottomNavItem.items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.screen.route,
                onClick = { onItemClick(item) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.labelRes)
                    )
                },
                label = {
                    Text(text = stringResource(item.labelRes))
                }
            )
        }
    }
}

/**
 * Material 3 NavigationRail — used on Medium / Expanded window widths
 * (tablet landscape, desktop) in place of the bottom NavigationBar.
 *
 * Per M3 guidance the rail is 80 dp wide, hosts the same top-level
 * destinations as the bottom bar, and is the canonical primary
 * navigation surface for desktop windows.
 *
 * Each destination is wrapped in a [TooltipBox] so desktop users get a
 * descriptive tooltip on hover (touch surfaces simply never trigger it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationRailBar(
    currentRoute: String?,
    onItemClick: (BottomNavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        BottomNavItem.items.forEach { item ->
            val tooltipText = when (item) {
                BottomNavItem.Conversations -> stringResource(Res.string.tooltip_nav_conversations)
                BottomNavItem.Agents -> stringResource(Res.string.tooltip_nav_agents)
                BottomNavItem.Settings -> stringResource(Res.string.tooltip_nav_settings)
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                tooltip = {
                    PlainTooltip { Text(tooltipText) }
                },
                state = rememberTooltipState()
            ) {
                NavigationRailItem(
                    selected = currentRoute == item.screen.route,
                    onClick = { onItemClick(item) },
                    icon = {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = stringResource(item.labelRes)
                        )
                    },
                    label = {
                        Text(text = stringResource(item.labelRes))
                    }
                )
            }
        }
    }
}
