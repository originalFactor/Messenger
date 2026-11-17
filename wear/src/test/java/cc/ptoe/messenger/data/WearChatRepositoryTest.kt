/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data

import java.util.concurrent.ConcurrentHashMap
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class WearChatRepositoryTest {
    private val agent = WearAgent("a", "Agent", null, true, true, true)
    private fun conversation(id: String) = WearConversation(id, id, "a", "before", 1, 1)
    private fun message(id: String, conversation: String, role: WearMessageRole, text: String) = WearChatMessage(id, conversation, role, text, 1)

    private class Store(agents: List<WearAgent>, conversations: List<WearConversation>) : WearChatStore {
        override val agents = MutableStateFlow(agents)
        override val conversations = MutableStateFlow(conversations)
        override val selectedConversationId = MutableStateFlow<String?>(conversations.firstOrNull()?.id)
        override val messageHistory = MutableStateFlow<Map<String, List<WearChatMessage>>>(emptyMap())
        override val userAvatarPath = MutableStateFlow<String?>(null)
        var writes = 0
        var beforeWrite: suspend () -> Unit = {}
        override suspend fun setSelectedConversationId(conversationId: String?) { selectedConversationId.value = conversationId }
        override suspend fun applySnapshot(agents: List<WearAgent>, conversations: List<WearConversation>, messages: Map<String, List<WearChatMessage>>,
            userAvatarPath: String?) {
            beforeWrite()
            writes++
            this.agents.value = agents; this.conversations.value = conversations; messageHistory.value = messages
            this.userAvatarPath.value = userAvatarPath
            val selected = this.selectedConversationId.value
            this.selectedConversationId.value = selected?.takeIf { id -> conversations.any { it.id == id } } ?: conversations.firstOrNull()?.id
        }
    }
    private class Transport : WearChatTransport {
        override val syncUpdates = MutableSharedFlow<WearSyncSnapshot>(extraBufferCapacity = 8)
        override val newChatResponses = MutableSharedFlow<WearNewChatResponse>()
        override val connectionState = MutableStateFlow<WearConnectionState>(WearConnectionState.Connected)
        override val syncError = MutableStateFlow<String?>(null)
        val channels = ConcurrentHashMap<String, Channel<WearChatFrame>>()
        val byConversation = ConcurrentHashMap<String, String>()
        var sent = 0
        var syncs = 0
        var syncGeneration = 0L
        var onSend: (String) -> Unit = {}
        override fun start() {}
        override fun loadExistingState() {}
        override fun requestReconnect() {}
        override fun markSyncBarrier(): Long = ++syncGeneration
        override fun requestSyncNow() { syncs++ }
        override suspend fun retrySync(): Boolean { syncs++; syncError.value = null; return true }
        override fun registerChat(requestId: String): ReceiveChannel<WearChatFrame> = Channel<WearChatFrame>(Channel.UNLIMITED).also { channels[requestId] = it }
        override fun unregisterChat(requestId: String) { channels.remove(requestId)?.cancel() }
        override suspend fun requestChat(requestId: String, conversationId: String, text: String): Result<Unit> {
            sent++; byConversation[conversationId] = requestId; onSend(requestId); return Result.success(Unit)
        }
        override suspend fun requestNewConversation(requestId: String, agentId: String?) = Result.failure<Unit>(IllegalStateException("unused"))
        fun emit(id: String, frame: WearChatFrame) { check(channels.getValue(id).trySend(frame).isSuccess) }
    }

    @Test fun immediateModelRejectionLeavesCommittedStateUntouched() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("c")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            transport.onSend = { id -> transport.emit(id, WearChatFrame.Error(id, "Set a model", "model_required")) }
            val result = withTimeout(5_000) { repository.sendMessage("hello") }
            assertEquals("Set a model", result.exceptionOrNull()?.message)
            assertEquals(1, transport.sent)
            assertEquals(0, store.writes)
            assertTrue(repository.messageHistory.first().isEmpty())
            assertEquals("before", repository.conversations.first().single().lastMessage)
            assertTrue(repository.selectedMessages.first().isEmpty())

            store.agents.value = listOf(agent.copy(isReady = false, hasDefaultModel = null))
            // A fresh repository loads an old snapshot without the model-presence field.
            val oldRepository = WearChatRepository(store, transport, scope)
            assertTrue(oldRepository.sendMessage("blocked").isFailure)
            assertEquals(1, transport.sent)
            assertEquals(0, store.writes)
        } finally { scope.cancel() }
    }

    @Test fun interleavedTurnsAndSnapshotsNeverPersistAnotherPendingTurn() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("a"), conversation("b")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            val first = async(start = CoroutineStart.UNDISPATCHED) { repository.sendMessage("one") }
            val firstId = withTimeout(5_000) { while (transport.byConversation["a"] == null) yield(); transport.byConversation.getValue("a") }
            repository.selectConversation("b")
            val second = async(start = CoroutineStart.UNDISPATCHED) { repository.sendMessage("two") }
            val secondId = withTimeout(5_000) { while (transport.byConversation["b"] == null) yield(); transport.byConversation.getValue("b") }
            transport.emit(firstId, WearChatFrame.Accepted(firstId, "user-a", "assistant-a"))
            transport.emit(secondId, WearChatFrame.Accepted(secondId, "user-b", "assistant-b"))
            assertTrue(repository.messageHistory.first().isEmpty())
            assertTrue(store.messageHistory.value.values.all { it.isEmpty() })

            val stale = WearSyncSnapshot(listOf(agent), listOf(conversation("a"), conversation("b")), emptyMap(), null, 1)
            transport.syncUpdates.emit(stale)
            withTimeout(5_000) { while (store.writes < 1) yield() }
            assertTrue(store.messageHistory.value.values.all { it.isEmpty() })

            transport.emit(firstId, WearChatFrame.Done(firstId, "answer-a", "user-a", "assistant-a"))
            assertTrue(withTimeout(5_000) { first.await() }.isSuccess)
            assertEquals(listOf("user-a", "assistant-a"), store.messageHistory.value.getValue("a").map { it.id })
            assertTrue(store.messageHistory.value["b"].orEmpty().isEmpty())
            val writesBeforeStale = store.writes
            transport.syncUpdates.emit(stale)
            withTimeout(5_000) { while (store.writes == writesBeforeStale) yield() }
            assertEquals(listOf("user-a", "assistant-a"), store.messageHistory.value.getValue("a").map { it.id })
            transport.emit(secondId, WearChatFrame.Error(secondId, "HTTP 503"))
            assertEquals("HTTP 503", withTimeout(5_000) { second.await() }.exceptionOrNull()?.message)
            assertTrue(store.messageHistory.value["b"].orEmpty().isEmpty())
            assertTrue(repository.selectedMessages.first().last().isError)

            val snapshot = WearSyncSnapshot(listOf(agent), listOf(conversation("a"), conversation("b")), mapOf(
                "a" to listOf(message("user-a", "a", WearMessageRole.USER, "one"), message("assistant-a", "a", WearMessageRole.ASSISTANT, "answer-a")),
                "b" to listOf(message("user-b", "b", WearMessageRole.USER, "two"), message("assistant-b", "b", WearMessageRole.ASSISTANT, "HTTP 503").copy(isError = true))
            ), null, 2)
            transport.syncUpdates.emit(snapshot)
            withTimeout(5_000) { repository.messageHistory.first { it["b"]?.lastOrNull()?.id == "assistant-b" } }
            assertEquals(listOf("user-a", "assistant-a"), repository.messageHistory.first().getValue("a").map { it.id })
            assertEquals(listOf("user-b", "assistant-b"), repository.selectedMessages.first().map { it.id })
            assertEquals(2, transport.syncs)
        } finally { scope.cancel() }
    }

    @Test fun phoneSnapshotBeforeCompletionMergesByMessageIdentity() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("c")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            val send = async(start = CoroutineStart.UNDISPATCHED) { repository.sendMessage("hello") }
            val id = withTimeout(5_000) { while (transport.byConversation["c"] == null) yield(); transport.byConversation.getValue("c") }
            transport.emit(id, WearChatFrame.Accepted(id, "user"))
            withTimeout(5_000) { repository.selectedMessages.first { it.firstOrNull()?.id == "user" } }
            val partial = listOf(message("user", "c", WearMessageRole.USER, "hello"),
                message("assistant", "c", WearMessageRole.ASSISTANT, "part").copy(isPending = true))
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")), mapOf("c" to partial), null, 2))
            withTimeout(5_000) { while (store.writes < 1) yield() }
            assertTrue(store.messageHistory.value["c"].orEmpty().isEmpty())
            val visible = repository.selectedMessages.first()
            assertEquals(2, visible.size)
            assertEquals("user", visible.first().id)
            assertTrue(visible.last().id.startsWith("local-assistant-"))
            transport.emit(id, WearChatFrame.Accepted(id, "user", "assistant"))
            transport.emit(id, WearChatFrame.Done(id, "final", "user", "assistant"))
            assertTrue(withTimeout(5_000) { send.await() }.isSuccess)
            assertEquals(listOf("user", "assistant"), repository.messageHistory.first().getValue("c").map { it.id })
            assertEquals("final", repository.messageHistory.first().getValue("c").last().content)

            // A queued stale snapshot cannot roll the completed answer back to pending.
            val writes = store.writes
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")), mapOf("c" to partial), null, 3))
            withTimeout(5_000) { while (store.writes == writes) yield() }
            assertEquals("final", repository.messageHistory.first().getValue("c").last().content)
            val final = partial.map { if (it.id == "assistant") it.copy(content = "final", isPending = false) else it }
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")), mapOf("c" to final), null, 4))
            withTimeout(5_000) { repository.messageHistory.first { it["c"]?.lastOrNull()?.content == "final" } }
            assertEquals(listOf("user", "assistant"), repository.selectedMessages.first().map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun selectionChangedWhileSnapshotWaitsIsNotOverwritten() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("a"), conversation("b")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val repository = WearChatRepository(store, transport, scope)
            store.beforeWrite = { entered.complete(Unit); release.await() }
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("a"), conversation("b")), emptyMap(), null, 2))
            withTimeout(5_000) { entered.await() }
            repository.selectConversation("b")
            release.complete(Unit)
            withTimeout(5_000) { while (store.writes == 0) yield() }
            assertEquals("b", store.selectedConversationId.value)
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun disconnectedUnknownOutcomeRequiresExplicitDismissal() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("c")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            transport.onSend = { id -> transport.channels.getValue(id).close(IOException("disconnected")) }
            assertTrue(withTimeout(5_000) { repository.sendMessage("hello") }.isFailure)
            assertTrue(repository.selectedHasUncertainTurn.first())
            assertEquals(0, store.writes)
            assertTrue(repository.sendMessage("second").isFailure)
            assertEquals(1, transport.sent)
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")), emptyMap(), null, 2))
            withTimeout(5_000) { while (store.writes == 0) yield() }
            assertTrue(repository.selectedHasUncertainTurn.first())
            assertTrue(repository.selectedMessages.first().last().isError)
            repository.dismissUncertainTurn()
            assertFalse(repository.selectedHasUncertainTurn.first())
            assertTrue(repository.selectedMessages.first().isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun userOnlyAcceptanceReconcilesOnlyAfterItsTerminalAssistantAppears() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("c")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            transport.onSend = { id ->
                transport.emit(id, WearChatFrame.Accepted(id, "user"))
                transport.channels.getValue(id).close(IOException("disconnected"))
            }
            assertTrue(withTimeout(5_000) { repository.sendMessage("hello") }.isFailure)
            assertTrue(repository.selectedHasUncertainTurn.first())
            val user = message("user", "c", WearMessageRole.USER, "hello")
            val assistant = message("assistant", "c", WearMessageRole.ASSISTANT, "partial").copy(isPending = true)
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")), mapOf("c" to listOf(user, assistant)), null, 2))
            withTimeout(5_000) { while (store.writes < 1) yield() }
            assertTrue(repository.selectedHasUncertainTurn.first())
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")),
                mapOf("c" to listOf(user, assistant.copy(isPending = false, isError = true))), null, 3))
            withTimeout(5_000) { repository.selectedHasUncertainTurn.first { !it } }
            assertEquals(listOf("user", "assistant"), repository.selectedMessages.first().map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun snapshotAvatarSurvivesInterleavedBlockedWritesAndChatCompletion() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("a"), conversation("b")))
        store.userAvatarPath.value = "old"
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val repository = WearChatRepository(store, transport, scope)
            store.beforeWrite = { entered.complete(Unit); release.await() }
            val first = async(start = CoroutineStart.UNDISPATCHED) { repository.sendMessage("one") }
            val a = withTimeout(5_000) { while (transport.byConversation["a"] == null) yield(); transport.byConversation.getValue("a") }
            transport.emit(a, WearChatFrame.Done(a, "A", "user-a", "assistant-a"))
            withTimeout(5_000) { entered.await() }
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("a"), conversation("b")), emptyMap(), "new", 2))
            withTimeout(5_000) { repository.userAvatarPath.first { it == "new" } }
            repository.selectConversation("b")
            val second = async(start = CoroutineStart.UNDISPATCHED) { repository.sendMessage("two") }
            val b = withTimeout(5_000) { while (transport.byConversation["b"] == null) yield(); transport.byConversation.getValue("b") }
            transport.emit(b, WearChatFrame.Done(b, "B", "user-b", "assistant-b"))
            release.complete(Unit)
            assertTrue(withTimeout(5_000) { first.await() }.isSuccess)
            assertTrue(withTimeout(5_000) { second.await() }.isSuccess)
            withTimeout(5_000) { store.userAvatarPath.first { it == "new" } }
            assertEquals("new", store.userAvatarPath.value)
            assertEquals("b", store.selectedConversationId.value)
            assertEquals(listOf("user-a", "assistant-a"), store.messageHistory.value.getValue("a").map { it.id })
            assertEquals(listOf("user-b", "assistant-b"), store.messageHistory.value.getValue("b").map { it.id })
        } finally { release.complete(Unit); scope.cancel() }
    }

    @Test fun initialStorageFailureIsReturnedWithoutSending() = runBlocking {
        val store = object : WearChatStore {
            override val agents = MutableStateFlow(emptyList<WearAgent>())
            override val conversations = MutableStateFlow(emptyList<WearConversation>())
            override val selectedConversationId = MutableStateFlow<String?>(null)
            override val messageHistory = flow<Map<String, List<WearChatMessage>>> { error("store unavailable") }
            override val userAvatarPath = MutableStateFlow<String?>(null)
            override suspend fun setSelectedConversationId(conversationId: String?) {}
            override suspend fun applySnapshot(agents: List<WearAgent>, conversations: List<WearConversation>,
                messages: Map<String, List<WearChatMessage>>, userAvatarPath: String?) {}
        }
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val result = WearChatRepository(store, transport, scope).sendMessage("hello")
            assertEquals("store unavailable", result.exceptionOrNull()?.message)
            assertTrue(result.exceptionOrNull() is WearNotAdmittedException)
            assertEquals(0, transport.sent)
        } finally { scope.cancel() }
    }

    @Test fun capturedConversationIsUsedAfterStorageLoadSuspends() = runBlocking {
        val load = CompletableDeferred<Unit>()
        val selected = MutableStateFlow<String?>("a")
        val store = object : WearChatStore {
            override val agents = MutableStateFlow(listOf(agent))
            override val conversations = MutableStateFlow(listOf(conversation("a"), conversation("b")))
            override val selectedConversationId = selected
            override val messageHistory = flow { load.await(); emit(emptyMap<String, List<WearChatMessage>>()) }
            override val userAvatarPath = MutableStateFlow<String?>(null)
            override suspend fun setSelectedConversationId(conversationId: String?) { selected.value = conversationId }
            override suspend fun applySnapshot(agents: List<WearAgent>, conversations: List<WearConversation>,
                messages: Map<String, List<WearChatMessage>>, userAvatarPath: String?) {}
        }
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            transport.onSend = { id -> transport.emit(id, WearChatFrame.Error(id, "No model", "model_required")) }
            val send = async(start = CoroutineStart.UNDISPATCHED) { repository.sendMessage("a", "message for a") }
            selected.value = "b"
            load.complete(Unit)
            assertTrue(withTimeout(5_000) { send.await() }.exceptionOrNull() is WearModelRequiredException)
            assertNotNull(transport.byConversation["a"])
            assertNull(transport.byConversation["b"])
        } finally { load.complete(Unit); scope.cancel() }
    }

    @Test fun retryStorageRecoversFailedInitialReadWithoutDiscardingPhoneSnapshot() = runBlocking {
        var available = false
        var savedAvatar: String? = null
        val store = object : WearChatStore {
            override val agents = MutableStateFlow(listOf(agent))
            override val conversations = MutableStateFlow(listOf(conversation("c")))
            override val selectedConversationId = MutableStateFlow<String?>("c")
            override val messageHistory = flow { if (!available) error("read failed"); emit(emptyMap<String, List<WearChatMessage>>()) }
            override val userAvatarPath = MutableStateFlow<String?>("old")
            override suspend fun setSelectedConversationId(conversationId: String?) { selectedConversationId.value = conversationId }
            override suspend fun applySnapshot(agents: List<WearAgent>, conversations: List<WearConversation>,
                messages: Map<String, List<WearChatMessage>>, userAvatarPath: String?) { savedAvatar = userAvatarPath }
        }
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            assertTrue(repository.sendMessage("hello").exceptionOrNull() is WearNotAdmittedException)
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")), emptyMap(), "new", 2))
            withTimeout(5_000) { repository.userAvatarPath.first { it == "new" } }
            available = true
            assertTrue(repository.retryStorage())
            assertEquals("new", savedAvatar)
            assertNull(repository.storageError.value)
            transport.onSend = { id -> transport.emit(id, WearChatFrame.Error(id, "No model", "model_required")) }
            assertTrue(repository.sendMessage("hello").exceptionOrNull() is WearModelRequiredException)
        } finally { scope.cancel() }
    }

    @Test fun storageFailureRequiresExplicitRetryAndWritesLatestSnapshot() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("c")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        try {
            val repository = WearChatRepository(store, transport, scope)
            store.beforeWrite = { attempts++; if (attempts == 1) throw IOException("disk full") }
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")), emptyMap(), "first", 1))
            withTimeout(5_000) { repository.storageError.first { it != null } }
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")), emptyMap(), "latest", 2))
            withTimeout(5_000) { repository.userAvatarPath.first { it == "latest" } }
            assertEquals(1, attempts)
            assertNull(store.userAvatarPath.value)
            assertTrue(repository.retryStorage())
            assertEquals(2, attempts)
            assertEquals("latest", store.userAvatarPath.value)
            assertNull(repository.storageError.value)
        } finally { scope.cancel() }
    }

    @Test fun successfulPhoneTurnStaysSuccessfulWhenLocalSaveFails() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("c")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            store.beforeWrite = { throw IOException("disk full") }
            transport.onSend = { id -> transport.emit(id, WearChatFrame.Done(id, "answer", "user", "assistant")) }
            assertTrue(withTimeout(5_000) { repository.sendMessage("hello") }.isSuccess)
            assertEquals("disk full", repository.storageError.value)
            assertEquals(listOf("user", "assistant"), repository.messageHistory.first().getValue("c").map { it.id })
            assertFalse(repository.selectedHasUncertainTurn.first())
        } finally { scope.cancel() }
    }

    @Test fun completedTurnDoesNotHideNewPhoneHistoryWhenIdsLeaveSnapshotWindow() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("c")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            val send = async(start = CoroutineStart.UNDISPATCHED) { repository.sendMessage("hello") }
            val id = withTimeout(5_000) { while (transport.byConversation["c"] == null) yield(); transport.byConversation.getValue("c") }
            transport.emit(id, WearChatFrame.Done(id, "answer", "user", "assistant"))
            assertTrue(withTimeout(5_000) { send.await() }.isSuccess)
            val newer = message("newer", "c", WearMessageRole.USER, "newer phone message").copy(timestamp = System.currentTimeMillis() + 1000)
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")), mapOf("c" to listOf(newer)), null, 2))
            val rows = withTimeout(5_000) { repository.messageHistory.first { it["c"]?.any { row -> row.id == "newer" } == true }.getValue("c") }
            assertEquals(listOf("user", "assistant", "newer"), rows.map { it.id })
            // This request started after completion. Its bounded snapshot is authoritative,
            // even when the completed IDs were deleted or fell out of the phone's window.
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")),
                mapOf("c" to listOf(newer)), null, 3, syncGeneration = transport.syncGeneration, messagesComplete = true))
            val authoritative = withTimeout(5_000) {
                repository.messageHistory.first { it["c"]?.map { row -> row.id } == listOf("newer") }
            }
            assertEquals(listOf("newer"), authoritative.getValue("c").map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun syncStartedBetweenConcurrentCompletionsOnlyReconcilesEarlierTurn() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("a"), conversation("b")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            val a = async(start = CoroutineStart.UNDISPATCHED) { repository.sendMessage("a", "one") }
            val b = async(start = CoroutineStart.UNDISPATCHED) { repository.sendMessage("b", "two") }
            val aId = withTimeout(5_000) { while (transport.byConversation["a"] == null) yield(); transport.byConversation.getValue("a") }
            val bId = withTimeout(5_000) { while (transport.byConversation["b"] == null) yield(); transport.byConversation.getValue("b") }
            transport.emit(aId, WearChatFrame.Done(aId, "A", "ua", "aa"))
            assertTrue(withTimeout(5_000) { a.await() }.isSuccess)
            val earlierRequest = transport.syncGeneration
            transport.emit(bId, WearChatFrame.Done(bId, "B", "ub", "ab"))
            assertTrue(withTimeout(5_000) { b.await() }.isSuccess)
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("a"), conversation("b")),
                emptyMap(), null, 3, syncGeneration = earlierRequest, messagesComplete = true))
            val rows = withTimeout(5_000) {
                repository.messageHistory.first { it["a"].orEmpty().isEmpty() && it["b"].orEmpty().size == 2 }
            }
            assertEquals(listOf("ub", "ab"), rows.getValue("b").map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun legacyPhoneUnmatchedSnapshotRequiresExplicitResolution() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("c")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            transport.onSend = { id -> transport.emit(id, WearChatFrame.Done(id, "answer", "user", "assistant")) }
            assertTrue(repository.sendMessage("hello").isSuccess)
            val later = message("later", "c", WearMessageRole.USER, "phone history")
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")),
                mapOf("c" to listOf(later)), null, 2, syncGeneration = transport.syncGeneration))
            withTimeout(5_000) { repository.selectedHasUncertainTurn.first { it } }
            assertTrue(repository.messageHistory.first().getValue("c").any { it.id == "assistant" })
            assertTrue(repository.sendMessage("do not resend").exceptionOrNull() is WearNotAdmittedException)
            assertEquals(1, transport.sent)
            repository.dismissUncertainTurn()
            assertFalse(repository.selectedHasUncertainTurn.first())
            assertEquals(listOf("later"), repository.messageHistory.first().getValue("c").map { it.id })
            assertEquals(listOf("later"), store.messageHistory.value.getValue("c").map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun laterCompletionDoesNotTrapLegacyDivergenceOrAdoptOlderRows() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("c")))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            transport.onSend = { id -> transport.emit(id, WearChatFrame.Done(id, "first", "u1", "a1")) }
            assertTrue(repository.sendMessage("one").isSuccess)
            transport.onSend = {}
            val second = async(start = CoroutineStart.UNDISPATCHED) { repository.sendMessage("two") }
            val secondId = withTimeout(5_000) {
                while (transport.sent < 2) yield()
                transport.byConversation.getValue("c")
            }
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("c")),
                mapOf("c" to emptyList()), null, 2, syncGeneration = transport.syncGeneration))
            withTimeout(5_000) { repository.selectedHasUncertainTurn.first { it } }
            transport.emit(secondId, WearChatFrame.Done(secondId, "second", "u2", "a2"))
            assertTrue(withTimeout(5_000) { second.await() }.isSuccess)
            repository.dismissUncertainTurn()
            assertFalse(repository.selectedHasUncertainTurn.first())
            assertEquals(listOf("u1", "a1", "u2", "a2"), repository.messageHistory.first().getValue("c").map { it.id })
            transport.onSend = { id -> transport.emit(id, WearChatFrame.Error(id, "No model", "model_required")) }
            assertTrue(repository.sendMessage("three").exceptionOrNull() is WearModelRequiredException)
            assertEquals(3, transport.sent)
        } finally { scope.cancel() }
    }

    @Test fun omittedLegacyMessageBatchPreservesCachedHistoryAndPendingReconciliation() = runBlocking {
        val store = Store(listOf(agent), listOf(conversation("a"), conversation("b")))
        val oldA = message("old-a", "a", WearMessageRole.USER, "previous a")
        val oldB = message("old-b", "b", WearMessageRole.USER, "previous b")
        store.messageHistory.value = mapOf("a" to listOf(oldA), "b" to listOf(oldB))
        val transport = Transport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = WearChatRepository(store, transport, scope)
            transport.onSend = { id -> transport.emit(id, WearChatFrame.Done(id, "answer", "user", "assistant")) }
            assertTrue(repository.sendMessage("hello").isSuccess)
            val writes = store.writes
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("a"), conversation("b")),
                emptyMap(), null, 2, syncGeneration = transport.syncGeneration))
            withTimeout(5_000) { while (store.writes == writes) yield() }
            assertEquals(listOf("old-a", "user", "assistant"), store.messageHistory.value.getValue("a").map { it.id })
            assertEquals(listOf("old-b"), store.messageHistory.value.getValue("b").map { it.id })
            assertFalse(repository.selectedHasUncertainTurn.first())

            // An explicit empty batch is a different response: the user must resolve the mismatch.
            transport.syncUpdates.emit(WearSyncSnapshot(listOf(agent), listOf(conversation("a"), conversation("b")),
                mapOf("a" to emptyList(), "b" to emptyList()), null, 3, syncGeneration = transport.syncGeneration))
            withTimeout(5_000) { repository.selectedHasUncertainTurn.first { it } }
            assertTrue(repository.messageHistory.first().getValue("b").isEmpty())
            repository.dismissUncertainTurn()
            assertTrue(repository.messageHistory.first().getValue("a").isEmpty())
        } finally { scope.cancel() }
    }
}
