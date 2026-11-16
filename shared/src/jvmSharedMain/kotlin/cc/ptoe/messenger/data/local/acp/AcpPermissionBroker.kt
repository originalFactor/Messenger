/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.data.util.randomUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class AcpPermissionOption(val agentOptionId: String, val label: String, val kind: String)
internal data class AcpVisibleOption(val id: String, val label: String, val kind: String)
internal data class AcpPermissionPrompt(val turnId: String, val options: List<AcpVisibleOption>, val title: String, val detail: String)

/** A decision is bound to one active turn and one prompt; missing/late/invalid decisions deny. */
internal class AcpPermissionBroker(private val timeoutMillis: Long = 120_000) {
    private data class Pending(val turnId: String, val choices: Map<String, String>, val result: CompletableDeferred<String?>,
        val cleared: CompletableDeferred<Unit> = CompletableDeferred())
    private val gate = Mutex()
    private val requestGate = Mutex()
    private var pending: Pending? = null
    private var closed = false

    suspend fun request(turnId: String, options: List<AcpPermissionOption>, title: String, detail: String,
        publish: suspend (AcpPermissionPrompt?) -> Unit): String? = requestGate.withLock {
        require(turnId.isNotBlank() && options.isNotEmpty() && options.size <= 32 &&
            options.all { it.agentOptionId.isNotBlank() && it.agentOptionId.length <= 512 } &&
            options.map { it.agentOptionId }.distinct().size == options.size) { "Invalid ACP permission options" }
        val nonce = randomUuid()
        val visible = options.mapIndexed { index, option -> AcpVisibleOption("$nonce/$index", option.label.take(180), option.kind.take(80)) }
        val decision = CompletableDeferred<String?>()
        val value = Pending(turnId, visible.zip(options).associate { it.first.id to it.second.agentOptionId }, decision)
        var installed = false
        try {
            gate.withLock { check(!closed && pending == null) { "ACP permission request unavailable" }; pending = value; installed = true }
            withTimeoutOrNull(timeoutMillis) {
                publish(AcpPermissionPrompt(turnId, visible, title.take(180), detail.take(1500)))
                decision.await()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        finally {
            withContext(NonCancellable) {
                if (installed) {
                    try { withTimeoutOrNull(5_000) { publish(null) } }
                    finally {
                        gate.withLock { if (pending === value) pending = null }
                        value.cleared.complete(Unit)
                    }
                }
            }
        }
    }

    suspend fun choose(turnId: String, visibleId: String): Boolean = gate.withLock {
        val current = pending ?: return@withLock false
        if (current.turnId != turnId) return@withLock false
        val selected = current.choices[visibleId] ?: return@withLock false
        current.result.complete(selected)
    }
    suspend fun close() = withContext(NonCancellable) {
        val current = gate.withLock { closed = true; pending }
        current?.result?.complete(null)
        current?.cleared?.await()
        Unit
    }
}
