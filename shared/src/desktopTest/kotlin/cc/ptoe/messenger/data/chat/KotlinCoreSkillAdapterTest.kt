/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.local.skill.RoomSkillStore
import cc.ptoe.messenger.data.local.skill.StoredSkill
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class KotlinCoreSkillAdapterTest {
    private suspend fun seed(db: LocalDatabase) {
        val rows = db.rows()
        rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "key", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
        RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "system", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
        rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
        RoomSkillStore(db).create("study", StoredSkill(mapOf("SKILL.md" to "---\nname: study\ndescription: Study guidance\n---\nUse spaced repetition."), enabled = true))
    }

    @Test fun projectlessTurnCanDiscoverAndReadEnabledSkillWithoutApproval() = runBlocking {
        val dir = Files.createTempDirectory("core-skill-turn")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            seed(db)
            lateinit var store: RoomKotlinTurnStore
            val tools = KotlinCoreSkillAdapter.create(db, "c") { store }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tools), tools)
            val calls = mutableListOf<cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto>()
            val runner = KotlinChatTurnRunner(store, KotlinTurnProvider { _, request -> flow {
                calls.add(request)
                if (calls.size == 1) emit(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(ToolCallData("call", "messenger_skills", """{"name":"study"}"""))))
                else { emit(ChatStreamEvent.Content("answer")); emit(ChatStreamEvent.Done("stop")) }
            } }, tools, scope)
            val id = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("c", "help", null))).turnId
            withTimeout(5000) { runner.awaitStopped(id) }
            assertEquals(KotlinTurnPhase.FINISHED, store.snapshot(id).phase)
            assertTrue(calls.first().messages.first().content.toString().contains("Study guidance"))
            assertEquals("messenger_skills", calls.first().tools!!.single().function.name)
            assertTrue(db.rows().messagesFor(listOf("c")).any { it.role == "tool" && it.content.contains("Use spaced repetition.") })
        } finally { scope.cancel(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun changedDiscoveryRejectsAdmissionBeforeTranscriptMutation() = runBlocking {
        val dir = Files.createTempDirectory("core-skill-stale")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            lateinit var store: RoomKotlinTurnStore
            val tools = KotlinCoreSkillAdapter.create(db, "c") { store }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tools), tools)
            RoomSkillStore(db).setEnabled("study", false)
            val result = store.admit(KotlinTurnCommand.Send("c", "help", null))
            assertIs<KotlinTurnAdmission.Rejected>(result)
            assertTrue(db.rows().messagesFor(listOf("c")).isEmpty())
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun disablingAgentBeforeExecutionPreventsSkillRead() = runBlocking {
        val dir = Files.createTempDirectory("core-skill-revoke")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            lateinit var store: RoomKotlinTurnStore
            val tools = KotlinCoreSkillAdapter.create(db, "c") { store }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tools), tools)
            val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "help", null))).turnId
            assertTrue(store.claim(id, false)); store.beginProvider(id)
            val call = ToolCallData("call", "messenger_skills", """{"name":"study"}""")
            store.completeProvider(id, listOf(call), null)
            assertEquals(KotlinTurnPhase.READY_TOOL, store.snapshot(id).phase)
            db.rows().upsertAgents(listOf(checkNotNull(db.rows().agent("a")).copy(toolsEnabled = false)))
            assertFailsWith<IllegalStateException> { store.beginTool(id) }
            assertEquals(KotlinTurnPhase.READY_TOOL, store.snapshot(id).phase)
            store.cancel(id); store.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
