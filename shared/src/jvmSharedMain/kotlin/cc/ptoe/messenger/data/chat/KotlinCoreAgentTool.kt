/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.cloud.SyncCollection
import cc.ptoe.messenger.data.cloud.SyncMutation
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.chat.KotlinDelegationParent
import cc.ptoe.messenger.domain.chat.KotlinTurnPhase
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

private const val AGENTS = "messenger_agents"
private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"action":{"type":"string","enum":["list","call"]},"agentId":{"type":"string"},"task":{"type":"string"}},"required":["action"],"additionalProperties":false}""").jsonObject
internal val coreAgentSpec = ToolSpecDto("function", ToolSpecFunctionDto(AGENTS, "List available agents or delegate a task to an agent. ACP agent IDs start with acp:.", schema))

/** The provided ACP host must be a real lifecycle owner that stops and joins its worker. */
internal suspend fun coreAgentTool(db: LocalDatabase, conversationId: String, store: () -> RoomKotlinTurnStore,
    native: KotlinDelegatedTurnHost, acp: KotlinDelegatedAcpHost?, now: () -> Long): KotlinCoreToolAdapter {
    return KotlinCoreToolAdapter.create(db, conversationId, store, coreAgentSpec, "mcp_agent_delegation_enabled") { turnId, call ->
        require(call.arguments.encodeToByteArray().size <= 132_000) { "Delegation arguments exceed the limit" }
        val args = Json.parseToJsonElement(call.arguments) as? JsonObject ?: error("Invalid delegation arguments")
        require(args.keys.all { it in setOf("action", "agentId", "task") }) { "Unknown delegation argument" }
        when (args.requiredString("action")) {
            "list" -> store().executeRoomCoreTool(turnId, call) {
                val rows = db.rows()
                val nativeAgents = rows.delegatableAgents().map { agent -> buildJsonObject {
                    put("id", agent.id); put("name", agent.name); put("kind", "messenger")
                } }
                val profiles = rows.getValue("device_control_config")?.value?.let { Json.parseToJsonElement(it) as? JsonObject }
                    ?.get("agents") as? JsonArray ?: JsonArray(emptyList())
                val external = profiles.mapNotNull { value ->
                    val item = value as? JsonObject ?: return@mapNotNull null
                    val id = item.optionalString("id")?.takeIf { it.isNotBlank() && it.length <= 80 } ?: return@mapNotNull null
                    val enabled = item["enabled"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: false } ?: true
                    if (!enabled) return@mapNotNull null
                    buildJsonObject { put("id", "acp:$id"); put("name", item.optionalString("name").orEmpty()); put("description", item.optionalString("description").orEmpty()); put("kind", "acp") }
                }
                ToolExecutionResult(buildJsonObject { put("agents", JsonArray(nativeAgents + external)) }.toString())
            }
            "call" -> {
                val agentId = args.requiredString("agentId")
                val task = args.requiredString("task")
                require(task.encodeToByteArray().size <= 128_000) { "Delegated task exceeds the limit" }
                val external = agentId.startsWith("acp:")
                if (external) checkNotNull(acp) { "ACP delegation is unavailable on this platform" }
                var target: KotlinDelegatedTarget? = null
                val admitted = store().executeRoomCoreTool(turnId, call) {
                    val rows = db.rows()
                    val parent = requireNotNull(rows.conversation(conversationId)) { "Conversation not found" }
                    val project = parent.projectId?.let { requireNotNull(rows.project(it)) { "Project unavailable" } }
                    if (project != null) {
                        val device = rows.getValue("device_id")?.value
                        check(project.deviceId == null || project.deviceId == device) { "Delegation requires a local project" }
                    }
                    if (external) {
                        check(parent.writable && project != null) { "ACP delegation requires a writable local project" }
                        val profiles = rows.getValue("device_control_config")?.value?.let { Json.parseToJsonElement(it) as? JsonObject }
                            ?.get("agents") as? JsonArray ?: error("ACP profiles unavailable")
                        val profile = profiles.mapNotNull { it as? JsonObject }.singleOrNull { it.optionalString("id") == agentId.removePrefix("acp:") }
                            ?: error("ACP profile unavailable")
                        check(profile["enabled"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull == true } ?: true) { "ACP profile is disabled" }
                    }
                    val childId = if (external) "acp:${randomUuid()}" else randomUuid()
                    if (!external) {
                        val agent = requireNotNull(rows.agent(agentId)) { "Agent not found" }
                        check(agent.role != "title" && agent.defaultModelId != null) { "Delegated Agent requires a model" }
                        val default = rows.defaultAgents().singleOrNull() ?: error("Default Agent unavailable")
                        val modelId = if (!agent.isDefault && agent.followDefaultModel) default.defaultModelId else agent.defaultModelId
                        val model = modelId?.let { rows.model(it) } ?: error("Delegated model unavailable")
                        check(model.isEnabled && rows.provider(model.providerId) != null) { "Delegated model unavailable" }
                        val time = now(); require(time >= 0)
                        rows.upsertConversations(listOf(ConversationRow(childId, "新对话", model.providerId, agent.id, parent.projectId,
                            null, null, null, null, null, null, null, parent.writable, time, time, null, null, null, 0, 0, 0)))
                        db.cloudSync().enqueueLocal(SyncCollection.CONVERSATION, childId, SyncMutation.UPSERT)
                    }
                    store().bindDelegatedChildInTransaction(turnId, call, childId, agentId, parent.projectId, parent.writable)
                    target = KotlinDelegatedTarget(childId, agentId, parent.projectId, project?.workspace, parent.writable)
                    ToolExecutionResult("Delegated turn admitted")
                }
                if (admitted.isError) admitted else {
                    val selected = checkNotNull(target)
                    val parent = KotlinDelegationParent(turnId, call.callId)
                    val answer = coroutineScope {
                        val operation = async(start = CoroutineStart.LAZY) {
                            if (external) checkNotNull(acp).run(parent, selected, task) else native.run(parent, selected, task)
                        }
                        val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
                            store().observe(turnId).first { it.phase != KotlinTurnPhase.EXECUTING_TOOL }
                            operation.cancel()
                        }
                        try { operation.start(); operation.await() } finally { monitor.cancelAndJoin() }
                    }
                    ToolExecutionResult(answer)
                }
            }
            else -> error("Delegation action must be list or call")
        }
    }
}
