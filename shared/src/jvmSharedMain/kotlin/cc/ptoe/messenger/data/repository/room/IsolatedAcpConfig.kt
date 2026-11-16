/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.data.local.acp.AcpConfigurationFence
import cc.ptoe.messenger.data.local.acp.AcpGatewayEndpointFactory
import cc.ptoe.messenger.data.local.acp.AcpLineTransport
import cc.ptoe.messenger.data.local.acp.AcpNewSessionSource
import cc.ptoe.messenger.data.local.acp.AcpPriorOwnerBarrier
import cc.ptoe.messenger.data.local.acp.AcpPublishedMcpCatalog
import cc.ptoe.messenger.data.local.acp.AcpSessionConfigurationSource
import cc.ptoe.messenger.data.local.acp.AcpWorkerBinding
import cc.ptoe.messenger.data.local.acp.LoopbackAcpGatewayEndpointFactory
import java.nio.file.Path

/** All owner, writer and worker-stop proofs are mandatory; this is not a production config. */
internal data class IsolatedAcpConfig(
    val databasePath: Path,
    val previousOwner: AcpPriorOwnerBarrier,
    val configurationFence: AcpConfigurationFence,
    val sessions: AcpSessionConfigurationSource,
    val newSession: AcpNewSessionSource,
    val mcpCatalog: AcpPublishedMcpCatalog,
    val transport: (AcpWorkerBinding) -> AcpLineTransport,
    val endpoints: AcpGatewayEndpointFactory = LoopbackAcpGatewayEndpointFactory,
)
