/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.data.local.room.LocalDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Created under the enclosing ACP owner and its configuration fence; no production entry exists. */
internal class RoomAcpConfiguredWorker(private val db: LocalDatabase, private val raw: AcpWorkerProcess) :
    AcpWorkerProcess, AcpNewSessionBinding, AcpWorkerSettings, AcpWorkerSteering, AcpReusableWorkerProof {
    private var current: AcpWorkerProcess? = null
    private var takeover = false

    override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore): AcpWorkerReady {
        check(current == null) { "ACP worker already started" }
        takeover = configuration.providerTakeover
        val worker = if (takeover) {
            check(binding.profileId in setOf("codex", "claude-code")) { "ACP provider takeover is unavailable for this profile" }
            val scope = RoomAcpTakeoverScope.capture(db)
            check(scope.models.isNotEmpty()) { "ACP provider takeover requires an enabled BYOK model" }
            AcpTakeoverWorker(raw, scope, scope.models, scope.preferredModelId)
        } else raw
        current = worker
        return worker.start(binding, configuration, gateway)
    }
    override fun bindCreatedSession(binding: AcpWorkerBinding) =
        (current as? AcpNewSessionBinding ?: error("ACP worker cannot adopt a session")).bindCreatedSession(binding)
    override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject =
        checkNotNull(current) { "ACP worker is unavailable" }.prompt(turnId, content, permissions)
    override suspend fun cancelAndConfirm() = checkNotNull(current) { "ACP worker is unavailable" }.cancelAndConfirm()
    override suspend fun stopAndAwait() { current?.stopAndAwait() }
    override suspend fun sessionSettings() = (current as? AcpWorkerSettings ?: error("ACP session settings are unavailable")).sessionSettings()
    override suspend fun setMode(modeId: String, permit: () -> Unit) =
        (current as? AcpWorkerSettings ?: error("ACP session settings are unavailable")).setMode(modeId, permit)
    override suspend fun setModeOption(configId: String, value: String, permit: () -> Unit) =
        (current as? AcpWorkerSettings ?: error("ACP session settings are unavailable")).setModeOption(configId, value, permit)
    override suspend fun setBooleanOption(configId: String, value: Boolean, permit: () -> Unit) =
        (current as? AcpWorkerSettings ?: error("ACP session settings are unavailable")).setBooleanOption(configId, value, permit)
    override suspend fun steer(turnId: String, content: JsonArray) =
        (current as? AcpWorkerSteering ?: error("ACP steering is unavailable")).steer(turnId, content)
    override val supportsReusableTurns: Boolean get() = !takeover && (current as? AcpReusableWorkerProof)?.supportsReusableTurns == true
    override suspend fun requireInitialQuiescence() =
        (current as? AcpReusableWorkerProof ?: error("ACP reusable-worker proof is unavailable")).requireInitialQuiescence()
    override suspend fun requireTurnQuiescence(turnId: String) =
        (current as? AcpReusableWorkerProof ?: error("ACP reusable-worker proof is unavailable")).requireTurnQuiescence(turnId)
}
