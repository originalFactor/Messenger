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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cc.ptoe.messenger.presentation.navigation.BottomLevelRoutes
import cc.ptoe.messenger.presentation.navigation.NavGraph
import cc.ptoe.messenger.presentation.navigation.Screen
import cc.ptoe.messenger.presentation.navigation.ConversationOpenRequests
import cc.ptoe.messenger.presentation.navigation.handleConversationOpenRequest
import cc.ptoe.messenger.presentation.navigation.prepareLocalConversation
import cc.ptoe.messenger.presentation.platform.showPlatformToast
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_retry
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.notification_conversation_open_failed
import cc.ptoe.messenger.generated.resources.notification_conversation_unavailable
import org.jetbrains.compose.resources.stringResource
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
    val turnFailures = AppContainerHolder.instance.turnFailureNotices
    val turnFailureSnackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val noticeFailure by (turnFailures?.failure ?: kotlinx.coroutines.flow.flowOf(null)).collectAsState(initial = null)
    LaunchedEffect(turnFailures, lifecycle, noticeFailure) {
        if (turnFailures != null) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            turnFailures.present { notice ->
                presentTurnFailureSnackbar(notice, turnFailureSnackbar) { id -> ConversationOpenRequests.open(id) }
            }
        }
    }
    val openRequest by ConversationOpenRequests.requests.collectAsState()
    val unavailableMessage = stringResource(Res.string.notification_conversation_unavailable)
    openRequest?.takeIf { it.error != null }?.let { failed ->
        AlertDialog(
            onDismissRequest = { ConversationOpenRequests.consume(failed) },
            title = { Text(stringResource(Res.string.notification_conversation_open_failed)) },
            text = { Text(failed.error.orEmpty()) },
            confirmButton = { TextButton(onClick = { ConversationOpenRequests.retry(failed) }) { Text(stringResource(Res.string.action_retry)) } },
            dismissButton = { TextButton(onClick = { ConversationOpenRequests.consume(failed) }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
    LaunchedEffect(openRequest) {
        val request = openRequest?.takeIf { it.error == null } ?: return@LaunchedEffect
        handleConversationOpenRequest(request, open = {
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
        })
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
                        Column(modifier = if (showTopLevelNav || noticeFailure != null) Modifier.navigationBarsPadding() else Modifier) {
                            noticeFailure?.let { failure -> TurnFailureRecovery(failure, onRetry = { turnFailures?.retry() }) }
                            if (showTopLevelNav) {
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
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                    noticeFailure?.let { failure ->
                        TurnFailureRecovery(failure, onRetry = { turnFailures?.retry() }, modifier = Modifier.navigationBarsPadding())
                    }
                }
            }
        }
        if (turnFailures != null) {
            SnackbarHost(
                hostState = turnFailureSnackbar,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (sizeClass == WindowSizeClass.Compact && showTopLevelNav) 80.dp else 0.dp)
            )
        }
    }
}
