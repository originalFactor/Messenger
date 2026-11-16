/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProjectRow
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RoomAcpWorkspaceAuthorityTest {
    @Test fun existingSessionMustMatchTheLocallyOwnedWorkspace(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-workspace")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val workspace = Files.createDirectory(dir.resolve("project")).toString()
            val rows = db.rows()
            val record = buildJsonObject {
                put("id", "acp:one"); put("profile", "agent"); put("project", "project"); put("cwd", workspace)
                put("agent_session", "session"); put("delegated", true)
            }
            rows.upsertValues(listOf(KeyValueRow("device_id", "device"), KeyValueRow("local_acp_sessions", JsonObject(mapOf("acp:one" to record)).toString())))
            rows.upsertProjects(listOf(ProjectRow("project", "Project", workspace, "device", 1, 1)))
            val authority = RoomAcpWorkspaceAuthority(db)
            val binding = AcpWorkerBinding("acp:one", "agent", "project", workspace, "session", true)
            authority.requireLocalUnchanged(binding)
            assertFailsWith<IllegalStateException> { authority.requireLocalUnchanged(binding.copy(delegated = false)) }
            assertFailsWith<IllegalStateException> { authority.requireLocalUnchanged(binding.copy(agentSessionId = "other")) }
            rows.upsertProjects(listOf(ProjectRow("project", "Project", workspace, "other-device", 1, 1)))
            assertFailsWith<IllegalStateException> { authority.requireLocalUnchanged(binding) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
