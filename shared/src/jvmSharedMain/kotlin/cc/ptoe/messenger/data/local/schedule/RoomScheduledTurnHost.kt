/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.schedule

import cc.ptoe.messenger.data.chat.KotlinTurnRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinTurnToolPolicy
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.cloud.SyncCollection
import cc.ptoe.messenger.data.cloud.SyncMutation
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.chat.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull

/** Factory must provide the same trusted Runtime policy and execution host as interactive turns. */
internal data class ScheduledTurnRuntime(
    val preparer: KotlinTurnRequestPreparer,
    val policy: KotlinTurnToolPolicy,
    val executor: KotlinTurnToolExecutor,
    val provider: KotlinTurnProvider,
    val scope: CoroutineScope,
    val lifecycle: KotlinTurnLifecycle = object : KotlinTurnLifecycle {},
    /** Same gate used by interactive turns and Cloud session revocation for this database. */
    val cloudGate: RoomCloudProviderGate? = null,
    /** Bind tool adapters to the exact store that will own this scheduled turn. */
    val bindStore: (RoomKotlinTurnStore) -> Unit = {},
)
internal fun interface ScheduledTurnRuntimeFactory {
    suspend fun create(conversationId: String, scope: ScheduledExecutionScope): ScheduledTurnRuntime
}

