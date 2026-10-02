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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cc.ptoe.messenger.presentation.navigation.BottomLevelRoutes
import cc.ptoe.messenger.presentation.navigation.NavGraph
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor

@Composable
fun MainScaffold(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showTopLevelNav = currentDestination?.hierarchy?.any { destination ->
        destination.route in BottomLevelRoutes.routes
    } == true

    val onItemClick: (BottomNavItem) -> Unit = { item ->
        navController.navigate(item.screen.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val sizeClass = windowSizeClassFor(maxWidth)

        when (sizeClass) {
            WindowSizeClass.Compact -> {
                // Mobile phone: bottom NavigationBar (existing behavior).
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    // The bottom navigation wrapper owns the system navigation inset;
                    // child screens own their top app-bar insets.
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (showTopLevelNav) {
                            // Floating pill: no opaque wrapper background — content
                            // scrolls beneath the bar and stays visible around it.
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .navigationBarsPadding()
                            ) {
                                BottomNavBar(
                                    currentRoute = currentDestination.route,
                                    onItemClick = onItemClick,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                ) { _ ->
                    // No bottom padding here: content extends beneath the floating
                    // bar; top-level screens add their own bar-clearance padding so
                    // the last list item can scroll clear of the pill.
                    Box(modifier = Modifier.fillMaxSize()) {
                        NavGraph(
                            navController = navController,
                            sizeClass = sizeClass,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            else -> {
                // Medium / Expanded (tablet landscape, desktop):
                // left NavigationRail + content fills the rest — M3 desktop pattern.
                Row(modifier = Modifier.fillMaxSize()) {
                    if (showTopLevelNav) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(12.dp)
                        ) {
                            NavigationRailBar(
                                currentRoute = currentDestination.route,
                                onItemClick = onItemClick,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        VerticalDivider(
                            modifier = Modifier.fillMaxHeight(),
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                        NavGraph(navController = navController, sizeClass = sizeClass)
                    }
                }
            }
        }
    }
}
