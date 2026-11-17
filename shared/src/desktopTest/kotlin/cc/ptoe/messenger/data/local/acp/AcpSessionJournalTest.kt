/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.repository.room.AcpSessionMutationGate
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcpSessionJournalTest {
    @Test fun transcriptIsIdempotentAndKeepsUnknownCoreFields(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-journal")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val path = Files.createDirectory(dir.resolve("project")).toString()
            db.rows().upsertValues(listOf(KeyValueRow("device_id", "device")))
            db.rows().upsertProjects(listOf(ProjectRow("project", "Project", path, "device", 1, 1)))
            val gate = object : AcpSessionMutationGate {
                override suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T): T = action()
            }
            val binding = AcpWorkerBinding("acp:one", "agent", "project", path, "foreign-session", false)
            RoomAcpSessionStore(db, gate).create(binding, "Agent", 1)
            val old = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject
            val record = old["acp:one"]!!.jsonObject
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", JsonObject(old + ("acp:one" to JsonObject(record + ("future" to JsonPrimitive("keep"))))).toString())))
            var active = "turn"
            val guard = object : AcpActiveTurnGuard {
                override suspend fun <T> withActiveTurn(sessionId: String, turnId: String, action: suspend () -> T): T {
                    check(sessionId == "acp:one" && turnId == active)
                    return action()
                }
            }
            val journal = RoomAcpSessionJournal(db, guard)
            val content = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "question") }))
            journal.begin("acp:one", "turn", "question", content, 2)
            journal.begin("acp:one", "turn", "question", content, 2)
            assertFailsWith<IllegalStateException> { journal.begin("acp:one", "turn", "changed", content, 2) }
            val events = JsonArray(listOf(buildJsonObject { put("type", "ToolUpdate"); put("future", buildJsonObject { put("x", 1) }) }))
            journal.finish("acp:one", "turn", "partial", events, "failure", 3)
            journal.finish("acp:one", "turn", "partial", events, "failure", 3)
            val saved = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject["acp:one"]!!.jsonObject
            assertEquals("keep", saved["future"]!!.jsonPrimitive.content)
            assertEquals(listOf("user", "assistant", "assistant"), saved["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals("error", saved["messages"]!!.jsonArray.last().jsonObject["status"]!!.jsonPrimitive.content)
            assertEquals(1, saved["messages"]!!.jsonArray[1].jsonObject["events"]!!.jsonArray.single().jsonObject["future"]!!.jsonObject["x"]!!.jsonPrimitive.int)
            active = "other"
            assertFailsWith<IllegalStateException> { journal.begin("acp:one", "turn", "question", content, 4) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun reconnectReportsEvictionAndKeepsOnlyLivePermission(): Unit = runBlocking {
        val replay = AcpEventBuffer(maxEvents = 2, maxBytes = 2048)
        replay.append(buildJsonObject { put("type", "first") })
        replay.permission(AcpPermissionPrompt("turn", listOf(AcpVisibleOption("choice", "Allow", "allow_once")), "title", "detail"))
        replay.append(buildJsonObject { put("type", "second") })
        replay.append(buildJsonObject { put("type", "third") })
        val snapshot = replay.snapshot(0)
        assertTrue(snapshot.truncated)
        assertEquals(listOf("second", "third"), snapshot.events.map { it.event["type"]!!.jsonPrimitive.content })
        assertEquals("choice", snapshot.pendingPermission!!.options.single().id)
        assertFalse(replay.snapshot(snapshot.events.first().sequence).truncated)
        replay.permission(null)
        replay.complete()
        val completed = replay.snapshot(snapshot.latestSequence)
        assertTrue(completed.completed)
        assertNull(completed.pendingPermission)
        assertFailsWith<IllegalStateException> { replay.append(buildJsonObject { put("type", "late") }) }
    }
}
