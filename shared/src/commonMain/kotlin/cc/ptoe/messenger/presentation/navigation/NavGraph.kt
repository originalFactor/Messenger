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

package cc.ptoe.messenger.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.savedstate.read
import cc.ptoe.messenger.presentation.ui.agents.AgentEditScreen
import cc.ptoe.messenger.presentation.ui.agents.AgentsDualPaneScreen
import cc.ptoe.messenger.presentation.ui.agents.AgentsScreen
import cc.ptoe.messenger.presentation.ui.agents.AgentMarketDetailScreen
import cc.ptoe.messenger.presentation.ui.agents.AgentMarketScreen
import cc.ptoe.messenger.presentation.ui.chat.ChatScreen
import cc.ptoe.messenger.presentation.ui.conversations.ConversationSettingsScreen
import cc.ptoe.messenger.presentation.ui.conversations.ConversationsDualPaneScreen
import cc.ptoe.messenger.presentation.ui.conversations.ConversationsScreen
import cc.ptoe.messenger.presentation.ui.conversations.ModelPickerScreen
import cc.ptoe.messenger.presentation.ui.conversations.ProviderPickerScreen
import cc.ptoe.messenger.presentation.ui.providers.ProviderDetailScreen
import cc.ptoe.messenger.presentation.ui.providers.ProviderEditScreen
import cc.ptoe.messenger.presentation.ui.providers.ProvidersDualPaneScreen
import cc.ptoe.messenger.presentation.ui.providers.ProvidersScreen
import cc.ptoe.messenger.presentation.ui.providers.ModelDetailScreen
import cc.ptoe.messenger.presentation.ui.settings.LicensesScreen
import cc.ptoe.messenger.presentation.ui.settings.SettingsDualPaneScreen
import cc.ptoe.messenger.presentation.ui.settings.SettingsScreen
import cc.ptoe.messenger.presentation.ui.settings.CloudSettingsScreen
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor
import cc.ptoe.messenger.presentation.viewmodel.ConversationsViewModel
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.conversations_rename_title
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder

/** SavedState 结果回传 key：ModelPicker 选中模型 */
private const val KEY_PICKED_MODEL_ID = "conversation_settings_picked_model_id"

