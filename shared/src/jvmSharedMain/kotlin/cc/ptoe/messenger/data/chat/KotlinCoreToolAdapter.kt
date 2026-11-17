/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.chat.KotlinCoreToolGrant
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.coroutines.CancellationException

/** Implementations must claim their invocation in the same transaction as the protected action. */
internal fun interface KotlinCoreToolAction {
    suspend fun execute(turnId: String, call: ToolCallData): ToolExecutionResult
}

internal class KotlinCoreToolAdapter private constructor(
    private val conversationId: String, private val store: () -> RoomKotlinTurnStore,
    private val spec: ToolSpecDto, private val enabled: Boolean, private val action: KotlinCoreToolAction,
) : KotlinToolCatalog, KotlinTurnToolPolicy, KotlinTurnToolExecutor {
    companion object {
        suspend fun create(db: LocalDatabase, conversationId: String, store: () -> RoomKotlinTurnStore,
            spec: ToolSpecDto, setting: String, action: KotlinCoreToolAction): KotlinCoreToolAdapter {
            require(conversationId.isNotBlank() && spec.function.name in KOTLIN_CORE_TOOLS)
            return KotlinCoreToolAdapter(conversationId, store, spec, db.rows().getValue(setting)?.value == "true", action)
        }
    }
    override fun coreToolGrant() = KotlinCoreToolGrant(conversationId, if (enabled) listOf(spec.function.name) else emptyList())
    override fun declarations(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow) =
        if (conversation.id == conversationId && enabled && coreToolAllowedByAgent(conversation, agent, defaultAgent, spec.function.name)) listOf(spec) else emptyList()
    override suspend fun permits(turnId: String, conversation: ConversationRow, call: ToolCallData): Boolean =
        enabled && conversation.id == conversationId && call.name == spec.function.name &&
            store().coreToolGrant(turnId)?.let { it.conversationId == conversationId && call.name in it.allowedNames } == true &&
            store().expectedTool(turnId, call) == spec
    override suspend fun execute(turnId: String, call: ToolCallData): ToolExecutionResult {
        require(call.name == spec.function.name && store().expectedTool(turnId, call) == spec) { "Core tool declaration changed" }
        return try { action.execute(turnId, call) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val detail = failure.message ?: "Core tool failed"
            if (store().coreToolInvocationStarted(turnId, call)) throw IllegalStateException("$detail; the operation may have completed", failure)
            else store().executeRoomCoreTool(turnId, call) { ToolExecutionResult(detail, isError = true) }
        }
    }
}
