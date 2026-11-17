/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.viewmodel

import cc.ptoe.messenger.data.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class WearChatViewModelTest {
    @Before fun setUpMain() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    private class Store : WearChatStore {
        override val agents = MutableStateFlow<List<WearAgent>>(emptyList())
        override val conversations = MutableStateFlow(listOf(
            WearConversation("a", "A", "agent", "", 1, 1),
            WearConversation("b", "B", "agent", "", 1, 1)
        ))
        override val selectedConversationId = MutableStateFlow<String?>(null)
        override val messageHistory = MutableStateFlow<Map<String, List<WearChatMessage>>>(emptyMap())
        override val userAvatarPath = MutableStateFlow<String?>(null)
        override suspend fun setSelectedConversationId(conversationId: String?) { selectedConversationId.value = conversationId }
        override suspend fun applySnapshot(agents: List<WearAgent>, conversations: List<WearConversation>,
            messages: Map<String, List<WearChatMessage>>, userAvatarPath: String?) = Unit
    }

    private class Transport : WearChatTransport {
        override val syncUpdates = MutableSharedFlow<WearSyncSnapshot>()
        override val newChatResponses = MutableSharedFlow<WearNewChatResponse>()
        override val connectionState = MutableStateFlow<WearConnectionState>(WearConnectionState.Disconnected)
        override val syncError = MutableStateFlow<String?>(null)
        override fun start() = Unit
        override fun loadExistingState() = Unit
        override fun requestReconnect() = Unit
        private var syncGeneration = 0L
        override fun markSyncBarrier(): Long = ++syncGeneration
        override fun requestSyncNow() = Unit
        override suspend fun retrySync(): Boolean = true
        override fun registerChat(requestId: String): ReceiveChannel<WearChatFrame> = Channel()
        override fun unregisterChat(requestId: String) = Unit
        override suspend fun requestChat(requestId: String, conversationId: String, text: String) = Result.success(Unit)
        override suspend fun requestNewConversation(requestId: String, agentId: String?) = Result.success(Unit)
    }

    @Test fun modelRejectionRestoresOnlyAnUneditedDraft() = runTest {
        val viewModels = mutableListOf<WearChatViewModel>()
        try {
            val responses = Channel<CompletableDeferred<Result<Unit>>>(Channel.UNLIMITED)
            val viewModel = WearChatViewModel(WearChatRepository(Store(), Transport(), backgroundScope)) { _, _ ->
                CompletableDeferred<Result<Unit>>().also { responses.send(it) }.await()
            }.also { viewModels.add(it) }
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()
            viewModel.openChat("a")
            runCurrent()

            viewModel.updateDraft("  original  ")
            viewModel.sendDraft()
            runCurrent()
            assertTrue(viewModel.uiState.value.isSending)
            responses.receive().complete(Result.failure(WearModelRequiredException("Choose a model")))
            runCurrent()
            assertEquals("  original  ", viewModel.uiState.value.draft)
            assertEquals("Choose a model", viewModel.uiState.value.bannerMessage)
            assertFalse(viewModel.uiState.value.isSending)

            viewModel.sendDraft()
            runCurrent()
            viewModel.updateDraft("new text")
            responses.receive().complete(Result.failure(WearModelRequiredException("Choose a model")))
            runCurrent()
            assertEquals("new text", viewModel.uiState.value.draft)
            assertFalse(viewModel.uiState.value.isSending)

            // Editing and then clearing still counts as an edit.
            viewModel.sendDraft()
            runCurrent()
            viewModel.updateDraft("changed")
            viewModel.updateDraft("")
            responses.receive().complete(Result.failure(WearModelRequiredException("Choose a model")))
            runCurrent()
            assertEquals("", viewModel.uiState.value.draft)

            viewModel.updateDraft("unsent")
            viewModel.sendDraft()
            runCurrent()
            responses.receive().complete(Result.failure(WearNotAdmittedException("Storage unavailable")))
            runCurrent()
            assertEquals("unsent", viewModel.uiState.value.draft)
        } finally {
            backgroundScope.cancel()
            viewModels.forEach { it.viewModelScope.cancel() }
            runCurrent()
        }
    }

    @Test fun thrownFailureAndCancellationAlwaysReleaseSendingState() = runTest {
        val viewModels = mutableListOf<WearChatViewModel>()
        try {
            var cancel = false
            val viewModel = WearChatViewModel(WearChatRepository(Store(), Transport(), backgroundScope)) { _, _ ->
                if (cancel) throw CancellationException("cancelled")
                throw IllegalStateException("storage failed")
            }.also { viewModels.add(it) }
            backgroundScope.launch { viewModel.uiState.collect {} }
            runCurrent()
            viewModel.openChat("a")
            runCurrent()
            viewModel.updateDraft("hello")
            viewModel.sendDraft()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSending)
            assertEquals("storage failed", viewModel.uiState.value.bannerMessage)

            viewModel.dismissBanner()
            cancel = true
            viewModel.updateDraft("next")
            viewModel.sendDraft()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isSending)
            assertNull(viewModel.uiState.value.bannerMessage)
        } finally {
            backgroundScope.cancel()
            viewModels.forEach { it.viewModelScope.cancel() }
            runCurrent()
        }
    }

    @Test fun delayedRejectionReturnsDraftToTheSubmittedChat() = runTest {
        val viewModels = mutableListOf<WearChatViewModel>()
        try {
            val response = CompletableDeferred<Result<Unit>>()
            var sentTo: String? = null
            val viewModel = WearChatViewModel(WearChatRepository(Store(), Transport(), backgroundScope)) { id, _ ->
                sentTo = id
                response.await()
            }.also { viewModels.add(it) }
            backgroundScope.launch { viewModel.uiState.collect {} }
            viewModel.openChat("a")
            runCurrent()
            viewModel.updateDraft("message for A")
            viewModel.sendDraft()
            runCurrent()
            viewModel.openChat("b")
            runCurrent()
            viewModel.updateDraft("draft for B")
            response.complete(Result.failure(WearModelRequiredException("Choose a model")))
            runCurrent()
            assertEquals("a", sentTo)
            assertEquals("draft for B", viewModel.uiState.value.draft)
            assertNull(viewModel.uiState.value.bannerMessage)
            viewModel.openChat("a")
            runCurrent()
            assertEquals("message for A", viewModel.uiState.value.draft)
            assertEquals("Choose a model", viewModel.uiState.value.bannerMessage)
        } finally {
            backgroundScope.cancel()
            viewModels.forEach { it.viewModelScope.cancel() }
            runCurrent()
        }
    }
}
