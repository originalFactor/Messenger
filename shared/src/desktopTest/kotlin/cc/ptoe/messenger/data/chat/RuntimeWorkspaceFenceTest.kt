/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeWorkspaceFenceTest {
    @Test fun mutationWaitsForTheActualOperationAndUnknownStopBlocksFurtherWork(): Unit = runBlocking {
        val dir = Files.createTempDirectory("workspace-fence")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        try {
            val fence = RuntimeWorkspaceFence(db)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val token = "a".repeat(64)
            val failed = "b".repeat(64)
            var stopped = false
            val host = object : TrackedWorkspaceHost {
                override val protocolAvailable = true
                override val supportedOperations = setOf("read")
                override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult = error("must not replay")
                override suspend fun status(token: String): TrackedWorkspaceStatus {
                    assertEquals(failed, token)
                    return if (stopped) TrackedWorkspaceStatus.STOPPED else TrackedWorkspaceStatus.UNKNOWN
                }
            }
            val invocation = async {
                fence.execute(token, host) { dispatch ->
                    dispatch(); started.complete(Unit); release.await(); "done"
                }
            }
            withTimeout(5_000) { started.await() }
            val mutation = async(start = CoroutineStart.UNDISPATCHED) {
                fence.mutate { db.rows().upsertValues(listOf(KeyValueRow("workspace-owner", "new"))) }
            }
            assertFalse(mutation.isCompleted)
            assertEquals(null, db.rows().getValue("workspace-owner"))
            release.complete(Unit)
            assertEquals("done", withTimeout(5_000) { invocation.await() })
            withTimeout(5_000) { mutation.await() }
            assertEquals("new", db.rows().getValue("workspace-owner")?.value)
            assertFailsWith<IllegalStateException> { fence.execute(token, host) { it(); Unit } }

            assertFailsWith<IllegalStateException> { fence.execute(failed, host) { it(); error("connection lost") } }
            var mutationRan = false
            assertFailsWith<IllegalStateException> { fence.mutate { mutationRan = true } }
            assertFalse(mutationRan)
            assertFailsWith<IllegalStateException> { fence.recover(host) }
            val wrongHost = object : TrackedWorkspaceHost by host {}
            stopped = true
            assertFailsWith<IllegalStateException> { fence.recover(wrongHost) }
            fence.recover(host)
            assertTrue(fence.mutate { true })
            assertFailsWith<IllegalStateException> { fence.execute(failed, host) { it(); Unit } }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
