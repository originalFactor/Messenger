/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.di

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.domain.artifact.ArtifactUiHost
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.CurrentAgentRepository
import cc.ptoe.messenger.domain.repository.LearningRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProjectRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository

/**
 * One complete application backend. Its stores, bridge and repositories must share the same
 * durable owner and lifetime; injecting individual Room repositories beside a Rust bridge would
 * create competing sources of truth. The host stops its workers before closing this backend.
 */
class AppBackend(
    val stores: LocalStores,
    val coreBridge: CoreBridge,
    val providers: ProviderRepository,
    val models: ModelRepository,
    val agents: AgentRepository,
    val conversations: ConversationRepository,
    val projects: ProjectRepository,
    val messages: MessageRepository,
    val currentAgent: CurrentAgentRepository,
    val learning: LearningRepository,
    /** Stop and join backend-owned turns, cloud, schedulers and other workers before Room closes. */
    val shutdown: suspend () -> Unit,
    val clearLocalData: suspend () -> Unit,
    /** Optional trusted-UI sandbox host, owned and stopped by this same backend. */
    val artifactUiHost: ArtifactUiHost? = null,
    /** Platform and MCP tools need their own authority and lifetime proof. */
    val platformToolsEnabled: Boolean = true,
)
