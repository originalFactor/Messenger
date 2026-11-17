/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudReplacementExecutorTest {
    private val deletion = PendingSyncChange(SyncCollection.AGENT, "old", SyncMutation.DELETE, 0)
    private val plan = CloudReplacementPlan(10, 4, "local", "remote", listOf(deletion))
    private class Ops : CloudReplacementOperations {
        var remote = 10L; var local = 4L; var count = 0; var driftOnDelete = false; var failUpload = false; var cancelOnDelete = false
        override suspend fun cloudVersion() = remote
        override suspend fun localRevision() = local
        override suspend fun delete(change: PendingSyncChange): Long { count++; if (cancelOnDelete) throw CancellationException("cancel"); remote += if (driftOnDelete) 2 else 1; return remote }
        override suspend fun prepareLocal(plan: CloudReplacementPlan): Long { local++; return local }
        override suspend fun upload(): Long { if (failUpload) error("network error"); return ++remote }
    }
    @Test fun reportsConflictBeforeAnyDeletion(): Unit = runBlocking {
        val ops = Ops(); ops.remote++
        val error = assertFailsWith<CloudReplacementFailure> { executeCloudReplacement(plan, ops) }
        assertTrue(error.completedDeletions.isEmpty()); assertFalse(error.localPrepared); assertEquals(0, ops.count)
    }
    @Test fun reportsPartialSuccessWhenConcurrentWriteInterleaves(): Unit = runBlocking {
        val ops = Ops(); ops.driftOnDelete = true
        val error = assertFailsWith<CloudReplacementFailure> { executeCloudReplacement(plan, ops) }
        assertEquals(listOf(deletion), error.completedDeletions); assertFalse(error.localPrepared); assertEquals(1, ops.count)
    }
    @Test fun reportsLocalPreparationWhenUploadFails(): Unit = runBlocking {
        val ops = Ops(); ops.failUpload = true
        val error = assertFailsWith<CloudReplacementFailure> { executeCloudReplacement(plan, ops) }
        assertEquals(listOf(deletion), error.completedDeletions); assertTrue(error.localPrepared)
    }
    @Test fun successfulReplacementDoesNotRetry(): Unit = runBlocking {
        val ops = Ops()
        assertEquals(12, executeCloudReplacement(plan, ops)); assertEquals(1, ops.count)
    }
    @Test fun cancellationRetainsUnknownOutcomeAndPropagates(): Unit = runBlocking {
        val ops = Ops(); ops.cancelOnDelete = true
        var latest: CloudReplacementProgress? = null
        val error = assertFailsWith<CancellationException> { executeCloudReplacement(plan, ops) { latest = it } }
        assertTrue(latest!!.outcomeMayBeUnknown)
        assertEquals("delete agents/old", latest!!.currentStep)
        assertTrue(error.suppressedExceptions.any { it is CloudReplacementFailure })
    }

}
