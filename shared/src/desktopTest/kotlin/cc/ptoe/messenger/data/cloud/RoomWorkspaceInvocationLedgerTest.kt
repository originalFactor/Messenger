/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.CLOUD_REPLACEMENT_LOCK_KEY
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProjectRow
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RoomWorkspaceInvocationLedgerTest {
    private val scope = KotlinRemoteControlScope("account", "generation", "https://cloud.example", "device")
    private val cookie = "messenger_session=secret-cookie"
    private val call = RoomInboundWorkspaceCall("call", "sender", "device", "project", "conversation", "read", "{\"secret\":\"secret-argument\"}")
    private fun open(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString()).setDriver(BundledSQLiteDriver()).build()
    private suspend fun seed(db: LocalDatabase) {
        db.rows().upsertValues(listOf(
            KeyValueRow("kotlin_sync_session", "[\"account\",\"generation\"]"), KeyValueRow("device_id", "device"),
            KeyValueRow("cloud_session", cookie), KeyValueRow("cloud_session_host", "https://cloud.example"),
            KeyValueRow("cloud_user_json", "{\"id\":\"account\"}"),
        ))
        db.rows().upsertProjects(listOf(ProjectRow("project", "Project", "/private-workspace", "device", 1, 1)))
    }
    private val stopped = object : WorkspaceInvocationStopBarrier {
        override suspend fun <T> withStopped(ref: WorkspaceInvocationRef, action: suspend () -> T): T = action()
    }

    @Test fun admissionSurvivesReopenAndNeverReplaysTheSameSenderAndCall(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-ledger-restart")
        val path = dir.resolve("db")
        var db = open(path)
        try {
            seed(db)
            val ledger = RoomWorkspaceInvocationLedger(db)
            val admitted = assertIs<WorkspaceInvocationAdmission.Admitted>(ledger.admit(scope, cookie, call))
            assertEquals("/private-workspace", admitted.grant.workspace)
            assertIs<WorkspaceInvocationAdmission.Busy>(ledger.admit(scope, cookie, call.copy(callId = "other")))
            assertIs<WorkspaceInvocationAdmission.Duplicate>(ledger.admit(scope, cookie, call.copy(argumentsJson = "changed")))
            val raw = db.rows().valuesByPrefix("kotlin_workspace_invocation").joinToString { it.value }
            assertFalse(raw.contains("secret-cookie") || raw.contains("secret-argument") || raw.contains("/private-workspace"))
            db.close(); db = open(path)
            val reopened = RoomWorkspaceInvocationLedger(db)
            val pending = assertNotNull(reopened.pending())
            assertFails { reopened.acknowledgeUnknown(pending, object : WorkspaceInvocationStopBarrier {
                override suspend fun <T> withStopped(ref: WorkspaceInvocationRef, action: suspend () -> T): T = error("worker may still run")
            }) }
            assertNotNull(reopened.pending())
            reopened.acknowledgeUnknown(pending, stopped)
            assertNull(reopened.pending())
            assertIs<WorkspaceInvocationAdmission.Duplicate>(reopened.admit(scope, cookie, call))
            db.rows().upsertValues(listOf(KeyValueRow("kotlin_sync_session", "[\"account\",\"new-generation\"]")))
            assertIs<WorkspaceInvocationAdmission.Duplicate>(reopened.admit(scope.copy(generation = "new-generation"), cookie, call))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun invalidAuthorityNeverCreatesAReservation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-ledger-authority")
        val db = open(dir.resolve("db"))
        try {
            seed(db)
            val ledger = RoomWorkspaceInvocationLedger(db)
            assertFails { ledger.admit(scope, "other-cookie", call) }
            assertFails { ledger.admit(scope, cookie, call.copy(name = "edit")) }
            db.rows().upsertProjects(listOf(ProjectRow("project", "Project", "/foreign", "other-device", 1, 1)))
            assertFails { ledger.admit(scope, cookie, call) }
            db.rows().upsertProjects(listOf(ProjectRow("project", "Project", "/private-workspace", "device", 1, 1)))
            db.rows().upsertValues(listOf(KeyValueRow(CLOUD_REPLACEMENT_LOCK_KEY, "locked")))
            assertFails { ledger.admit(scope, cookie, call) }
            assertNull(ledger.pending())
            assertFalse(db.rows().valuesByPrefix("kotlin_workspace_invocation_v1:").any())
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun concurrentInvocationsShareOneReservationAndStaleProofCannotReleaseIt(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-ledger-race")
        val db = open(dir.resolve("db"))
        try {
            seed(db)
            val ledger = RoomWorkspaceInvocationLedger(db)
            val first = async { ledger.admit(scope, cookie, call) }
            val second = async { ledger.admit(scope, cookie, call.copy(callId = "other")) }
            val results = listOf(first.await(), second.await())
            val admitted = results.filterIsInstance<WorkspaceInvocationAdmission.Admitted>().single()
            assertEquals(1, results.count { it is WorkspaceInvocationAdmission.Busy })
            val stale = WorkspaceInvocationRef(admitted.ref.key, java.util.UUID.randomUUID().toString())
            assertFails { ledger.complete(stale, stopped) }
            assertNotNull(ledger.pending())
            ledger.complete(admitted.ref, stopped)
            assertNull(ledger.pending())
            assertFails { ledger.complete(admitted.ref, stopped) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun missingIndexOrMalformedHistoryCannotHidePriorInvocation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-ledger-corrupt")
        val db = open(dir.resolve("db"))
        try {
            seed(db)
            val ledger = RoomWorkspaceInvocationLedger(db)
            val admitted = assertIs<WorkspaceInvocationAdmission.Admitted>(ledger.admit(scope, cookie, call))
            val activeKey = "kotlin_workspace_invocation_active_v1"
            val originalIndex = assertNotNull(db.rows().getValue(activeKey))
            db.rows().deleteValue(activeKey)
            assertFails { ledger.pending() }
            assertFails { ledger.admit(scope, cookie, call.copy(callId = "another")) }
            db.rows().upsertValues(listOf(originalIndex))
            ledger.acknowledgeUnknown(admitted.ref, stopped)
            val record = assertNotNull(db.rows().getValue(admitted.ref.key))
            db.rows().upsertValues(listOf(record.copy(value = "{\"state\":\"invalid\"}")))
            assertFails { ledger.pending() }
            assertFails { ledger.admit(scope, cookie, call.copy(callId = "another")) }
            db.rows().upsertValues(listOf(record.copy(value = "x".repeat(1_025))))
            assertFails { ledger.pending() }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
