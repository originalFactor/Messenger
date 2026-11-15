/*
 * Copyright 2026 originalFactor
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import cc.ptoe.messenger.presentation.navigation.Screen
import cc.ptoe.messenger.presentation.navigation.ConversationOpenRequests
import cc.ptoe.messenger.presentation.navigation.prepareLocalConversation
import cc.ptoe.messenger.presentation.platform.showPlatformToast
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.notification_conversation_unavailable
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor

@Composable
fun MainScaffold(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    val openRequest by ConversationOpenRequests.requests.collectAsState()
    val unavailableMessage = stringResource(Res.string.notification_conversation_unavailable)
    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        try {
            if (request.deviceId != null) {
                navController.navigate(Screen.RemoteConversation.createRoute(request.deviceId, request.conversationId)) { launchSingleTop = true }
            } else {
                val container = AppContainerHolder.instance
                val exists = prepareLocalConversation(
                    signedIn = container.cloud.user.first() != null,
                    sync = { container.cloud.sync() },
                    exists = { withContext(Dispatchers.IO) { container.coreBridge.getConversationJson(request.conversationId) != null } },
                    onSyncFailure = { showPlatformToast(it.message ?: unavailableMessage) },
                )
                if (exists) navController.navigate(Screen.Chat.createRoute(request.conversationId)) { launchSingleTop = true }
                else showPlatformToast(unavailableMessage)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            showPlatformToast(failure.message ?: unavailableMessage)
        } finally {
            ConversationOpenRequests.consume(request)
        }
    }
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
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        val sizeClass = windowSizeClassFor(maxWidth)

        when (sizeClass) {
            WindowSizeClass.Compact -> {
                // Mobile phone: fixed bottom NavigationBar.
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    // The bottom navigation wrapper owns the system navigation inset;
                    // child screens own their top app-bar insets.
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (showTopLevelNav) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                    ) {
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
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxHeight()
                        ) {
                            NavigationRailBar(
                                currentRoute = currentDestination.route,
                                onItemClick = onItemClick
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
