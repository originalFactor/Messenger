/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Real loopback transport for the stdio worker; capability URLs remain process-local. */
internal object LoopbackAcpGatewayEndpointFactory : AcpGatewayEndpointFactory {
    override suspend fun open(gateway: AcpGatewayCore, sse: Boolean): AcpGatewayEndpoint {
        var transport: AcpGatewayTransport? = null
        try {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { transport = AcpGatewayTransport.start(gateway) }
            currentCoroutineContext().ensureActive()
            val server = checkNotNull(transport)
            return object : AcpGatewayEndpoint {
                override val url = if (sse) server.sseUrl else server.rpcUrl
                override suspend fun closeAndAwait() { server.close() }
                override fun toString() = "AcpGatewayEndpoint(redacted)"
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { transport?.close() }
            throw failure
        }
    }
}