/** Creates a local conversation and executes a real Kotlin background turn, with a restricted catalog. */
internal class RoomScheduledTurnHost(
    private val db: LocalDatabase,
    private val tasks: RoomScheduledTasks,
    private val runtimeFactory: ScheduledTurnRuntimeFactory,
    private val availableNames: suspend () -> Set<String>,
    private val now: () -> Long,
) : ScheduledTurnHost {
    override suspend fun run(scope: ScheduledExecutionScope, recordConversation: suspend (String) -> Boolean): String {
        val fresh = tasks.executionScope(scope.claim, availableNames())
        check(fresh == scope) { "Scheduled task permissions changed before admission" }
        val conversationId = randomUuid()
        val runtime = runtimeFactory.create(conversationId, scope)
        val preparer = object : KotlinTurnRequestPreparer {
            override fun toolGrant() = runtime.preparer.toolGrant()?.also { grant ->
                require(grant.conversationId == conversationId && grant.projectId == scope.projectId &&
                    grant.allowedNames.all { it in scope.allowedTools }) { "Scheduled Runtime grant exceeds its tool ceiling" }
            }
            override fun coreToolGrant() = runtime.preparer.coreToolGrant()?.also { grant ->
                require(grant.conversationId == conversationId && grant.allowedNames.all { it in scope.allowedTools }) { "Scheduled Core grant exceeds its tool ceiling" }
            }
            override fun supportsContextCompaction() = runtime.preparer.supportsContextCompaction()
            override fun rebuildContext(conversation: ConversationRow, history: List<cc.ptoe.messenger.data.local.room.MessageRow>, prompt: String) =
                runtime.preparer.rebuildContext(conversation, history, prompt)
            override fun effectiveSystemPrompt(agent: cc.ptoe.messenger.data.local.room.AgentRow, defaultAgent: cc.ptoe.messenger.data.local.room.AgentRow, request: cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto) =
                runtime.preparer.effectiveSystemPrompt(agent, defaultAgent, request)
            override suspend fun validateAdmission(conversation: ConversationRow, request: cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto) =
                runtime.preparer.validateAdmission(conversation, request)
            override fun prepare(conversation: ConversationRow, agent: cc.ptoe.messenger.data.local.room.AgentRow,
                defaultAgent: cc.ptoe.messenger.data.local.room.AgentRow, model: cc.ptoe.messenger.data.local.room.ModelRow,
                provider: cc.ptoe.messenger.data.local.room.ProviderRow, history: List<cc.ptoe.messenger.data.local.room.MessageRow>) =
                runtime.preparer.prepare(conversation, agent, defaultAgent, model, provider, history).also { request ->
                    require(request.tools.orEmpty().all { it.function.name in scope.allowedTools }) { "Scheduled request exceeds its tool ceiling" }
                }
        }
        val policy = KotlinTurnToolPolicy { turnId, conversation, call ->
            call.name in scope.allowedTools && call.name in tasks.executionScope(scope.claim, availableNames()).allowedTools &&
                runtime.policy.permits(turnId, conversation, call)
        }
        val executor = KotlinTurnToolExecutor { turnId, call ->
            check(call.name in scope.allowedTools && call.name in tasks.executionScope(scope.claim, availableNames()).allowedTools) {
                "Scheduled tool permission was revoked"
            }
            runtime.executor.execute(turnId, call)
        }
        val store = RoomKotlinTurnStore(db, preparer, policy, cloudGate = runtime.cloudGate)
        runtime.bindStore(store)
        val runner = KotlinChatTurnRunner(store, runtime.provider, executor, runtime.scope, runtime.lifecycle)
        val timestamp = now()
        val published = tasks.publishConversation(scope, conversationId) { rows ->
            val agent = requireNotNull(rows.agent(scope.agentId)) { "Scheduled Agent is unavailable" }
            check(agent.defaultModelId != null) { "Scheduled Agent requires a model" }
            val default = rows.defaultAgents().singleOrNull() ?: error("Default Agent is unavailable")
            val modelId = if (!agent.isDefault && agent.followDefaultModel) default.defaultModelId else agent.defaultModelId
            val model = modelId?.let { rows.model(it) } ?: error("Scheduled Agent requires a model")
            check(model.isEnabled && rows.provider(model.providerId) != null) { "Scheduled model is unavailable" }
            if (scope.projectId != null) {
                val project = requireNotNull(rows.project(scope.projectId)) { "Scheduled project is unavailable" }
                check(!project.deviceId.isNullOrBlank() && project.deviceId == rows.getValue("device_id")?.value) { "Scheduled project is not local" }
            }
            val conversation = ConversationRow(conversationId, "Scheduled task", model.providerId, agent.id, scope.projectId,
                null, null, null, null, null, null, null, true, timestamp, timestamp, null, null, null, 0, 0, 0)
            rows.upsertConversations(listOf(conversation))
            db.cloudSync().enqueueLocal(SyncCollection.CONVERSATION, conversationId, SyncMutation.UPSERT)
        }
        check(published) { "Scheduled run was stopped before conversation creation" }
        check(recordConversation(conversationId)) { "Scheduled run was stopped before turn admission" }
        currentCoroutineContext().ensureActive()
        var turnId: String? = null
        try {
            withContext(NonCancellable) {
                when (val admission = runner.submit(KotlinTurnCommand.Send(conversationId, scope.prompt, null, scheduledRun = true,
                    scheduledClaim = KotlinScheduledTurnClaim(scope.claim.taskId, scope.claim.token, scope.claim.startedAt))) { turnId = it }) {
                    is KotlinTurnAdmission.Accepted -> check(turnId == admission.turnId)
                    KotlinTurnAdmission.ModelRequired -> error("Scheduled Agent requires a model")
                    is KotlinTurnAdmission.Rejected -> error(admission.reason)
                }
            }
            currentCoroutineContext().ensureActive()
            val id = checkNotNull(turnId)
            runner.awaitStopped(id)
            val phase = store.snapshot(id).phase
            if (phase == KotlinTurnPhase.INTERRUPTED_TOOL || phase == KotlinTurnPhase.CANCELLING_TOOL) {
                // An ambiguous tool must retain the scheduler claim until separately reconciled.
                db.rows().observeValue("kotlin_turn_active_v1:$conversationId").first { it?.value != id }
            }
            when (store.snapshot(id).phase) {
                KotlinTurnPhase.FINISHED -> return conversationId
                KotlinTurnPhase.CANCELLED -> throw CancellationException("Scheduled turn cancelled")
                else -> {
                    val detail = db.rows().getValue("kotlin_turn_v1:$id")?.value?.let {
                        (Json.parseToJsonElement(it).jsonObject["detail"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    }
                    error(detail ?: "Scheduled turn failed")
                }
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                turnId?.let { id ->
                    if (db.rows().getValue("kotlin_turn_active_v1:$conversationId")?.value == id) {
                        runner.cancelAndAwait(id)
                        db.rows().observeValue("kotlin_turn_active_v1:$conversationId").first { it?.value != id }
                    } else runner.awaitStopped(id)
                }
            }
            throw failure
        }
    }
}
