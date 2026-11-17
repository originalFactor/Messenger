/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.MessageRow
import cc.ptoe.messenger.data.local.room.ProjectRow
import java.nio.file.Files
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomDeviceControlDispatcherTest {
    private suspend fun runWithStore(block: suspend (LocalDatabase, RoomDeviceControlDispatcher) -> Unit) {
        val dir = Files.createTempDirectory("room-remote-control")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val session = SyncSession("account", "generation")
        val stored = StoredCloudSession(session, "messenger_session=secret", "https://cloud.example", CloudUser(id = "account"))
        try {
            val rows = db.rows()
            rows.upsertValues(listOf(
                KeyValueRow("kotlin_sync_session", """["account","generation"]"""),
                KeyValueRow("device_id", "device"), KeyValueRow("cloud_session", "messenger_session=secret"),
                KeyValueRow("cloud_session_host", "https://cloud.example"), KeyValueRow("cloud_user_json", """{"id":"account"}"""),
                KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, "1"), KeyValueRow("device_control_grant", """["https://cloud.example","account"]"""),
                KeyValueRow("device_control_config", """{"enabled":true,"agents":[{"id":"local","name":"Local","command":"SECRET"},{"id":"off","name":"Off","enabled":false}]}"""),
                KeyValueRow("local_acp_sessions", """{"acp:one":{"id":"acp:one","title":"ACP","project":"local","profile":"local","updated_at":5,"cwd":"SECRET","messages":[{"id":"acp-msg","role":"user","text":"hello","status":"sent"}]}}"""),
            ))
            rows.upsertAgents(listOf(AgentRow("agent", "Agent", null, "SECRET", "", null, null, null, null, null,
                true, false, false, false, false, false, false, null, null, null, "chat", false, false, "", 1, 1)))
            rows.upsertProjects(listOf(ProjectRow("local", "Local", "/secret/path", "device", 1, 1),
                ProjectRow("foreign", "Foreign", "/other", "other-device", 1, 1)))
            fun conv(id: String, project: String?) = ConversationRow(id, id, "provider", "agent", project, null,
                null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)
            rows.upsertConversations(listOf(conv("local-chat", "local"), conv("foreign-chat", "foreign"), conv("no-project", null)))
            rows.upsertMessages((1..25).map { MessageRow("m$it", "local-chat", "user", "message $it", null, it.toLong(), "sent", null) } +
                MessageRow("foreign-msg", "foreign-chat", "assistant", "foreign transcript", null, 1, "sent", null))
            val dispatcher = RoomDeviceControlDispatcher(db, stored, "device", RoomRemoteTaskSnapshots { id ->
                if (id == "foreign-chat") RoomRemoteTaskSnapshot(true, "request", listOf(buildJsonObject { put("type", "TextDelta"); put("text", "working"); put("private", "SECRET") }))
                else if (id == "local-chat") RoomRemoteTaskSnapshot(false, "completed", listOf(
                    buildJsonObject { put("type", "SummaryFailed"); put("message", "summary error"); put("private", "SECRET") },
                    buildJsonObject { put("type", "TitleFailed"); put("message", "title error") },
                    buildJsonObject { put("type", "ApprovalFailed"); put("message", "review error"); put("private", "SECRET") },
                    buildJsonObject { put("type", "Error"); put("message", "HTTP 429"); put("detail", "quota exhausted") },
                ))
                else RoomRemoteTaskSnapshot(false)
            })
            block(db, dispatcher)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
    private suspend fun RoomDeviceControlDispatcher.request(action: String, sessionId: String? = null, before: String? = null): JsonObject =
        dispatch(1L, buildJsonObject { put("action", action); sessionId?.let { put("sessionId", it) }; before?.let { put("before", it) } }, Channel(1)) {}
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test fun catalogAndHistoryExposeOnlyOwnedProjectsWithoutCredentials(): Unit = runBlocking {
        runWithStore { _, dispatcher ->
            val catalog = dispatcher.request("catalog")
            assertEquals("Catalog", catalog.text("type"))
            val body = catalog.toString()
            assertTrue(body.contains("local-chat") && body.contains("acp:one") && body.contains("acp:local"))
            assertFalse(body.contains("foreign-chat") || body.contains("no-project") || body.contains("SECRET") || body.contains("/secret/path"))
            val first = dispatcher.request("history", "local-chat")
            assertEquals("History", first.text("type"))
            assertEquals("m6", first.text("before"))
            assertEquals(20, (first["messages"] as JsonArray).size)
            val older = dispatcher.request("history", "local-chat", first.text("before"))
            assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), (older["messages"] as JsonArray).map { (it as JsonObject).text("id") })
            assertEquals("project_unavailable", dispatcher.request("history", "foreign-chat").text("code"))
            assertEquals("project_unavailable", dispatcher.request("history", "no-project").text("code"))
            assertEquals("History", dispatcher.request("history", "acp:one").text("type"))
            assertEquals("unavailable", dispatcher.request("create").text("code"))
        }
    }

    @Test fun exactNotificationAndAccountGrantAreRechecked(): Unit = runBlocking {
        runWithStore { db, dispatcher ->
            val opened = dispatcher.request("notificationOpen", "foreign-chat")
            assertEquals("Session", opened.text("type"))
            assertEquals("request", opened.text("requestId"))
            assertTrue(opened.toString().contains("foreign transcript"))
            assertFalse(opened.toString().contains("SECRET"))
            val completed = dispatcher.request("notificationOpen", "local-chat").toString()
            assertTrue(completed.contains("SummaryFailed") && completed.contains("TitleFailed") && completed.contains("ApprovalFailed") && completed.contains("review error") && completed.contains("HTTP 429") && completed.contains("quota exhausted"))
            assertFalse(completed.contains("SECRET"))
            assertEquals("not_found", dispatcher.request("notificationOpen", "missing").text("code"))
            db.rows().upsertValues(listOf(KeyValueRow("device_control_grant", """["https://cloud.example","other-account"]""")))
            assertEquals("control_disabled", dispatcher.request("catalog").text("code"))
            assertEquals("control_disabled", dispatcher.request("notificationOpen", "foreign-chat").text("code"))
            db.rows().upsertValues(listOf(KeyValueRow("device_control_grant", """["https://cloud.example","account"]"""), KeyValueRow("kotlin_sync_session", """["account","different"]""")))
            assertEquals("control_disabled", dispatcher.request("catalog").text("code"))
        }
    }

    @Test fun boundedAcpPreviewsMarkOmittedMediaAndToolDetails(): Unit = runBlocking {
        runWithStore { db, dispatcher ->
            val image = "a".repeat(230_000)
            val messages = JsonArray(listOf(
                Json.parseToJsonElement("""{"id":"plain","role":"user","text":"hello","status":"sent","content":[{"type":"text","text":"hello"}]}"""),
                buildJsonObject {
                    put("id", "media"); put("role", "assistant"); put("text", ""); put("status", "sent")
                    put("content", JsonArray(listOf(buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", image) })))
                    put("events", JsonArray(listOf(buildJsonObject { put("type", "ToolUpdate"); put("update", buildJsonObject { put("title", "tool") }) })))
                },
            ))
            val record = buildJsonObject {
                put("id", "acp:one"); put("title", "ACP"); put("project", "local"); put("messages", messages)
            }
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", JsonObject(mapOf("acp:one" to record)).toString())))

            for (action in listOf("history", "notificationOpen")) {
                val result = dispatcher.request(action, "acp:one")
                val returned = (result.getValue("messages") as JsonArray).map { it as JsonObject }
                assertEquals(listOf("plain", "media"), returned.map { it.text("id") })
                assertFalse((returned.first()["detailsOmitted"] as? JsonPrimitive)?.booleanOrNull == true)
                assertEquals(true, (returned.last()["detailsOmitted"] as JsonPrimitive).booleanOrNull)
                assertFalse(result.toString().contains(image))
            }
            assertTrue(checkNotNull(db.rows().getValue("local_acp_sessions")).value.contains(image))
        }
    }
}
