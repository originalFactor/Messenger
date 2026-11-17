/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.LearningOwnership
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class KotlinCoreAssetToolsTest {
    private class Gate : CoreAssetGate {
        private val lock = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T) = lock.withLock { block() }
    }
    private suspend fun seed(db: LocalDatabase) {
        val rows = db.rows()
        rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "key", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
        RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "system", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
        rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
        rows.upsertValues(listOf(KeyValueRow("learning_tools_enabled", "true"), KeyValueRow("artifact_tools_enabled", "true")))
    }
    private suspend fun begin(store: RoomKotlinTurnStore, call: ToolCallData): String {
        val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "help", null))).turnId
        assertTrue(store.claim(id, false)); store.beginProvider(id); store.completeProvider(id, listOf(call), null)
        store.decideApproval(id, call.callId, true); assertEquals(call, store.beginTool(id))
        return id
    }

    @Test fun learningUpsertCommitsCapabilityAndStructuredResultTogether() = runBlocking {
        val dir = Files.createTempDirectory("core-learning-tool")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            lateinit var store: RoomKotlinTurnStore
            val tool = coreLearningTool(db, "c", { store }, LearningOwnership(db, assets)) { 1000 }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tool), tool)
            val call = ToolCallData("call", "messenger_learning", """{"action":"upsert","record":{"id":"note","title":"Notes","source":{"kind":"manual","snapshot":"source"},"content":{"kind":"item","itemKind":"note"}}}""")
            val id = begin(store, call)
            val result = tool.execute(id, call)
            assertFalse(result.isError, result.output)
            assertEquals("learningRecord", result.structuredContent!!.jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("Notes", db.learningReview().record("note")?.title)
            assertFailsWith<IllegalStateException> { tool.execute(id, call) }
            assertEquals(1L, db.learningReview().record("note")?.version)
            store.completeTool(id, call, result); store.cancel(id); store.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun artifactCreateIsBlockedIfOptInIsRevokedBeforeCommit() = runBlocking {
        val dir = Files.createTempDirectory("core-artifact-tool")
        val root = Files.createDirectory(dir.resolve("assets")).also { if (Files.getFileStore(it).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(it, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")) }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            seed(db)
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            val artifacts = RoomArtifactService(db, assets)
            lateinit var store: RoomKotlinTurnStore
            val tool = coreArtifactTool(db, "c", { store }, artifacts) { 1000 }
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tool), tool)
            val call = ToolCallData("call", "messenger_artifacts", """{"action":"create","title":"Demo","files":{"index.html":"<h1>Hello</h1>"}}""")
            val id = begin(store, call)
            db.rows().upsertValues(listOf(KeyValueRow("artifact_tools_enabled", "false")))
            assertFailsWith<IllegalStateException> { tool.execute(id, call) }
            assertTrue(db.rows().valuesByPrefix("artifact:").isEmpty())
            db.rows().upsertValues(listOf(KeyValueRow("artifact_tools_enabled", "true")))
            val result = tool.execute(id, call)
            assertFalse(result.isError, result.output)
            assertEquals("artifact", result.structuredContent!!.jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(1, db.rows().valuesByPrefix("artifact:").size)
            store.completeTool(id, call, result); store.cancel(id); store.release(id)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
