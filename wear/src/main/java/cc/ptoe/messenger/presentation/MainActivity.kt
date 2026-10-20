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

package cc.ptoe.messenger.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import cc.ptoe.messenger.WearMessengerApplication
import cc.ptoe.messenger.presentation.theme.MessengerTheme
import cc.ptoe.messenger.presentation.ui.chat.ChatScreen
import cc.ptoe.messenger.presentation.ui.chatlist.ChatListScreen
import cc.ptoe.messenger.presentation.ui.chatlist.NewChatScreen
import cc.ptoe.messenger.presentation.viewmodel.WearChatViewModel
import cc.ptoe.messenger.presentation.viewmodel.WearScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = (application as WearMessengerApplication).wearChatRepository

        setContent {
            val wearChatViewModel: WearChatViewModel = viewModel(
                factory = WearChatViewModel.provideFactory(repository)
            )
            MessengerTheme {
                WearChatApp(wearChatViewModel)
            }
        }
    }
}

@Composable
private fun WearChatApp(
    viewModel: WearChatViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.bannerMessage) {
        if (uiState.bannerMessage != null) {
            delay(3500)
            viewModel.dismissBanner()
        }
    }

    BackHandler(enabled = uiState.screen != WearScreen.ChatList) {
        when (uiState.screen) {
            WearScreen.Chat -> viewModel.navigateBackToList()
            WearScreen.NewChat -> viewModel.cancelNewChat()
            WearScreen.ChatList -> {}
        }
    }

    AppScaffold {
        when (uiState.screen) {
            WearScreen.ChatList -> {
                ChatListScreen(
                    uiState = uiState,
                    onChatOpen = viewModel::openChat,
                    onNewChat = viewModel::startNewChat,
                    onReconnect = { viewModel.requestReconnect() }
                )
            }
            WearScreen.NewChat -> {
                NewChatScreen(
                    agents = uiState.agents,
                    isCreatingChat = uiState.isCreatingChat,
                    onPickAgent = { agentId -> viewModel.createChat(agentId) }
                )
            }
            WearScreen.Chat -> {
                ChatScreen(
                    uiState = uiState,
                    onBack = viewModel::navigateBackToList,
                    onDraftChange = viewModel::updateDraft,
                    onSend = viewModel::sendDraft
                )
            }
        }
    }
}
