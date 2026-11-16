/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.chat.KotlinTurnRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinTurnToolPolicy
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.KotlinChatTurnRunner
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Provider
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomRemoteTurnOperationsTest {
    private suspend fun fixture(provider: KotlinTurnProvider, run: suspend (LocalDatabase, RoomDeviceControlDispatcher, RoomKotlinTurnStore) -> Unit) {
        val dir = Files.createTempDirectory("room-remote-turn")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val workers = CoroutineScope(Dispatchers.Default + Job())
        try {
            val rows = db.rows()
            rows.upsertValues(listOf(
                KeyValueRow("kotlin_sync_session", """["account","generation"]"""), KeyValueRow("device_id", "device"),
                KeyValueRow("cloud_session", "messenger_session=secret"), KeyValueRow("cloud_session_host", "https://cloud.example"),
                KeyValueRow("cloud_user_json", """{"id":"account"}"""), KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, "1"), KeyValueRow("device_control_grant", """["https://cloud.example","account"]"""),
                KeyValueRow("device_control_config", """{"enabled":true}"""),
            ))
            rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "private-key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 1000, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "system", defaultModelId = "m", temperature = 0.7f,
                topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            rows.upsertProjects(listOf(ProjectRow("project", "Project", "/workspace", "device", 1, 1)))
            rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", "project", null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
            val preparer = KotlinTurnRequestPreparer { _, _, _, model, _, _ -> ChatCompletionRequestDto(model.modelId, emptyList()) }
            val store = RoomKotlinTurnStore(db, preparer, KotlinTurnToolPolicy { _, _, _ -> false })
            val runner = KotlinChatTurnRunner(store, provider, KotlinTurnToolExecutor { _, _ -> error("Unexpected tool") }, workers)
            val stored = StoredCloudSession(SyncSession("account", "generation"), "messenger_session=secret", "https://cloud.example", CloudUser(id = "account"))
            val dispatcher = RoomDeviceControlDispatcher(db, stored, "device", RoomRemoteTaskSnapshots { RoomRemoteTaskSnapshot(false) }, RoomRemoteTurnOperations(store, runner))
            run(db, dispatcher, store)
        } finally { workers.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    private fun prompt() = buildJsonObject { put("action", "prompt"); put("sessionId", "c"); put("text", "hello") }
    @Test fun authorizedPromptStreamsPersistedReplyAndRevocationBlocksNextAdmission(): Unit = runBlocking {
        val provider = KotlinTurnProvider { _, _ -> flow { emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop")) } }
        fixture(provider) { db, dispatcher, _ ->
            val updates = mutableListOf<JsonObject>()
            val result = withTimeout(5_000) { dispatcher.dispatch(1, prompt(), Channel(1)) { updates.add(it) } }
            assertEquals("Finished", result.getValue("type").jsonPrimitive.content)
            assertTrue(updates.any { it["text"]?.jsonPrimitive?.content == "answer" })
            assertFalse(updates.toString().contains("private-key"))
            assertEquals(listOf("hello", "answer"), db.rows().messagesFor(listOf("c")).map { it.content })
            db.rows().upsertValues(listOf(KeyValueRow("device_control_config", """{"enabled":false}""")))
            val denied = dispatcher.dispatch(1, prompt(), Channel(1)) {}
            assertEquals("control_disabled", denied.getValue("code").jsonPrimitive.content)
            assertEquals(2, db.rows().messagesFor(listOf("c")).size)
        }
    }

    @Test fun disconnectCancelsAndJoinsAdmittedWorker(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val provider = KotlinTurnProvider { _, _ -> flow {
            try { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            finally { stopped.complete(Unit) }
        } }
        fixture(provider) { db, dispatcher, _ ->
            val request = async { dispatcher.dispatch(1, prompt(), Channel(1)) {} }
            withTimeout(5_000) { started.await() }
            withTimeout(5_000) { request.cancelAndJoin(); stopped.await() }
            assertNull(db.rows().getValue("kotlin_turn_active_v1:c"))
            assertEquals("sent", db.rows().messagesFor(listOf("c")).last().status)
        }
    }

    @Test fun completedTitleFailureRemainsAvailableToNotificationPolling(): Unit = runBlocking {
        val provider = object : KotlinTurnProvider {
            override fun stream(provider: Provider, request: ChatCompletionRequestDto): Flow<ChatStreamEvent> =
                flow { emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop")) }
            override fun complete(provider: Provider, request: ChatCompletionRequestDto): Flow<ChatStreamEvent> =
                flow { emit(ChatStreamEvent.Error("title service failed")) }
        }
        fixture(provider) { db, dispatcher, store ->
            val agent = checkNotNull(db.rows().agent("a"))
            db.rows().upsertAgents(listOf(agent.copy(id = "title", name = "Title", isDefault = false, role = "title")))
            db.rows().upsertConversations(listOf(checkNotNull(db.rows().conversation("c")).copy(title = "New Chat")))
            val result = withTimeout(5_000) { dispatcher.dispatch(1, prompt(), Channel(1)) {} }
            assertEquals("Finished", result.getValue("type").jsonPrimitive.content)
            val snapshots = RoomRemoteTurnSnapshots(store) { null }
            val first = snapshots.snapshot("c")
            val again = snapshots.snapshot("c")
            assertFalse(first.active)
            assertEquals(first, again)
            assertTrue(first.events.any { it["type"]?.jsonPrimitive?.content == "TitleFailed" && it["message"]?.jsonPrimitive?.content?.contains("title service failed") == true })
        }
    }
}
