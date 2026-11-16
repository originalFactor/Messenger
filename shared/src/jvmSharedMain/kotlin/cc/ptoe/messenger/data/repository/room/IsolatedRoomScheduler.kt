/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.data.chat.KotlinTurnRequestPreparer
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTaskRunner
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTasks
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTurnHost
import cc.ptoe.messenger.data.local.schedule.KotlinScheduledTask
import cc.ptoe.messenger.data.local.schedule.ScheduledExecutionScope
import cc.ptoe.messenger.data.local.schedule.ScheduledRecoveryBarrier
import cc.ptoe.messenger.data.local.schedule.ScheduledRunClaim
import cc.ptoe.messenger.data.local.schedule.ScheduledTaskLifecycle
import cc.ptoe.messenger.data.local.schedule.ScheduledTurnRuntime
import cc.ptoe.messenger.data.local.schedule.ScheduledTurnRuntimeFactory
import cc.ptoe.messenger.data.remote.api.DirectProviderStream
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.repository.LearningOwnership
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.domain.model.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Partial scheduler: workspace, unbounded or unsupported tool grants fail before dispatch. */
internal class IsolatedRoomScheduler(
    private val db: LocalDatabase,
    private val tasks: RoomScheduledTasks,
    private val learning: LearningOwnership,
    private val artifacts: RoomArtifactService,
    private val cloudGate: RoomCloudProviderGate,
    private val processScope: CoroutineScope,
    previousWorkersStopped: ScheduledRecoveryBarrier,
    private val now: () -> Long,
    private val admissions: PartialChatCalls,
    claimDue: suspend (Long) -> List<Pair<KotlinScheduledTask, ScheduledRunClaim>> = { tasks.claimDue(it) },
) {
    private val failureState = MutableStateFlow<String?>(null)
    @Volatile private var started = false
    val failure = failureState.asStateFlow()
    private val supported = IsolatedCoreTools.supportedNames
    private val direct = DirectProviderStream()
    private val provider = object : KotlinTurnProvider {
        override fun stream(provider: Provider, request: ChatCompletionRequestDto) = direct.stream(provider, request)
        override fun complete(provider: Provider, request: ChatCompletionRequestDto) = direct.complete(provider, request)
    }
    private val host = RoomScheduledTurnHost(db, tasks, ScheduledTurnRuntimeFactory { conversationId, scheduled ->
        requireSafeScope(scheduled)
        val bundle = IsolatedCoreTools.create(db, conversationId, learning, artifacts, scheduled.allowedTools, now)
        check(bundle.tools.coreToolGrant()?.allowedNames.orEmpty().toSet() == scheduled.allowedTools) {
            "Scheduled Core capability is disabled"
        }
        val preparer = object : KotlinTurnRequestPreparer by bundle.preparer {
            override suspend fun validateAdmission(conversation: cc.ptoe.messenger.data.local.room.ConversationRow, request: ChatCompletionRequestDto) {
                requireSafeScope(scheduled)
                check(tasks.executionScope(scheduled.claim, supported) == scheduled) { "Scheduled task permissions changed" }
                bundle.preparer.validateAdmission(conversation, request)
            }
        }
        ScheduledTurnRuntime(preparer, bundle.tools, bundle.tools, provider, processScope,
            cloudGate = cloudGate, bindStore = bundle::bind)
    }, { supported }, now)
    private val runner = RoomScheduledTaskRunner(tasks, host, { supported }, previousWorkersStopped,
        processScope, now, object : ScheduledTaskLifecycle {
            override fun schedulerFailed(message: String) { failureState.value = message }
        }, claimDue)

    /** Recovery proof is checked by the runner before it starts or retires interrupted work. */
    suspend fun start() = admissions.run { runner.start(); started = true }
    suspend fun tick() = admissions.run {
        check(started) { "Scheduler has not completed previous-worker recovery" }
        runner.tick()
    }

    private suspend fun requireSafeScope(scope: ScheduledExecutionScope) {
        check(scope.projectId == null) { "Scheduled workspace execution is unavailable" }
        val task = tasks.list().singleOrNull { it.id == scope.taskId } ?: error("Scheduled task is unavailable")
        val agent = requireNotNull(db.rows().agent(scope.agentId)) { "Scheduled Agent is unavailable" }
        val defaultAgent = db.rows().defaultAgents().singleOrNull() ?: error("Default Agent is unavailable")
        val toolsEnabled = if (!agent.isDefault && agent.toolsFollowDefault) defaultAgent.toolsEnabled else agent.toolsEnabled
        if (toolsEnabled) {
            val ceiling = task.allowedTools ?: error("Scheduled task has an unrestricted tool scope")
            check(ceiling.all { it in supported }) { "Scheduled task requires unavailable tools" }
        }
        check(scope.allowedTools.all { it in supported }) { "Scheduled task requires unavailable tools" }
    }
}