@Composable
fun NavGraph(
    navController: NavHostController,
    sizeClass: WindowSizeClass,
    modifier: Modifier = Modifier
) {
    val verticalTransitions = sizeClass != WindowSizeClass.Compact
    NavHost(
        navController = navController,
        startDestination = Screen.Conversations.route,
        modifier = modifier,
        enterTransition = navEnterTransition(verticalTransitions),
        exitTransition = navExitTransition(verticalTransitions),
        popEnterTransition = navPopEnterTransition(verticalTransitions),
        popExitTransition = navPopExitTransition(verticalTransitions)
    ) {
        composable(Screen.Conversations.route) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val sizeClass = windowSizeClassFor(maxWidth)
                if (sizeClass != WindowSizeClass.Compact) {
                    // Medium / Expanded (tablet portrait / desktop): List-Detail two-pane layout.
                    ConversationsDualPaneScreen(
                        initialConversationId = null,
                        onOpenConversationSettings = { conversationId ->
                            navController.navigate(Screen.ConversationSettings.createRoute(conversationId))
                        }
                    )
                } else {
                    // Phone: single-pane, push Chat route on tap.
                    ConversationsScreen(
                        onConversationClick = { conversationId ->
                            navController.navigate(Screen.Chat.createRoute(conversationId))
                        }
                    )
                }
            }
        }
        composable(
            route = Screen.Chat.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.read { getStringOrNull("conversationId") } ?: ""
            ChatScreen(
                conversationId = conversationId,
                onBackClick = { navController.popBackStack() },
                onSettingsClick = {
                    navController.navigate(Screen.ConversationSettings.createRoute(conversationId))
                }
            )
        }
        composable(Screen.Agents.route) { backStackEntry ->
            val conversationsBackStackEntry = remember(backStackEntry) {
                navController.getBackStackEntry(Screen.Conversations.route)
            }
            val conversationsViewModel: ConversationsViewModel = viewModel(
                conversationsBackStackEntry,
                factory = ConversationsViewModel.provideFactory(
                    conversationRepository = AppContainerHolder.instance.conversationRepository,
                    messageRepository = AppContainerHolder.instance.messageRepository,
                    currentAgentRepository = AppContainerHolder.instance.currentAgentRepository,
                    agentRepository = AppContainerHolder.instance.agentRepository,
                    modelRepository = AppContainerHolder.instance.modelRepository
                )
            )
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val sizeClass = windowSizeClassFor(maxWidth)
                // 双栏布局下 AgentEditScreen 托管在 Agents 页面内，Picker 结果回传到本 entry
                val savedStateHandle = backStackEntry.savedStateHandle
                val pickedModelId by savedStateHandle
                    .getStateFlow<String?>(KEY_PICKED_MODEL_ID, null)
                    .collectAsStateWithLifecycle()
                if (sizeClass != WindowSizeClass.Compact) {
                    // Medium / Expanded (tablet portrait / desktop): List-Detail two-pane layout.
                    AgentsDualPaneScreen(
                        onOpenAgentEdit = { /* Dual-pane: AgentsDualPaneScreen manages selectedAgentId internally. */ },
                        onSelectCurrentAgent = { agentId ->
                            conversationsViewModel.switchAgent(agentId)
                        },
                        onMarketClick = {
                            navController.navigate(Screen.AgentMarket.route)
                        },
                        onPickProvider = { currentProviderId ->
                            navController.navigate(Screen.ProviderPicker.createRoute(currentProviderId))
                        },
                        pickedModelId = pickedModelId,
                        onPickedModelConsumed = { savedStateHandle[KEY_PICKED_MODEL_ID] = null }
                    )
                } else {
                    // Phone: single-pane, push AgentEdit / AgentMarket on tap.
                    AgentsScreen(
                        onAddClick = {
                            navController.navigate(Screen.AgentEdit.createRoute())
                        },
                        onMarketClick = {
                            navController.navigate(Screen.AgentMarket.route)
                        },
                        onEditClick = { agentId ->
                            navController.navigate(Screen.AgentEdit.createRoute(agentId))
                        },
                        onAgentClick = { agentId ->
                            conversationsViewModel.switchAgent(agentId)
                            navController.popBackStack(Screen.Conversations.route, inclusive = false)
                        }
                    )
                }
            }
        }
        composable(Screen.Providers.route) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val sizeClass = windowSizeClassFor(maxWidth)
                if (sizeClass != WindowSizeClass.Compact) {
                    // Medium / Expanded (tablet portrait / desktop): List-Detail two-pane layout.
                    ProvidersDualPaneScreen()
                } else {
                    // Phone: single-pane, push ProviderEdit / ProviderDetail on tap.
                    ProvidersScreen(
                        onBackClick = { navController.popBackStack() },
                        onAddClick = {
                            navController.navigate(Screen.ProviderEdit.createRoute())
                        },
                        onEditClick = { providerId ->
                            navController.navigate(Screen.ProviderEdit.createRoute(providerId))
                        },
                        onProviderClick = { providerId ->
                            navController.navigate(Screen.ProviderDetail.createRoute(providerId))
                        }
                    )
                }
            }
        }
        composable(Screen.Settings.route) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val sizeClass = windowSizeClassFor(maxWidth)
                if (sizeClass != WindowSizeClass.Compact) {
                    // Medium / Expanded (tablet portrait / desktop): List-Detail two-pane layout.
                    SettingsDualPaneScreen()
                } else {
                    // Phone: single-pane, push sub-screens on tap.
                    SettingsScreen(
                        onProvidersClick = {
                            navController.navigate(Screen.Providers.route)
                        },
                        onLicensesClick = {
                            navController.navigate(Screen.Licenses.route)
                        },
                        onCloudSettingsClick = { navController.navigate(Screen.CloudSettings.route) }
                    )
                }
            }
        }
        composable(Screen.AgentMarket.route) {
            AgentMarketScreen(
                onBackClick = { navController.popBackStack() },
                onAgentClick = { marketAgentId ->
                    navController.navigate(Screen.AgentMarketDetail.createRoute(marketAgentId))
                },
                onImported = {
                    navController.popBackStack(Screen.Agents.route, inclusive = false)
                }
            )
        }
        composable(
            route = Screen.AgentMarketDetail.route,
            arguments = listOf(navArgument("marketAgentId") { type = NavType.StringType })
        ) { backStackEntry ->
            val marketAgentId = backStackEntry.arguments?.read { getStringOrNull("marketAgentId") } ?: return@composable
            AgentMarketDetailScreen(
                marketAgentId = marketAgentId,
                onBackClick = { navController.popBackStack() },
                onImported = {
                    navController.popBackStack(Screen.AgentMarket.route, inclusive = true)
                }
            )
        }
        composable(Screen.CloudSettings.route) {
            CloudSettingsScreen(
                onBackClick = { navController.popBackStack() },
                cloudSyncRepository = AppContainerHolder.instance.cloudSyncRepository
            )
        }

        composable(
            route = Screen.ProviderEdit.route,
            arguments = listOf(
                navArgument("providerId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.read { getStringOrNull("providerId") }
            ProviderEditScreen(
                providerId = providerId,
                onBackClick = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ProviderDetail.route,
            arguments = listOf(
                navArgument("providerId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.read { getStringOrNull("providerId") } ?: ""
            ProviderDetailScreen(
                providerId = providerId,
                onBackClick = { navController.popBackStack() },
                onModelClick = { model ->
                    navController.navigate(Screen.ModelDetail.createRoute(model.id))
                }
            )
        }

        composable(
            route = Screen.ModelDetail.route,
            arguments = listOf(
                navArgument("modelId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments?.read { getStringOrNull("modelId") } ?: ""
            ModelDetailScreen(
                modelId = modelId,
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.AgentEdit.route,
            arguments = listOf(
                navArgument("agentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val agentId = backStackEntry.arguments?.read { getStringOrNull("agentId") }
            val savedStateHandle = backStackEntry.savedStateHandle
            val pickedModelId by savedStateHandle
                .getStateFlow<String?>(KEY_PICKED_MODEL_ID, null)
                .collectAsStateWithLifecycle()
            AgentEditScreen(
                agentId = agentId,
                onBackClick = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onPickProvider = { currentProviderId ->
                    navController.navigate(Screen.ProviderPicker.createRoute(currentProviderId))
                },
                pickedModelId = pickedModelId,
                onPickedModelConsumed = { savedStateHandle[KEY_PICKED_MODEL_ID] = null }
            )
        }

        composable(
            route = Screen.ConversationRename.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.read { getStringOrNull("conversationId") } ?: ""
            SimplePlaceholderScreen(
                text = stringResource(Res.string.conversations_rename_title) + ": $conversationId"
            )
        }

        composable(
            route = Screen.ConversationSettings.route,
            arguments = listOf(
                navArgument("conversationId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments?.read { getStringOrNull("conversationId") } ?: ""
            val savedStateHandle = backStackEntry.savedStateHandle
            val pickedModelId by savedStateHandle
                .getStateFlow<String?>(KEY_PICKED_MODEL_ID, null)
                .collectAsStateWithLifecycle()
            ConversationSettingsScreen(
                conversationId = conversationId,
                onBackClick = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
                onPickProvider = { currentProviderId ->
                    navController.navigate(Screen.ProviderPicker.createRoute(currentProviderId))
                },
                pickedModelId = pickedModelId,
                onPickedModelConsumed = { savedStateHandle[KEY_PICKED_MODEL_ID] = null }
            )
        }

        composable(
            route = Screen.ProviderPicker.route,
            arguments = listOf(
                navArgument("selectedProviderId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val selectedProviderId = backStackEntry.arguments?.read { getStringOrNull("selectedProviderId") }
            ProviderPickerScreen(
                selectedProviderId = selectedProviderId,
                onBackClick = { navController.popBackStack() },
                onProviderSelected = { providerId ->
                    // 选中 Provider 后直接进入该 Provider 的模型选择页（二级联动），
                    // 并把本页从返回栈移除，形成"设置页 → Provider 页 → Model 页"的连续流程
                    navController.navigate(Screen.ModelPicker.createRoute(providerId)) {
                        popUpTo(Screen.ProviderPicker.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Screen.ModelPicker.route,
            arguments = listOf(
                navArgument("providerId") { type = NavType.StringType },
                navArgument("selectedModelId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val providerId = backStackEntry.arguments?.read { getStringOrNull("providerId") } ?: ""
            val selectedModelId = backStackEntry.arguments?.read { getStringOrNull("selectedModelId") }
            ModelPickerScreen(
                providerId = providerId,
                selectedModelId = selectedModelId,
                onBackClick = { navController.popBackStack() },
                onModelSelected = { modelId ->
                    // 只回传选中的模型 ID；所属 Provider 由设置页/Agent 编辑页
                    // 从模型数据反查并原子更新，避免回传二值引发状态竞态
                    navController.previousBackStackEntry?.savedStateHandle?.set(KEY_PICKED_MODEL_ID, modelId)
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Licenses.route) {
            LicensesScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun SimplePlaceholderScreen(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineSmall
        )
    }
}
