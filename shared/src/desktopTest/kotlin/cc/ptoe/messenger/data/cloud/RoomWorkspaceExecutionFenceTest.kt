/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProjectRow
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class RoomWorkspaceExecutionFenceTest {
    private val scope = KotlinRemoteControlScope("account", "generation", "https://cloud.example", "device")
    private val call = RoomInboundWorkspaceCall("call", "sender", "device", "project", "conversation", "read", "{}")
    private suspend fun fixture(block: suspend (LocalDatabase, RoomWorkspaceExecutionFence) -> Unit) {
        val dir = Files.createTempDirectory("workspace-execution-fence")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            db.rows().upsertValues(listOf(
                KeyValueRow("kotlin_sync_session", "[\"account\",\"generation\"]"), KeyValueRow("device_id", "device"),
                KeyValueRow("cloud_session", "messenger_session=secret"), KeyValueRow("cloud_session_host", "https://cloud.example"),
                KeyValueRow("cloud_user_json", "{\"id\":\"account\"}"),
            ))
            db.rows().upsertProjects(listOf(ProjectRow("project", "Project", "/workspace", "device", 1, 1)))
            block(db, RoomWorkspaceExecutionFence.forDatabase(db))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun writerWaitsUntilWorkerStopsAndOldSessionCannotReadmit(): Unit = runBlocking {
        fixture { db, gate ->
            assertSame(gate, RoomWorkspaceExecutionFence.forDatabase(db))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val work = async { gate.execute(scope, call) { grant ->
                assertEquals("/workspace", grant.workspace); entered.complete(Unit); release.await()
                WorkspaceExecutionOutcome.Stopped("done", false)
            } }
            withTimeout(5_000) { entered.await() }
            val mutation = async { gate.mutate { db.rows().upsertValues(listOf(KeyValueRow("kotlin_sync_session", "[\"account\",\"new\"]"))) } }
            delay(100)
            assertFalse(mutation.isCompleted)
            release.complete(Unit)
            assertEquals("done", withTimeout(5_000) { work.await() }.output)
            withTimeout(5_000) { mutation.await() }
            assertFailsWith<IllegalStateException> { gate.execute(scope, call.copy(callId = "new")) { WorkspaceExecutionOutcome.Stopped("unexpected", false) } }
            assertFailsWith<IllegalStateException> { gate.execute(scope, call) { WorkspaceExecutionOutcome.Stopped("unexpected", false) } }
        }
    }

    @Test fun unknownStopBlocksBothFutureWorkAndProtectedMutations(): Unit = runBlocking {
        fixture { _, gate ->
            assertFailsWith<IllegalStateException> { gate.execute(scope, call) { WorkspaceExecutionOutcome.Unknown } }
            assertFailsWith<IllegalStateException> { gate.mutate { Unit } }
            assertFailsWith<IllegalStateException> { gate.execute(scope, call.copy(callId = "new")) { WorkspaceExecutionOutcome.Stopped("unexpected", false) } }
        }
    }
}
