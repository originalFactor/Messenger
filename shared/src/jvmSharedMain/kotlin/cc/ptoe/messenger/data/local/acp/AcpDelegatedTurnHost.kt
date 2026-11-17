/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.data.chat.KotlinDelegatedAcpHost
import cc.ptoe.messenger.data.chat.KotlinDelegatedTarget
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.chat.KotlinDelegationParent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Returns a private configuration snapshot; normal ACP authority must revalidate it before startup. */
internal fun interface AcpDelegationConfiguration {
    suspend fun load(profileId: String, projectId: String): AcpWorkerConfiguration
}

/** Real ACP delegation: no nested delegation, no auto-approval, and worker shutdown is joined. */
internal class AcpDelegatedTurnHost(
    private val db: LocalDatabase, private val parentStore: () -> RoomKotlinTurnStore,
    private val workers: AcpWorkerManager, private val sessions: RoomAcpSessionStore,
    private val authority: AcpNewSessionAuthority, private val coordinator: AcpTurnCoordinator,
    private val configurations: AcpDelegationConfiguration, private val now: () -> Long,
) : KotlinDelegatedAcpHost {
    override suspend fun run(parent: KotlinDelegationParent, target: KotlinDelegatedTarget, task: String): String {
        require(target.agentId.startsWith("acp:") && target.conversationId.startsWith("acp:") && target.writable)
        val projectId = requireNotNull(target.projectId) { "ACP delegation requires a project" }
        val workspace = requireNotNull(target.workspace) { "ACP workspace is unavailable" }
        val profileId = target.agentId.removePrefix("acp:")
        val draft = AcpWorkerBinding(target.conversationId, profileId, projectId, workspace, "", delegated = true)
        parentStore().claimAcpDelegation(parent, target.conversationId)
        try {
            val configuration = configurations.load(profileId, projectId)
            check(!configuration.allowDelegation && configuration.coreToolScope.isEmpty()) { "Delegated ACP scope exceeds its authority" }
            val guarded = AcpNewSessionAuthority { binding, settings ->
                check(binding == draft && binding.delegated) { "Delegated ACP binding changed" }
                authority.verify(binding, settings)
                parentStore().assertAcpDelegation(parent, target.conversationId)
            }
            val creator = AcpSessionCreator(workers, sessions, guarded, now)
            val binding = creator.create(draft, configuration, "Delegated task")
            parentStore().assertAcpDelegation(parent, target.conversationId)
            val turnId = randomUuid()
            val permissionRequested = AtomicBoolean(false)
            val content = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", task) }))
            val outcome = coroutineScope {
                val operation = async(start = CoroutineStart.LAZY) { workers.prompt(binding, configuration, content, turnId) }
                val watch = launch(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.activeTurns.first { it[binding.conversationId] == turnId }
                    coordinator.changes(turnId).first {
                        if (coordinator.snapshot(turnId, 0).pendingPermission != null) {
                            permissionRequested.set(true); operation.cancel(); true
                        } else false
                    }
                }
                try { operation.start(); operation.await() }
                catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    if (permissionRequested.get()) error("The delegated ACP agent requested permission; the request was cancelled")
                    throw cancelled
                } finally { watch.cancelAndJoin() }
            }
            check((outcome["type"] as? JsonPrimitive)?.contentOrNull == "Finished") { "Delegated ACP Agent did not finish" }
            val root = db.rows().getValue("local_acp_sessions")?.value?.let { Json.parseToJsonElement(it) as? JsonObject } ?: error("ACP session unavailable")
            val session = root[target.conversationId] as? JsonObject ?: error("ACP session unavailable")
            val messages = session["messages"] as? JsonArray ?: error("ACP transcript unavailable")
            return messages.mapNotNull { it as? JsonObject }.lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "assistant" && it["status"]?.jsonPrimitive?.contentOrNull == "sent" }
                ?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        } finally {
            withContext(NonCancellable) { workers.retireAndAwait(target.conversationId) }
        }
    }
}
