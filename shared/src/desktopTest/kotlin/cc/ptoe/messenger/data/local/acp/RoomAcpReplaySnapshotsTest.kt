/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.repository.room.AcpSessionMutationGate
import cc.ptoe.messenger.data.repository.room.RoomAcpConversationStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class RoomAcpReplaySnapshotsTest {
    @Test fun snapshotsKeepRawEventsSeparateDeduplicateAndDeleteWithSession(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-replay-snapshots")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val workspace = Files.createDirectory(dir.resolve("project")).toString()
            db.rows().upsertValues(listOf(KeyValueRow("device_id", "device")))
            db.rows().upsertProjects(listOf(ProjectRow("project", "Project", workspace, "device", 1, 1)))
            val gate = object : AcpSessionMutationGate { override suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T) = action() }
            val guard = object : AcpActiveTurnGuard { override suspend fun <T> withActiveTurn(sessionId: String, turnId: String, action: suspend () -> T) = action() }
            val one = AcpWorkerBinding("acp:one", "agent", "project", workspace, "worker-one", false)
            val two = one.copy(conversationId = "acp:two", agentSessionId = "worker-two")
            val sessions = RoomAcpSessionStore(db, gate)
            sessions.create(one, "One", 1); sessions.create(two, "Two", 1)
            val original = buildJsonObject { put("id", "original"); put("role", "assistant"); put("text", "local error"); put("status", "error"); put("timestamp", 1) }
            val root = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject
            val record = root.getValue(one.conversationId).jsonObject
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", JsonObject(root + (one.conversationId to JsonObject(record + ("messages" to JsonArray(listOf(original)))))).toString())))
            val events = JsonArray(listOf(
                buildJsonObject { put("sessionUpdate", "user_message_chunk"); put("content", buildJsonObject { put("type", "text"); put("text", "same") }) },
                buildJsonObject { put("sessionUpdate", "agent_message_chunk"); put("messageId", "worker-id"); put("content", buildJsonObject { put("type", "image"); put("data", "aW1hZ2U="); put("mimeType", "image/png") }) },
                buildJsonObject { put("sessionUpdate", "future_update"); put("unknown", buildJsonObject { put("keep", true) }) },
            ) + (0 until 102).map { n -> buildJsonObject { put("sessionUpdate", "future_update"); put("n", n) } })
            val projected = JsonArray(listOf(buildJsonObject { put("id", "projected"); put("role", "user"); put("text", "same"); put("status", "sent"); put("timestamp", 2) }))
            val content = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "new") }))
            val journal = RoomAcpSessionJournal(db, guard)
            val replay = AcpCompletedReplay(projected, events)
            journal.begin(one.conversationId, "turn-one", "new", content, 3, replay, one)
            journal.begin(one.conversationId, "turn-one", "new", content, 3, replay, one)
            journal.begin(one.conversationId, "turn-two", "new", content, 4, replay, one)
            val snapshots = RoomAcpReplaySnapshots(db, gate)
            val info = snapshots.list(one.conversationId).single()
            assertEquals(105, info.eventCount)
            val page = snapshots.page(one.conversationId, info.id)
            assertEquals(JsonArray(events.take(100)), page.events)
            assertEquals("worker-one", page.source.getValue("agentSessionId").jsonPrimitive.content)
            assertEquals(100, page.next)
            val last = snapshots.page(one.conversationId, info.id, checkNotNull(page.next))
            assertEquals(JsonArray(events.drop(100)), last.events)
            assertNull(last.next)
            assertTrue(snapshots.list(two.conversationId).isEmpty())
            assertFailsWith<IllegalStateException> { snapshots.page(two.conversationId, info.id) }
            val saved = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject.getValue(one.conversationId).jsonObject.getValue("messages").jsonArray
            assertEquals(original, saved.first())
            assertEquals(listOf("local error", "new", "new"), saved.map { it.jsonObject.getValue("text").jsonPrimitive.content })
            assertFailsWith<IllegalStateException> { journal.begin(one.conversationId, "wrong-binding", "new", content, 4, replay, one.copy(agentSessionId = "changed")) }
            assertNull(db.rows().getValue("kotlin_acp_transcript_v1:wrong-binding"))
            snapshots.delete(one.conversationId, info.id)
            assertTrue(snapshots.list(one.conversationId).isEmpty())
            journal.begin(one.conversationId, "turn-three", "new", content, 5, replay, one)
            assertEquals(1, snapshots.list(one.conversationId).size)
            RoomAcpConversationStore(db, gate).delete(one.conversationId)
            assertNull(db.rows().getValue("kotlin_acp_replay_snapshot_v1:${info.id}"))
            assertFailsWith<IllegalStateException> { snapshots.list(one.conversationId) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun quotaAndCaptureLimitsFailBeforePromptAdmission(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-replay-quota")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val workspace = Files.createDirectory(dir.resolve("project")).toString()
            db.rows().upsertValues(listOf(KeyValueRow("device_id", "device")))
            db.rows().upsertProjects(listOf(ProjectRow("project", "Project", workspace, "device", 1, 1)))
            val gate = object : AcpSessionMutationGate { override suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T) = action() }
            val guard = object : AcpActiveTurnGuard { override suspend fun <T> withActiveTurn(sessionId: String, turnId: String, action: suspend () -> T) = action() }
            val binding = AcpWorkerBinding("acp:one", "agent", "project", workspace, "worker", false)
            RoomAcpSessionStore(db, gate).create(binding, "One", 1)
            val journal = RoomAcpSessionJournal(db, guard)
            val content = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "prompt") }))
            fun replay(n: Int) = AcpCompletedReplay(JsonArray(emptyList()), JsonArray(listOf(buildJsonObject { put("sessionUpdate", "future"); put("n", n) })))
            repeat(8) { journal.begin(binding.conversationId, "turn-$it", "prompt", content, it.toLong() + 2, replay(it), binding) }
            val before = db.rows().getValue("local_acp_sessions")!!.value
            assertFailsWith<IllegalStateException> { journal.begin(binding.conversationId, "turn-overflow", "prompt", content, 11, replay(9), binding) }
            assertEquals(before, db.rows().getValue("local_acp_sessions")!!.value)
            assertNull(db.rows().getValue("kotlin_acp_transcript_v1:turn-overflow"))
            assertEquals(8, RoomAcpReplaySnapshots(db, gate).list(binding.conversationId).size)
            val capture = AcpReplayCapture()
            assertFailsWith<IllegalArgumentException> { capture.append(buildJsonObject { put("content", "x".repeat(192_000)) }) }
            assertTrue(capture.snapshot().isEmpty())
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
