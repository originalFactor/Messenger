/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalRowsDao
import kotlinx.serialization.json.Json

internal val KOTLIN_CORE_TOOLS = setOf("messenger_learning", "messenger_artifacts", "messenger_generate_image", "messenger_schedule_task", "messenger_agents", "messenger_skills")

internal fun coreToolAllowedByAgent(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow, name: String): Boolean {
    if (name !in KOTLIN_CORE_TOOLS) return false
    val follow = !agent.isDefault && agent.toolsFollowDefault
    val enabled = conversation.overrideToolsEnabled ?: if (follow) defaultAgent.toolsEnabled else agent.toolsEnabled
    if (!enabled) return false
    val raw = conversation.overrideToolsConfig ?: if (follow) defaultAgent.toolsConfig else agent.toolsConfig
    val config = if (raw.isBlank()) emptyMap() else runCatching { Json.decodeFromString<Map<String, Boolean>>(raw) }.getOrDefault(emptyMap())
    if (config[name] == false) return false
    return name != "messenger_schedule_task" ||
        (conversation.writable && conversation.overrideToolsEnabled == null && conversation.overrideToolsConfig == null)
}

/** Must run in the caller's Room transaction, before granting or claiming a tool call. */
internal suspend fun LocalRowsDao.requireCoreToolAllowed(conversationId: String, name: String) {
    val conversation = requireNotNull(conversation(conversationId)) { "Conversation not found" }
    val agent = requireNotNull(agent(conversation.agentId)) { "Agent not found" }
    val defaultAgent = defaultAgents().singleOrNull() ?: error("Default Agent unavailable")
    check(coreToolAllowedByAgent(conversation, agent, defaultAgent, name)) { "Tool is disabled by Agent policy" }
    val setting = when (name) {
        "messenger_learning" -> "learning_tools_enabled"
        "messenger_artifacts" -> "artifact_tools_enabled"
        "messenger_generate_image" -> "image_generation_enabled"
        "messenger_schedule_task" -> "scheduled_task_tool_enabled"
        "messenger_agents" -> "mcp_agent_delegation_enabled"
        "messenger_skills" -> null
        else -> error("Unknown Core tool")
    }
    if (setting != null) check(getValue(setting)?.value == "true") { "Tool capability is disabled" }
}
