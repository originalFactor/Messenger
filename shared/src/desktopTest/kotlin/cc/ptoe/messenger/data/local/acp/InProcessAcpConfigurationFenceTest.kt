/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.coroutines.*
import kotlin.test.*

class InProcessAcpConfigurationFenceTest {
    private val binding = AcpWorkerBinding("acp:one", "profile", "project", "/tmp/project", "session", false)

    @Test fun writerDrainsAllWorkersAndPreventsNewWorkersUntilItFinishes(): Unit = runBlocking {
        val fence = InProcessAcpConfigurationFence()
        val first = fence.acquire(binding)
        val second = fence.acquire(binding.copy(conversationId = "acp:two"))
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        val writer = async(start = CoroutineStart.UNDISPATCHED) { fence.withExclusiveWriter {
            writerEntered.complete(Unit); releaseWriter.await()
        } }
        val later = async(start = CoroutineStart.UNDISPATCHED) { fence.acquire(binding) }
        assertFalse(writerEntered.isCompleted)
        assertFalse(later.isCompleted)
        first.release()
        assertFalse(writerEntered.isCompleted)
        second.release()
        withTimeout(5_000) { writerEntered.await() }
        assertFalse(later.isCompleted)
        releaseWriter.complete(Unit)
        withTimeout(5_000) { writer.await() }
        val last = withTimeout(5_000) { later.await() }
        last.release(); last.release() // Completion is idempotent, not a second reader release.
        withTimeout(5_000) { fence.withExclusiveWriter { } }
    }

    @Test fun cancelledWaitingReaderAndFailedWriterLeaveNoLeakedLease(): Unit = runBlocking {
        val fence = InProcessAcpConfigurationFence()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val writer = async(start = CoroutineStart.UNDISPATCHED) { fence.withExclusiveWriter {
            entered.complete(Unit); release.await()
        } }
        entered.await()
        val reader = async(start = CoroutineStart.UNDISPATCHED) { fence.acquire(binding) }
        assertFalse(reader.isCompleted)
        reader.cancelAndJoin()
        release.complete(Unit)
        withTimeout(5_000) { writer.await() }
        assertFailsWith<IllegalStateException> { fence.withExclusiveWriter { error("failed write") } }
        val fresh = withTimeout(5_000) { fence.acquire(binding) }
        fresh.release()
        withTimeout(5_000) { fence.withExclusiveWriter { } }
    }
}
