/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RoomWorkspaceRelayAuthorityTest {
    @Test fun accountProjectAndWritableConversationAreCheckedWithoutControlOptIn(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-relay-authority")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val rows = db.rows()
            rows.upsertValues(listOf(
                KeyValueRow("kotlin_sync_session", """["account","generation"]"""), KeyValueRow("device_id", "device"),
                KeyValueRow("cloud_session", "messenger_session=secret"), KeyValueRow("cloud_session_host", "https://cloud.example"),
                KeyValueRow("cloud_user_json", """{"id":"account"}"""), KeyValueRow("device_control_config", """{"enabled":false}"""),
            ))
            rows.upsertAgents(listOf(AgentRow("a", "Agent", null, "", "", null, null, null, null, null,
                true, false, false, false, false, false, false, null, null, null, "chat", false, false, "", 1, 1)))
            rows.upsertProjects(listOf(ProjectRow("local", "Local", "/workspace", "device", 1, 1),
                ProjectRow("foreign", "Foreign", "/other", "other-device", 1, 1), ProjectRow("legacy", "Legacy", "/legacy", null, 1, 1)))
            fun conv(id: String, project: String, writable: Boolean) = ConversationRow(id, id, "p", "a", project, null,
                null, null, null, null, null, null, writable, 1, 1, null, null, null, 0, 0, 0)
            rows.upsertConversations(listOf(conv("readonly", "local", false), conv("writable", "local", true), conv("other", "foreign", true)))
            val scope = KotlinRemoteControlScope("account", "generation", "https://cloud.example", "device")
            val authority = RoomWorkspaceRelayAuthority(db)
            val call = RoomInboundWorkspaceCall("call", "sender", "device", "local", "readonly", "read", "{\"path\":\"x\"}")
            assertEquals("/workspace", authority.prepare(scope, call).workspace)
            assertFailsWith<IllegalStateException> { authority.prepare(scope, call.copy(name = "edit")) }
            assertFailsWith<IllegalStateException> { authority.prepare(scope, call.copy(name = "terminal")) }
            assertEquals("/workspace", authority.prepare(scope, call.copy(name = "create", conversationId = "writable")).workspace)
            assertFailsWith<IllegalStateException> { authority.prepare(scope, call.copy(projectId = "foreign")) }
            assertFailsWith<IllegalStateException> { authority.prepare(scope, call.copy(projectId = "legacy")) }
            assertFailsWith<IllegalStateException> { authority.prepare(scope, call.copy(name = "edit", conversationId = "other")) }
            assertFailsWith<IllegalArgumentException> { authority.prepare(scope, call.copy(targetDeviceId = "other")) }
            assertFailsWith<IllegalArgumentException> { authority.prepare(scope, call.copy(name = "messenger_learning")) }
            db.rows().upsertValues(listOf(KeyValueRow("kotlin_sync_session", """["account","new-generation"]""")))
            assertFailsWith<IllegalStateException> { authority.prepare(scope, call) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
