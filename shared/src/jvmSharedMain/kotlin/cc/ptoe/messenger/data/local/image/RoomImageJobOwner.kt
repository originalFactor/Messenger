/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.imageJobId
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Platform proof must fence every older process and every other image worker using this store. */
internal fun interface PriorImageWorkersStopped { suspend fun verify() }

/**
 * Process-owned UI image jobs. The owner must be installed only after lifetime exclusivity is
 * established and must share Room and the asset gate with the service. Every non-UI caller of
 * that service must be stopped and joined before this owner stops or recovers interrupted jobs.
 * The requesting UI coroutine observes admission; it does not own or cancel the paid request.
 */
internal class RoomImageJobOwner private constructor(
    private val db: LocalDatabase,
    private val assets: CoreAssetService,
    private val service: ImageGenerationService,
    private val now: () -> Long,
    private val shutdown: RoomManagedShutdown,
) {
    private class Worker(val request: ImageRequest, val admission: CompletableDeferred<ImageJob>, val job: Job)
    private val reservations = ImageReservationJournal(db.imageJobs())
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workers = ConcurrentHashMap<String, Worker>()
    private val mutableFailure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = mutableFailure.asStateFlow()
    private var closed = false

    companion object {
        /** Recovery never resubmits; caller must first prove all prior image workers cannot resume. */
        suspend fun open(db: LocalDatabase, assets: CoreAssetService, service: ImageGenerationService,
            shutdown: RoomManagedShutdown, priorStopped: PriorImageWorkersStopped, now: () -> Long,
            onRegistered: (RoomImageJobOwner) -> Unit = {}): RoomImageJobOwner {
            val owner = RoomImageJobOwner(db, assets, service, now, shutdown)
            owner.recoverInterrupted(assets, priorStopped)
            try { withContext(NonCancellable) {
                shutdown.registerStop { owner.shutdown() }
                // Expose the recovery handle only after its shutdown action is registered.
                onRegistered(owner)
            } }
            catch (failure: Throwable) {
                try { owner.shutdown() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
            return owner
        }
    }

    suspend fun submit(request: ImageRequest): ImageJob {
        currentCoroutineContext().ensureActive()
        val admission = mutex.withLock {
            check(!closed) { "Image job owner is closed" }
            checkHealthy()
            val existing = assets.withExclusive { db.imageJobs().findRequest(request) }
            val id = imageJobId(request.requestId)
            val active = workers[id]
            if (active != null) {
                check(active.request == request) { "Request ID already identifies a different image request" }
                active.admission
            } else if (existing != null) {
                CompletableDeferred(existing)
            } else {
                val admitted = CompletableDeferred<ImageJob>()
                val task = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val result = service.run(request, onAdmitted = { job -> admitted.complete(job) })
                        admitted.complete(result)
                    } catch (failure: Throwable) {
                        withContext(NonCancellable) {
                            if (admitted.isCompleted) retireUncertain(id)
                            admitted.completeExceptionally(failure)
                        }
                    }
                }
                val worker = Worker(request, admitted, task)
                check(workers.putIfAbsent(id, worker) == null) { "Image job admission collision" }
                task.invokeOnCompletion { failure ->
                    if (!admitted.isCompleted) admitted.completeExceptionally(failure ?: IllegalStateException("Image job ended before admission"))
                    workers.remove(id, worker)
                }
                task.start()
                admitted
            }
        }
        return admission.await()
    }

    /** Explicit cancellation is durable first, then cancels the owned worker. */
    suspend fun cancel(id: String): ImageJob = mutex.withLock {
        check(!closed) { "Image job owner is closed" }
        checkHealthy()
        val result = service.cancel(id)
        workers[id]?.job?.cancel()
        result
    }

    suspend fun hasActiveJobs(): Boolean = mutex.withLock { workers.isNotEmpty() }

    fun checkHealthy() {
        check(mutableFailure.value == null) { checkNotNull(mutableFailure.value) }
        service.checkHealthy()
    }

    /** Prevent admission, cancel, and join actual worker cleanup before the Room owner closes. */
    suspend fun shutdown() = withContext(NonCancellable) {
        val tasks = mutex.withLock {
            closed = true
            workers.values.map { it.job }.also { jobs -> jobs.forEach { it.cancel() } }
        }
        tasks.joinAll()
        scope.coroutineContext[Job]?.cancelAndJoin()
        checkHealthy()
    }

    /** Only while managed shutdown retains the owner for recovery; proof must cover all workers. */
    suspend fun recoverAfterStop(proof: PriorImageWorkersStopped) = shutdown.withRecovery {
        mutex.withLock {
            check(closed && workers.isEmpty()) { "Image workers are still active" }
            recoverInterruptedForStoppedOwner(proof)
            service.markRecovered()
            mutableFailure.value = null
        }
    }

    private suspend fun retireUncertain(id: String) {
        try {
            assets.withExclusive {
                val current = db.imageJobs().read(id)
                if (current.status == ImageJobStatus.running) {
                    db.imageJobs().fail(id, "Image request stopped unexpectedly; provider charges may have occurred. No automatic retry.", true, now())
                }
            }
        } catch (failure: Throwable) {
            mutableFailure.value = "Image job status could not be recorded; its outcome may be unknown. Recovery is required."
        }
    }

    private suspend fun recoverInterrupted(assets: CoreAssetService, priorStopped: PriorImageWorkersStopped) {
        priorStopped.verify()
        assets.withExclusive {
            reservations.reconcile(assets.root, emptySet())
            db.imageJobs().recoverInterrupted(now())
        }
    }

    private suspend fun recoverInterruptedForStoppedOwner(proof: PriorImageWorkersStopped) {
        proof.verify()
        assets.withRecovery {
            reservations.reconcile(assets.root, emptySet())
            db.imageJobs().recoverInterrupted(now())
        }
    }
}
