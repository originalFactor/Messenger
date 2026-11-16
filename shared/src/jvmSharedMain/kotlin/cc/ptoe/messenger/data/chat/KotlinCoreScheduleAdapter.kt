/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTasks
import cc.ptoe.messenger.data.local.schedule.ScheduleCreationAuthorization
import cc.ptoe.messenger.data.local.schedule.ScheduleToolAdapter
import cc.ptoe.messenger.data.local.schedule.scheduleToolDeclaration
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.chat.KotlinCoreToolGrant
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor

private const val SCHEDULE = "messenger_schedule_task"

/** Scheduling is opt-in, local and fenced against recursively created scheduled turns. */
internal class KotlinCoreScheduleAdapter private constructor(
    private val conversationId: String, private val store: () -> RoomKotlinTurnStore,
    private val enabled: Boolean, private val tasks: RoomScheduledTasks, private val now: () -> Long,
) : KotlinToolCatalog, KotlinTurnToolPolicy, KotlinTurnToolExecutor {
    private val spec = scheduleToolDeclaration()
    companion object {
        suspend fun create(db: LocalDatabase, conversationId: String, store: () -> RoomKotlinTurnStore, now: () -> Long): KotlinCoreScheduleAdapter {
            require(conversationId.isNotBlank())
            val tasks = RoomScheduledTasks(db)
            return KotlinCoreScheduleAdapter(conversationId, store, tasks.toolEnabled(), tasks, now)
        }
    }
    override fun coreToolGrant() = KotlinCoreToolGrant(conversationId, if (enabled) listOf(SCHEDULE) else emptyList())
    override fun declarations(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow) =
        if (conversation.id == conversationId && enabled && coreToolAllowedByAgent(conversation, agent, defaultAgent, SCHEDULE)) listOf(spec) else emptyList()
    override suspend fun permits(turnId: String, conversation: ConversationRow, call: ToolCallData): Boolean =
        enabled && conversation.id == conversationId && call.name == SCHEDULE &&
            store().coreToolGrant(turnId)?.let { it.conversationId == conversationId && SCHEDULE in it.allowedNames } == true &&
            store().expectedTool(turnId, call) == spec

    override suspend fun execute(turnId: String, call: ToolCallData) = store().executeRoomCoreTool(turnId, call) {
        require(call.name == SCHEDULE && store().expectedTool(turnId, call) == spec) { "Schedule tool declaration changed" }
        ScheduleToolAdapter(tasks, ScheduleCreationAuthorization { id, callId -> store().requireScheduleCreationAllowed(id, callId) }, now).execute(turnId, call)
    }
}
