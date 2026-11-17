/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.domain.chat.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class KotlinDelegatedTarget(val conversationId: String, val agentId: String, val projectId: String?, val workspace: String?, val writable: Boolean)
internal data class KotlinDelegatedRuntime(
    val preparer: KotlinTurnRequestPreparer, val policy: KotlinTurnToolPolicy, val executor: KotlinTurnToolExecutor,
    val provider: KotlinTurnProvider, val scope: CoroutineScope, val lifecycle: KotlinTurnLifecycle = object : KotlinTurnLifecycle {},
    val cloudGate: RoomCloudProviderGate? = null,
)
internal fun interface KotlinDelegatedRuntimeFactory { suspend fun create(conversationId: String, store: () -> RoomKotlinTurnStore): KotlinDelegatedRuntime }
internal fun interface KotlinDelegatedAcpHost { suspend fun run(parent: KotlinDelegationParent, target: KotlinDelegatedTarget, task: String): String }

/** Creates the child runner against the same Room object and joins it before returning. */
internal class KotlinDelegatedTurnHost(private val db: LocalDatabase, private val factory: KotlinDelegatedRuntimeFactory) {
    suspend fun run(parent: KotlinDelegationParent, target: KotlinDelegatedTarget, task: String): String {
        lateinit var store: RoomKotlinTurnStore
        val runtime = factory.create(target.conversationId) { store }
        val base = runtime.preparer
        val preparer = object : KotlinTurnRequestPreparer {
            private fun restricted(request: ChatCompletionRequestDto) = request.copy(tools = request.tools?.filterNot {
                it.function.name == "messenger_agents" || it.function.name == "messenger_schedule_task"
            }?.ifEmpty { null })
            override fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow, model: ModelRow, provider: ProviderRow, history: List<MessageRow>) =
                restricted(base.prepare(conversation, agent, defaultAgent, model, provider, history))
            override fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow, model: ModelRow, provider: ProviderRow, history: List<MessageRow>, disableTools: Boolean) =
                restricted(base.prepare(conversation, agent, defaultAgent, model, provider, history, disableTools))
            override fun toolGrant() = base.toolGrant()
            override fun coreToolGrant() = base.coreToolGrant()?.let { it.copy(allowedNames = it.allowedNames.filterNot { name -> name == "messenger_agents" || name == "messenger_schedule_task" }) }
            override fun effectiveSystemPrompt(agent: AgentRow, defaultAgent: AgentRow, request: ChatCompletionRequestDto) = base.effectiveSystemPrompt(agent, defaultAgent, request)
            override fun supportsContextCompaction() = base.supportsContextCompaction()
            override fun rebuildContext(conversation: ConversationRow, history: List<MessageRow>, prompt: String) = base.rebuildContext(conversation, history, prompt)
            override suspend fun validateAdmission(conversation: ConversationRow, request: ChatCompletionRequestDto) = base.validateAdmission(conversation, request)
        }
        store = RoomKotlinTurnStore(db, preparer, runtime.policy, cloudGate = runtime.cloudGate)
        val runner = KotlinChatTurnRunner(store, runtime.provider, runtime.executor, runtime.scope, runtime.lifecycle)
        var id: String? = null
        var stopped = false
        try {
            when (val admission = runner.submit(KotlinTurnCommand.Send(target.conversationId, task, null,
                delegatedRun = true, delegationParent = parent)) { id = it }) {
                is KotlinTurnAdmission.Accepted -> check(id == admission.turnId)
                KotlinTurnAdmission.ModelRequired -> error("Delegated Agent requires a model")
                is KotlinTurnAdmission.Rejected -> error(admission.reason)
            }
            val turnId = checkNotNull(id)
            val state = store.observePresentation(turnId, target.conversationId).first {
                it.phase in setOf(KotlinTurnPhase.WAITING_APPROVAL, KotlinTurnPhase.FINISHED, KotlinTurnPhase.FAILED, KotlinTurnPhase.CANCELLED, KotlinTurnPhase.INTERRUPTED_TOOL)
            }
            if (state.phase == KotlinTurnPhase.WAITING_APPROVAL) error("The delegated Agent requested permission; the request was cancelled")
            runner.awaitStopped(turnId)
            stopped = true
            check(state.phase == KotlinTurnPhase.FINISHED) { state.detail ?: "Delegated Agent did not complete" }
            return state.content
        } finally {
            if (!stopped) withContext(NonCancellable) { id?.let { runner.cancelAndAwait(it) } }
        }
    }
}
