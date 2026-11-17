/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.schedule

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoomScheduledTasksTest {
    @Test fun importedTasksAreClaimedOnceAndInterruptedRunsAreNotRepeated(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-room")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val agent = Json.parseToJsonElement("""{"_id":"agent","isDefault":true}""").jsonObject.agentRow(null)
            db.rows().upsertAgents(listOf(agent))
            val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
            val old = """{"id":"$id","name":"Existing","prompt":"run","agentId":"agent","projectId":null,"intervalMinutes":5,"nextRunAt":1000,"enabled":true,"lastRunAt":null,"lastError":null,"lastConversationId":null,"allowedTools":["read"]}"""
            db.rows().upsertValues(listOf(KeyValueRow("scheduled_task:$id", old)))
            val store = RoomScheduledTasks(db)
            assertEquals(listOf("read"), store.list().single().allowedTools)
            val (task, claim) = store.claimDue(2000).single()
            assertEquals(302000, task.nextRunAt)
            assertTrue(task.lastError!!.contains("interrupted"))
            assertTrue(store.claimDue(302000).isEmpty())
            assertTrue(store.recordConversation(claim, "conversation"))
            assertFalse(store.recordConversation(claim, "other"))
            val paused = store.update(task.copy(enabled = false))
            assertEquals(listOf("read"), paused.allowedTools)
            assertTrue(store.active(id)!!.cancelRequested)
            assertFalse(store.recordConversation(claim, "other"))
            assertTrue(store.finish(claim, null, null))
            assertEquals("Execution cancelled", store.list().single().lastError)
            assertEquals("conversation", store.list().single().lastConversationId)
            assertFalse(store.finish(claim, null, null))
            store.update(store.list().single().copy(enabled = true, nextRunAt = 3000))
            val (_, interrupted) = store.claimDue(3000).single()
            assertFailsWith<IllegalStateException> { store.update(task) }
            store.recoverInterrupted()
            assertFalse(store.finish(interrupted, null, null))
            assertTrue(store.claimDue(3000).isEmpty())
            assertTrue(store.list().single().lastError!!.contains("interrupted"))
            val (_, deleting) = store.claimDue(303000).single()
            assertEquals(deleting.token, store.delete(id)!!.token)
            assertTrue(store.active(id)!!.cancelRequested)
            assertTrue(store.finish(deleting, null, null))
            assertTrue(store.list().isEmpty())
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun toolCreationRechecksTurnSettingPolicyAndKeepsAuthorizationCeiling(): Unit = runBlocking {
        val dir = Files.createTempDirectory("schedule-tool")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val rows = db.rows()
            val agent = Json.parseToJsonElement("""{"_id":"agent","isDefault":true,"defaultModelId":"m","toolsEnabled":true}""").jsonObject.agentRow(null)
            rows.upsertAgents(listOf(agent))
            rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://provider.test", "key", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 100, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
            rows.upsertConversations(listOf(Json.parseToJsonElement("""{"_id":"chat","agentId":"agent","writable":true}""").jsonObject.conversationRow()))
            val args = """{"name":"Reminder","prompt":"Do work","intervalMinutes":10,"firstRunDelayMinutes":1,"projectless":true}"""
            fun turn(phase: String) = kotlinx.serialization.json.buildJsonObject {
                put("id", kotlinx.serialization.json.JsonPrimitive("turn")); put("conversationId", kotlinx.serialization.json.JsonPrimitive("chat"))
                put("phase", kotlinx.serialization.json.JsonPrimitive(phase)); put("index", kotlinx.serialization.json.JsonPrimitive(0))
                put("calls", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive("call")); put("name", kotlinx.serialization.json.JsonPrimitive(SCHEDULE_TOOL)); put("arguments", kotlinx.serialization.json.JsonPrimitive(args))
                })))
                put("request", kotlinx.serialization.json.buildJsonObject { put("tools", kotlinx.serialization.json.JsonArray(listOf("read", SCHEDULE_TOOL, "messenger_agents").map { name ->
                    kotlinx.serialization.json.buildJsonObject { put("function", kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive(name)) }) }
                })) })
            }
            rows.upsertValues(listOf(KeyValueRow("kotlin_turn_v1:turn", turn("EXECUTING_TOOL").toString()), KeyValueRow("kotlin_turn_active_v1:chat", "turn")))
            val store = RoomScheduledTasks(db)
            val call = ToolCallData("call", SCHEDULE_TOOL, args)
            val allowed = ScheduleCreationAuthorization { _, _ -> }
            assertFailsWith<IllegalStateException> { store.createFromTool("turn", call, 1000, allowed) }
            store.setToolEnabled(true)
            val created = store.createFromTool("turn", call, 1000, allowed)
            assertEquals(61000, created.nextRunAt)
            assertEquals(listOf("read"), created.allowedTools)
            assertEquals(created, store.createFromTool("turn", call, 1000, allowed))
            assertEquals(1, store.list().size)
            val (_, claim) = store.claimDue(61000).single()
            val scope = store.executionScope(claim, setOf("read", "write", SCHEDULE_TOOL, "messenger_agents"))
            assertEquals(setOf("read"), scope.allowedTools)
            rows.upsertAgents(listOf(agent.copy(toolsEnabled = false)))
            assertTrue(store.executionScope(claim, setOf("read")).allowedTools.isEmpty())
            rows.upsertAgents(listOf(agent))
            rows.upsertValues(listOf(KeyValueRow("kotlin_turn_v1:turn", turn("CANCELLING_TOOL").toString())))
            assertFailsWith<IllegalStateException> { store.createFromTool("turn", call, 1000, allowed) }
            val adapter = ScheduleToolAdapter(store, ScheduleCreationAuthorization { _, _ -> error("not authorized") }) { 1000 }
            assertTrue(adapter.execute("turn", call).isError)
            assertNotNull(store.list().single())
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
