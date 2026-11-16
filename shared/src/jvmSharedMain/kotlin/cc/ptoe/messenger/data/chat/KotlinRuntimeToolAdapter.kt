/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.chat.KotlinRuntimeToolGrant
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor
import cc.ptoe.messenger.domain.chat.RuntimeToolNotDispatchedException
import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.domain.tool.TerminalTool
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import cc.ptoe.messenger.domain.tool.builtinChatToolsSupported
import cc.ptoe.messenger.domain.tool.createBuiltinChatTools
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Verifies workspace ownership, Runtime availability and the captured host/build identity. */
fun interface RuntimeWorkspaceVerifier {
    suspend fun isAvailable(grant: KotlinRuntimeToolGrant): Boolean
}

/** Host tracks a stable invocation token; lost or unavailable stop evidence is UNKNOWN. */
interface TrackedWorkspaceHost {
    val protocolAvailable: Boolean
    /** Only operations whose synchronous completion is trustworthy for this host. */
    val supportedOperations: Set<String>
    suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult
    suspend fun status(token: String): TrackedWorkspaceStatus
    /** Request cancellation and return STOPPED only on independently verified completion. */
    suspend fun cancelAndAwaitStopped(token: String): TrackedWorkspaceStatus = TrackedWorkspaceStatus.UNKNOWN
}
enum class TrackedWorkspaceStatus { UNKNOWN, RUNNING, STOPPED }

fun trackedToolToken(turnId: String, callId: String): String =
    MessageDigest.getInstance("SHA-256").digest("${turnId.length}:$turnId$callId".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** One immutable conversation/workspace capability. Reconstruct only from a persisted turn grant. */
class KotlinRuntimeToolAdapter(
    private val db: LocalDatabase,
    private val grant: KotlinRuntimeToolGrant,
    private val verifier: RuntimeWorkspaceVerifier,
    private val store: () -> RoomKotlinTurnStore,
    private val trackedHost: TrackedWorkspaceHost,
    private val fence: RuntimeWorkspaceFence,
    private val toolsForWorkspace: (String?) -> List<ChatTool> = ::createBuiltinChatTools,
    private val hostAvailable: () -> Boolean = ::builtinChatToolsSupported,
) : KotlinToolCatalog, KotlinTurnToolPolicy, KotlinTurnToolExecutor {
    init {
        require(grant.conversationId.isNotBlank() && grant.hostIdentity.isNotBlank() && File(grant.workspaceRoot).isAbsolute)
        require(grant.allowedNames.distinct().size == grant.allowedNames.size)
        require(grant.allowedNames.all { it in setOf("glob", "grep", "read", "edit", "create") }) { "This Runtime tool cannot yet be tracked safely" }
        require(grant.allowedNames.all { it in trackedHost.supportedOperations }) { "Runtime cannot safely track a requested workspace operation" }
        fence.requireOwner(db)
    }
    override fun toolGrant() = grant

    override fun declarations(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow): List<ToolSpecDto>? {
        require(conversation.id == grant.conversationId && conversation.projectId == grant.projectId) { "Tool workspace changed" }
        check(hostAvailable() && trackedHost.protocolAvailable) { "Runtime does not support tracked workspace operations" }
        check(grant.allowedNames.all { it in trackedHost.supportedOperations }) { "Runtime workspace capability changed" }
        val tools = toolsForWorkspace(grant.workspaceRoot)
        require(tools.map { it.name }.distinct().size == tools.size) { "Duplicate tool names" }
        require(grant.allowedNames.all { allowed -> tools.any { it.name == allowed } }) { "Runtime tool is unavailable" }
        return tools.filter { permitted(conversation, agent, defaultAgent, it) }.map { tool ->
            val parameters = Json.parseToJsonElement(tool.parametersJson) as? JsonObject ?: error("Invalid tool schema")
            ToolSpecDto("function", ToolSpecFunctionDto(tool.name, tool.description, parameters))
        }
    }

    override suspend fun permits(turnId: String, conversation: ConversationRow, call: ToolCallData): Boolean {
        return validatedTool(turnId, conversation, call) != null
    }

    private suspend fun validatedTool(turnId: String, conversation: ConversationRow, call: ToolCallData): ChatTool? {
        if (store().toolGrant(turnId) != grant || !hostAvailable() || !trackedHost.protocolAvailable ||
            call.name !in trackedHost.supportedOperations || !verifier.isAvailable(grant)) return null
        val expected = store().expectedTool(turnId, call) ?: return null
        if (conversation.id != grant.conversationId || conversation.projectId != grant.projectId) return null
        if (grant.projectId != null) {
            val project = db.rows().project(grant.projectId) ?: return null
            if (project.workspace != grant.workspaceRoot || project.deviceId != grant.deviceId) return null
        }
        val agent = db.rows().agent(conversation.agentId) ?: return null
        val default = db.rows().defaultAgents().singleOrNull() ?: return null
        val tools = toolsForWorkspace(grant.workspaceRoot)
        if (tools.count { it.name == call.name } != 1) return null
        val current = tools.single { it.name == call.name }
        val schema = Json.parseToJsonElement(current.parametersJson) as? JsonObject ?: return null
        if (expected.function.description != current.description || expected.function.parameters != schema) return null
        return current.takeIf { permitted(conversation, agent, default, it) }
    }

    override suspend fun execute(turnId: String, call: ToolCallData): ToolExecutionResult {
        val token = trackedToolToken(turnId, call.callId)
        var claimed = false
        try { return fence.execute(token, trackedHost) { markDispatched ->
            val conversation = db.rows().conversation(grant.conversationId) ?: error("Conversation is unavailable")
            validatedTool(turnId, conversation, call) ?: error("Runtime tool permission or workspace is no longer available")
            // The active grant, workspace and live policy are checked atomically with the claim.
            store().claimRuntimeToolInvocation(turnId, call, grant)
            claimed = true
            markDispatched()
            val result = trackedHost.execute(token, grant.workspaceRoot, call)
            check(trackedHost.protocolAvailable && trackedHost.status(token) == TrackedWorkspaceStatus.STOPPED) {
                "Workspace operation stop is unverified"
            }
            result
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!claimed) throw RuntimeToolNotDispatchedException(failure)
            throw failure
        }
    }

    private fun permitted(conversation: ConversationRow, agent: AgentRow, default: AgentRow, tool: ChatTool): Boolean {
        return tool.name in grant.allowedNames && runtimeToolAllowedByAgent(conversation, agent, default, tool.name, tool.writeAccess)
    }
}

/** Must be evaluated again inside the transaction that claims the invocation. */
internal fun runtimeToolAllowedByAgent(conversation: ConversationRow, agent: AgentRow, default: AgentRow,
    name: String, writes: Boolean): Boolean {
    if (name.startsWith("messenger_") || (!conversation.writable && (writes || name == TerminalTool.TOOL_NAME))) return false
    val follow = !agent.isDefault && agent.toolsFollowDefault
    if (!(conversation.overrideToolsEnabled ?: if (follow) default.toolsEnabled else agent.toolsEnabled)) return false
    val config = conversation.overrideToolsConfig ?: if (follow) default.toolsConfig else agent.toolsConfig
    val parsed = if (config.isBlank()) emptyMap() else
        runCatching { Json.decodeFromString<Map<String, Boolean>>(config) }.getOrElse { return false }
    return parsed[name] != false
}
