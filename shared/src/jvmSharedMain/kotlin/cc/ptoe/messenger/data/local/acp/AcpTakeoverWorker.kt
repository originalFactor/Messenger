/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Isolated one-turn worker wrapper. Only the loopback capability enters the worker's environment;
 * upstream credentials remain in [catalog]. The wrapped transport's stop must still prove the
 * real process and its side effects stopped. This wrapper cannot be reused for another turn.
 */
internal class AcpTakeoverWorker(
    private val worker: AcpWorkerProcess,
    private val catalog: AcpRelayCatalog,
    models: List<AcpTakeoverModel>,
    private val preferredModelId: String?,
    private val openCodeMajor: Int? = null,
) : AcpWorkerProcess, AcpWorkerSettings, AcpWorkerSteering, AcpNewSessionBinding {
    private val models = models.toList()
    private var relay: AcpModelRelay? = null
    private var host: AcpModelRelayHost? = null
    private var started = false
    private var attemptedTurn = false

    override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore): AcpWorkerReady {
        check(!started && relay == null && host == null) { "ACP takeover worker already started" }
        val available = models.filter { it.enabled && AcpTakeoverPlan.supportsFormat(it.apiFormat) }
        val allowed = available.map { it.id }.toSet()
        require(allowed.isNotEmpty() && allowed.size == available.size) { "ACP takeover model scope is invalid" }
        val opened = withContext(NonCancellable) {
            val ownedRelay = AcpModelRelay(catalog, allowed)
            try {
                val ownedHost = AcpModelRelayHost.start(ownedRelay)
                relay = ownedRelay; host = ownedHost
                ownedHost
            } catch (failure: Throwable) {
                try { ownedRelay.close() } catch (cleanup: Throwable) { if (failure !== cleanup) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        try {
            currentCoroutineContext().ensureActive()
            val env = AcpTakeoverPlan.configure(binding.profileId, configuration.environment, models, preferredModelId,
                opened.baseUrl, checkNotNull(relay).capability(), openCodeMajor).environment
            val configured = AcpWorkerConfiguration(configuration.command, configuration.arguments, env,
                configuration.mcpServers, configuration.allowDelegation, configuration.coreToolScope, configuration.providerTakeover)
            val ready = worker.start(binding, configured, gateway)
            started = true
            return ready
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { opened.close() } }
            catch (cleanup: Throwable) { if (failure !== cleanup) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override fun bindCreatedSession(binding: AcpWorkerBinding) =
        (worker as? AcpNewSessionBinding ?: error("ACP worker cannot adopt a new session")).bindCreatedSession(binding)

    override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject {
        check(started && !attemptedTurn) { "ACP takeover worker is unavailable or already used" }
        attemptedTurn = true
        val ownedRelay = checkNotNull(relay)
        val ownedHost = checkNotNull(host)
        withContext(NonCancellable) { ownedRelay.beginTurn(turnId) }
        try {
            currentCoroutineContext().ensureActive()
            return worker.prompt(turnId, content, permissions)
        } finally {
            withContext(NonCancellable) {
                try { ownedRelay.endTurn(turnId) } finally { ownedHost.close() }
            }
        }
    }

    override suspend fun cancelAndConfirm(): Boolean = worker.cancelAndConfirm()
    override suspend fun stopAndAwait(): Unit = withContext(NonCancellable) {
        var failure: Throwable? = null
        try { host?.close() } catch (error: Throwable) { failure = error }
        try { worker.stopAndAwait() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        failure?.let { throw it }
        Unit
    }

    override suspend fun sessionSettings(): JsonObject =
        (worker as? AcpWorkerSettings ?: error("ACP session settings are unavailable")).sessionSettings()
    override suspend fun setMode(modeId: String, permit: () -> Unit): JsonObject =
        (worker as? AcpWorkerSettings ?: error("ACP mode changes are unavailable")).setMode(modeId, permit)
    override suspend fun setModeOption(configId: String, value: String, permit: () -> Unit): JsonObject =
        (worker as? AcpWorkerSettings ?: error("ACP configuration is unavailable")).setModeOption(configId, value, permit)
    override suspend fun setBooleanOption(configId: String, value: Boolean, permit: () -> Unit): JsonObject =
        (worker as? AcpWorkerSettings ?: error("ACP configuration is unavailable")).setBooleanOption(configId, value, permit)
    override suspend fun steer(turnId: String, content: JsonArray): JsonObject =
        (worker as? AcpWorkerSteering ?: error("ACP steering is unavailable")).steer(turnId, content)
}
