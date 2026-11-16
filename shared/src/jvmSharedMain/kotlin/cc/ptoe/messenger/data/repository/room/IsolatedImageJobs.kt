/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.data.local.image.ImageGenerationAuthorization
import cc.ptoe.messenger.data.local.image.ImageGenerationService
import cc.ptoe.messenger.data.local.image.PriorImageWorkersStopped
import cc.ptoe.messenger.data.local.image.RoomImageJobOwner
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** The host retains this even if backend construction fails after the owner is registered. */
internal class IsolatedImageRecovery {
    private val owner = AtomicReference<RoomImageJobOwner?>(null)

    internal fun register(value: RoomImageJobOwner) {
        check(owner.compareAndSet(null, value)) { "Image recovery handle is already bound" }
    }

    /** Only while managed shutdown retains Room for recovery, after independent stop proof. */
    suspend fun recoverAfterStop(proof: PriorImageWorkersStopped) {
        checkNotNull(owner.get()) { "Image owner is not registered" }.recoverAfterStop(proof)
    }
}

/** Explicitly supplied by the owner; authorization applies to trusted user-originated jobs. */
internal data class IsolatedImageJobsConfig(
    val storeDirectory: Path,
    val previousWorkersStopped: PriorImageWorkersStopped,
    val authorization: ImageGenerationAuthorization,
    val recovery: IsolatedImageRecovery,
    /** Explicit policy for model-originated requests; absent means the image tool is unavailable. */
    val modelAuthorization: ImageGenerationAuthorization? = null,
)

/** Shared service keeps UI and model requests in the same cancellation registry. */
internal data class IsolatedModelImageTool(
    val service: ImageGenerationService,
    val authorization: ImageGenerationAuthorization,
)
