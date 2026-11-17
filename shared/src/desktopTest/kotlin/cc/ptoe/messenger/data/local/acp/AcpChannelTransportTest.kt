/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpChannelTransportTest {
    private val binding = AcpWorkerBinding("conversation", "profile", "project", "/workspace", "session", false)
    private val configuration = AcpWorkerConfiguration("agent", emptyList(), mapOf("TOKEN" to "secret"), JsonArray(emptyList()), false, emptySet())

    @Test fun fixedRuntimeFailureReachesPendingAndLaterRequests(): Unit = runBlocking { supervisorScope {
        val dispatched = CompletableDeferred<Unit>()
        val transport = AcpChannelTransport(AcpChannelHost { channel ->
            channel.readInput { dispatched.complete(Unit); error("Update Messenger Runtime to use ACP safely.") }
        }, AcpStoppedProof { })
        val peer = AcpJsonRpcPeer(transport, this, { _, _ -> }, { _, _ -> JsonObject(emptyMap()) })
        peer.start(binding, configuration)
        try {
            val request = async { peer.request("initialize", JsonObject(emptyMap())) }
            withTimeout(5_000) { dispatched.await() }
            val first = assertFailsWith<IllegalStateException> { withTimeout(5_000) { request.await() } }
            assertEquals("Update Messenger Runtime to use ACP safely.", first.message)
            val later = assertFailsWith<IllegalStateException> { peer.request("initialize", JsonObject(emptyMap())) }
            assertEquals(first.message, later.message)
        } finally { peer.closeAndAwait() }
    } }

    @Test fun unknownHostErrorsAndTheirCausesAreNotExposed(): Unit = runBlocking {
        val original = IllegalStateException("secret", IllegalStateException("secret cause"))
        original.addSuppressed(IllegalStateException("secret suppressed"))
        val transport = AcpChannelTransport(AcpChannelHost {
            throw original
        }, AcpStoppedProof { })
        transport.start(binding, configuration)
        try {
            val failure = assertFailsWith<IllegalStateException> { withTimeout(5_000) { transport.readLine() } }
            assertEquals("ACP process host failed", failure.message)
            val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
            assertTrue(chain.none { it === original || it.message.orEmpty().contains("secret") })
            assertTrue(chain.all { it.suppressed.isEmpty() })
        } finally { transport.stopAndAwait() }
    }

    @Test fun unverifiedRuntimeStopRemainsVisibleWithoutPassingOriginalCause(): Unit = runBlocking {
        val original = IllegalStateException("Messenger Runtime could not confirm ACP process stop.", IllegalStateException("secret"))
        val transport = AcpChannelTransport(AcpChannelHost {
            throw original
        }, AcpStoppedProof { })
        transport.start(binding, configuration)
        try {
            val failure = assertFailsWith<IllegalStateException> { withTimeout(5_000) { transport.readLine() } }
            assertEquals("Messenger Runtime could not confirm ACP process stop.", failure.message)
            val chain = generateSequence<Throwable>(failure) { it.cause }.toList()
            assertFalse(chain.any { it === original || it.message.orEmpty().contains("secret") })
            assertTrue(chain.all { it.suppressed.isEmpty() })
        } finally { transport.stopAndAwait() }
    }
}
