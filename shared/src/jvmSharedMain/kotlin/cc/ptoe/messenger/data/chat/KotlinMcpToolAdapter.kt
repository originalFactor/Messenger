/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.cloud.RoomDeviceControlFence
import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.chat.KotlinMcpToolBinding
import cc.ptoe.messenger.domain.chat.KotlinMcpToolGrant
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor
import cc.ptoe.messenger.domain.chat.McpTurnSettledException
import cc.ptoe.messenger.domain.mcp.McpInventorySnapshot
import cc.ptoe.messenger.domain.mcp.McpInventoryTool
import cc.ptoe.messenger.domain.mcp.McpManager
import cc.ptoe.messenger.domain.mcp.McpToolCallOutcome
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Experimental, bound to the exact Room store and the manager's in-memory inventory. */
internal class KotlinMcpToolAdapter(
    private val db: LocalDatabase,
    private val conversationId: String,
    private val manager: McpManager,
    private val inventory: McpInventorySnapshot,
    private val selectedNames: Set<String> = inventory.tools.map { it.name }.toSet(),
    private val store: () -> RoomKotlinTurnStore,
) : KotlinToolCatalog, KotlinTurnToolPolicy, KotlinTurnToolExecutor {
    private val tools = inventory.tools.filter { it.name in selectedNames }.also {
        require(it.map { tool -> tool.name }.toSet() == selectedNames) { "Selected MCP tools are unavailable" }
    }
    private val grant = KotlinMcpToolGrant(conversationId, tools.map { it.binding() })
    private val authority = RuntimeWorkspaceFence.forDatabase(db)
    private val device = RoomDeviceControlFence.forDao(db.cloudSync())

    override fun mcpToolGrant() = grant
    override fun declarations(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow): List<ToolSpecDto> {
        require(conversation.id == conversationId) { "MCP conversation changed" }
        return tools.filter { runtimeToolAllowedByAgent(conversation, agent, defaultAgent, it.name, false) }.map {
            ToolSpecDto("function", ToolSpecFunctionDto(it.name, it.description, it.parameters))
        }
    }
    override suspend fun validateAdmission(conversation: ConversationRow, request: cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto) {
        // This runs inside Room's writer transaction. The current inventory is checked by
        // admitInventoryCall outside Room, avoiding a Room/manager lock-order inversion.
        check(conversation.id == conversationId) { "MCP conversation changed" }
    }
    override suspend fun permits(turnId: String, conversation: ConversationRow, call: ToolCallData): Boolean {
        val tool = tools.singleOrNull { it.name == call.name } ?: return false
        if (conversation.id != conversationId || store().mcpToolGrant(turnId) != grant) return false
        val expected = store().expectedTool(turnId, call) ?: return false
        return expected.function.description == tool.description && expected.function.parameters == tool.parameters
    }

    override suspend fun execute(turnId: String, call: ToolCallData): ToolExecutionResult {
        val tool = tools.singleOrNull { it.name == call.name } ?: error("MCP tool is unavailable")
        val receipt = store().registerMcpInvocation(turnId, call)
        var permits: List<RoomCloudProviderGate.ToolPermit> = emptyList()
        var admissionCompleted = false
        try {
            require(runCatching { Json.parseToJsonElement(call.arguments) }.getOrNull() is JsonObject) {
                "MCP tool arguments must be a JSON object"
            }
            val admission = authority.mutate { device.mutate {
                val (client, _) = manager.admitInventoryCall(inventory, tool, claim = {
                    store().claimMcpToolInvocation(receipt, grant, tool.binding()).also { permits = it }
                })
                Triple(client, authority.authoritySnapshot(), device.authoritySnapshot())
            } }
            val (client, authorityEpoch, deviceEpoch) = admission
            return when (val outcome = client.callToolOutcome(tool.remoteName, call.arguments) {
                manager.authorizeInventorySend(inventory, tool, client) {
                    authority.authorizeAuthority(authorityEpoch) {
                        device.authorizeAuthority(deviceEpoch) {
                            fun authorizeCloud(index: Int) {
                                if (index == permits.size) receipt.authorize()
                                else permits[index].authorize { authorizeCloud(index + 1) }
                            }
                            authorizeCloud(0)
                            admissionCompleted = true
                        }
                    }
                }
            }) {
                is McpToolCallOutcome.Result -> outcome.result
                McpToolCallOutcome.NotDispatched -> {
                    store().rejectUndispatchedMcpTool(receipt, afterAdmission = admissionCompleted, detail = "MCP server disconnected before dispatch")
                    throw McpTurnSettledException()
                }
                is McpToolCallOutcome.ProtocolError -> error("MCP protocol error (${outcome.code}): ${outcome.message}; tool outcome is unknown")
                is McpToolCallOutcome.Unknown -> error("MCP tool outcome is unknown; recovery is required")
            }
        } catch (cancelled: CancellationException) {
            if (!admissionCompleted) {
                store().rejectUndispatchedMcpTool(receipt, afterAdmission = false, detail = "MCP call cancelled before dispatch")
                throw McpTurnSettledException()
            }
            throw IllegalStateException("MCP tool outcome is unknown; recovery is required", cancelled)
        } catch (failure: McpTurnSettledException) { throw failure }
        catch (failure: Exception) {
            if (!admissionCompleted) {
                store().rejectUndispatchedMcpTool(receipt, afterAdmission = false, detail = failure.message ?: "MCP call was not dispatched")
                throw McpTurnSettledException()
            }
            throw failure
        } finally { permits.forEach { it.release() }; store().releaseMcpInvocation(receipt) }
    }

    private fun McpInventoryTool.binding() = KotlinMcpToolBinding(name, serverId, remoteName, description, parameters)
}
