/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinContextCompactionTest {
    private val unused = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, method, _ ->
        error("Unexpected delegated call: ${method.name}")
    } as CoreBridge
    private suspend fun seed(db: LocalDatabase, window: Long = 100) {
        val rows = db.rows()
        rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "secret", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, window, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
        RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "system", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
        rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
        rows.upsertMessages((1..15).map { i -> MessageRow("m$i", "c", if (i % 2 == 1) "user" else "assistant", "message $i with sufficient content", null, i.toLong(), "sent", null) })
    }
    private fun store(db: LocalDatabase) = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false }) { 1000L }
    private fun command() = KotlinTurnCommand.Send("c", "new question", null, disableTools = true, supportsToolApproval = false, summarizePrompt = "summarize")

    @Test fun summaryKeepsLastTenRowsAndUpdatesNextRequestWithoutDeletingHistory() = runBlocking {
        val dir = Files.createTempDirectory("context-summary")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db)
            val calls = mutableListOf<ChatCompletionRequestDto>()
            val store = store(db)
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, request -> flow {
                calls.add(request)
                if (calls.size == 1) { emit(ChatStreamEvent.Content("folded summary")); emit(ChatStreamEvent.Done("stop")) }
                else { emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop")) }
            } }, KotlinTurnToolExecutor { _, _ -> error("tools disabled") }, scope)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(command())).turnId
            withTimeout(5000) { runner.awaitStopped(id) }
            assertEquals(KotlinTurnPhase.FINISHED, store.snapshot(id).phase)
            assertEquals(2, calls.size)
            assertEquals("summarize", calls[0].messages.first().content.jsonPrimitive.content)
            assertTrue(calls[0].tools.isNullOrEmpty())
            assertEquals("folded summary", db.rows().conversation("c")?.contextSummary)
            assertEquals(7L, db.rows().conversation("c")?.contextSummaryUntil)
            assertEquals(12, calls[1].messages.size) // system + summary + 10 recent messages
            assertTrue(calls[1].messages[1].content.jsonPrimitive.content.contains("folded summary"))
            assertEquals("message 7 with sufficient content", calls[1].messages[2].content.jsonPrimitive.content)
            assertEquals(17, db.rows().messagesFor(listOf("c")).size)
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun summaryFailureReportsBubbleAndStillSendsWithoutRetry() = runBlocking {
        val dir = Files.createTempDirectory("context-summary-error")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db)
            var calls = 0
            val store = store(db)
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, _ -> flow {
                if (++calls == 1) emit(ChatStreamEvent.Error("HTTP 429: rate limit"))
                else { emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop")) }
            } }, KotlinTurnToolExecutor { _, _ -> error("tools disabled") }, scope)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(command())).turnId
            withTimeout(5000) { runner.awaitStopped(id) }
            assertEquals(2, calls)
            assertNull(db.rows().conversation("c")?.contextSummary)
            val messages = db.rows().messagesFor(listOf("c"))
            assertTrue(messages.any { it.status == "error" && it.errorMessage!!.contains("HTTP 429") })
            assertTrue(messages.any { it.status == "sent" && it.content == "answer" })
            val events = mutableListOf<String>()
            KotlinBackgroundTurnBridge(unused, store, runner, scope).observeBackgroundTurn("c") { events.add(it) }
            assertTrue(events.any { it.contains("SummaryFailed") && it.contains("429") })
            assertTrue(events.last().contains("Finished"))
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun interruptedSummaryIsNotResentOnRecovery() = runBlocking {
        val dir = Files.createTempDirectory("context-summary-recovery")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db)
            val old = store(db)
            val id = assertIs<KotlinTurnAdmission.Accepted>(old.admit(command())).turnId
            assertEquals(KotlinTurnPhase.SUMMARY_PENDING, old.snapshot(id).phase)
            assertTrue(old.claim(id, false)); old.beginSummary(id); old.release(id)
            var calls = 0
            val current = store(db)
            val runner = KotlinChatTurnRunner(current, KotlinTurnProvider { _, _ -> flow {
                calls++; emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop"))
            } }, KotlinTurnToolExecutor { _, _ -> error("tools disabled") }, scope)
            runner.recover(id)
            withTimeout(5000) { runner.awaitStopped(id) }
            assertEquals(1, calls)
            assertEquals(KotlinTurnPhase.FINISHED, current.snapshot(id).phase)
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.status == "error" && it.errorMessage!!.contains("interrupted") })
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun unknownWindowCapsHistoryToTwentyInsteadOfRejecting() = runBlocking {
        val dir = Files.createTempDirectory("context-cap")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db, window = 0)
            val rows = db.rows()
            rows.upsertMessages((16..25).map { i -> MessageRow("m$i", "c", "user", "message $i", null, i.toLong(), "sent", null) })
            val store = store(db)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(command())).turnId
            val request = store.snapshot(id).request
            assertEquals(21, request.messages.size)
            assertEquals("message 7 with sufficient content", request.messages[1].content.jsonPrimitive.content)
            assertEquals("new question", request.messages.last().content.jsonPrimitive.content)
            store.cancel(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
