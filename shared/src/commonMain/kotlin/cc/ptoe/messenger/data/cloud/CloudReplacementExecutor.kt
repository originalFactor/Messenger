/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import kotlinx.coroutines.CancellationException

/** Implementations bind every operation to the session used to construct the plan. */
internal interface CloudReplacementOperations {
    suspend fun cloudVersion(): Long
    suspend fun localRevision(): Long
    suspend fun delete(change: PendingSyncChange): Long
    /** Rekey local default and queue the full snapshot atomically, returning its new revision. */
    suspend fun prepareLocal(plan: CloudReplacementPlan): Long
    /** Upload queued records; return the last server version when complete. */
    suspend fun upload(): Long
}

internal data class CloudReplacementProgress(
    val completedDeletions: List<PendingSyncChange> = emptyList(),
    val localPrepared: Boolean = false,
    val currentStep: String? = null,
    val outcomeMayBeUnknown: Boolean = false,
)

internal class CloudReplacementFailure(
    val completedDeletions: List<PendingSyncChange>,
    val localPrepared: Boolean,
    cause: Throwable,
) : IllegalStateException(
    "Cloud replacement stopped after ${completedDeletions.size} remote deletion(s)" +
        (if (localPrepared) " and local preparation" else "") + "; changes may be partial: ${cause.message}", cause,
)

/**
 * The server has no conditional delete. Each version is checked immediately before a request and
 * checked again from its response; a concurrent write can still cause one deletion before detection.
 * It is reported as partial success and never retried automatically.
 */
internal suspend fun executeCloudReplacement(
    plan: CloudReplacementPlan,
    operations: CloudReplacementOperations,
    onProgress: (CloudReplacementProgress) -> Unit = {},
): Long {
    val completed = mutableListOf<PendingSyncChange>()
    var prepared = false
    var cloudVersion = plan.serverVersion
    var progress = CloudReplacementProgress()
    fun report(step: String?, unknown: Boolean) {
        progress = CloudReplacementProgress(completed.toList(), prepared, step, unknown)
        onProgress(progress)
    }
    report(null, false)
    try {
        suspend fun checkCurrent(localVersion: Long) {
            check(operations.localRevision() == localVersion) { "Local data changed during replacement" }
            check(operations.cloudVersion() == cloudVersion) { "Cloud changed during replacement" }
        }
        checkCurrent(plan.localRevision)
        for (change in plan.deletions) {
            checkCurrent(plan.localRevision)
            report("delete ${change.collection.path}/${change.id}", true)
            val resultVersion = operations.delete(change)
            completed += change
            report(null, false)
            check(resultVersion == cloudVersion + 1) { "Cloud changed during deletion" }
            cloudVersion = resultVersion
        }
        checkCurrent(plan.localRevision)
        report("prepare local data", true)
        val preparedRevision = operations.prepareLocal(plan)
        prepared = true
        report(null, false)
        checkCurrent(preparedRevision)
        report("upload", true)
        val result = operations.upload()
        report(null, false)
        return result
    } catch (cancelled: CancellationException) {
        if (progress.outcomeMayBeUnknown || completed.isNotEmpty() || prepared) {
            cancelled.addSuppressed(CloudReplacementFailure(completed.toList(), prepared,
                IllegalStateException("Cancelled during ${progress.currentStep ?: "replacement"}; the remote outcome may be unknown")))
        }
        throw cancelled
    } catch (error: Exception) {
        throw CloudReplacementFailure(completed.toList(), prepared, error)
    }
}
