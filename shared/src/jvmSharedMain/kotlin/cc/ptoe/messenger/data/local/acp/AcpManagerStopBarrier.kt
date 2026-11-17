/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

/** Valid only for turns observed by this manager; restart recovery needs a platform-owned proof. */
internal class AcpManagerStopBarrier(private val manager: AcpWorkerManager) : AcpRecoveryBarrier {
    override suspend fun <T> withStopped(requestId: String, sessionId: String, action: suspend () -> T): T =
        manager.withStoppedTurn(sessionId, requestId, action)
}
