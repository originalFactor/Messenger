/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.chat.KotlinCoreToolGrant
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor

/** Routes a frozen tool name to exactly one implementation. */
internal data class KotlinToolComponent(
    val names: Set<String>, val catalog: KotlinToolCatalog,
    val policy: KotlinTurnToolPolicy, val executor: KotlinTurnToolExecutor,
)

/** One instance is bound to one conversation and its admission snapshot. */
internal class CompositeKotlinTools(private val components: List<KotlinToolComponent>) :
    KotlinToolCatalog, KotlinTurnToolPolicy, KotlinTurnToolExecutor {
    private val routes = buildMap {
        components.forEach { component -> component.names.forEach { name ->
            require(name.isNotBlank() && put(name, component) == null) { "Duplicate tool implementation" }
        } }
    }
    override fun toolGrant() = components.mapNotNull { it.catalog.toolGrant() }.let {
        require(it.size <= 1) { "Multiple workspace scopes are not supported" }; it.singleOrNull()
    }
    override fun coreToolGrant(): KotlinCoreToolGrant? {
        val grants = components.mapNotNull { it.catalog.coreToolGrant() }
        if (grants.isEmpty()) return null
        require(grants.map { it.conversationId }.distinct().size == 1) { "Core scopes belong to different conversations" }
        val names = grants.flatMap { it.allowedNames }
        require(names.distinct().size == names.size) { "Duplicate Core tool grant" }
        return KotlinCoreToolGrant(grants.first().conversationId, names)
    }
    override fun promptSuffix() = components.joinToString("") { it.catalog.promptSuffix() }
    override fun declarations(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow) = components.flatMap { component ->
        requireNotNull(component.catalog.declarations(conversation, agent, defaultAgent)) { "Tool catalog unavailable" }.also { tools ->
            require(tools.all { it.function.name in component.names }) { "Tool catalog exceeds its implementation scope" }
        }
    }.also { require(it.map { tool -> tool.function.name }.distinct().size == it.size) { "Duplicate tool declaration" } }

    override suspend fun validateAdmission(conversation: ConversationRow, request: ChatCompletionRequestDto) {
        components.forEach { it.catalog.validateAdmission(conversation, request) }
    }
    override suspend fun permits(turnId: String, conversation: ConversationRow, call: ToolCallData): Boolean =
        routes[call.name]?.policy?.permits(turnId, conversation, call) == true
    override suspend fun execute(turnId: String, call: ToolCallData) =
        (routes[call.name] ?: error("Tool implementation is unavailable")).executor.execute(turnId, call)
}
