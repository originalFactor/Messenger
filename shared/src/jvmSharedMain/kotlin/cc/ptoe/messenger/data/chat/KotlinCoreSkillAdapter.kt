/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.skill.RoomSkillStore
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.chat.KotlinCoreToolGrant
import cc.ptoe.messenger.domain.chat.KotlinTurnToolExecutor
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

private const val SKILLS = "messenger_skills"
private val skillSchema = Json.parseToJsonElement("""{"type":"object","properties":{"name":{"type":"string"},"path":{"type":"string","default":"SKILL.md"}},"required":["name"],"additionalProperties":false}""").jsonObject
internal val coreSkillSpec = ToolSpecDto("function", ToolSpecFunctionDto(SKILLS,
    "Read an enabled Agent Skill's bundled UTF-8 file. Defaults to SKILL.md. This only reads text; it never executes scripts or grants permissions.", skillSchema))

/** A turn-bound, workspace-free capability. Skill packages remain untrusted local data. */
internal class KotlinCoreSkillAdapter private constructor(
    private val conversationId: String,
    private val skills: RoomSkillStore,
    private val store: () -> RoomKotlinTurnStore,
    private val discovery: String?,
) : KotlinToolCatalog, KotlinTurnToolPolicy, KotlinTurnToolExecutor {
    companion object {
        suspend fun create(db: LocalDatabase, conversationId: String, store: () -> RoomKotlinTurnStore): KotlinCoreSkillAdapter {
            require(conversationId.isNotBlank())
            val skills = RoomSkillStore(db)
            return KotlinCoreSkillAdapter(conversationId, skills, store, skills.discovery(permitted = true))
        }
    }

    override fun coreToolGrant() = KotlinCoreToolGrant(conversationId, if (discovery == null) emptyList() else listOf(SKILLS))
    override fun promptSuffix() = discovery.orEmpty()
    override fun declarations(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow): List<ToolSpecDto> {
        require(conversation.id == conversationId) { "Skill scope belongs to another conversation" }
        return if (discovery != null && coreToolAllowedByAgent(conversation, agent, defaultAgent, SKILLS)) listOf(coreSkillSpec) else emptyList()
    }

    override suspend fun validateAdmission(conversation: ConversationRow, request: ChatCompletionRequestDto) {
        if (request.tools.orEmpty().any { it.function.name == SKILLS }) {
            require(conversation.id == conversationId && discovery != null && skills.discovery(permitted = true) == discovery) { "Enabled Skills changed; prepare the turn again" }
        }
    }

    override suspend fun permits(turnId: String, conversation: ConversationRow, call: ToolCallData): Boolean =
        conversation.id == conversationId && call.name == SKILLS && discovery != null &&
            store().coreToolGrant(turnId)?.let { it.conversationId == conversationId && SKILLS in it.allowedNames } == true &&
            store().expectedTool(turnId, call) == coreSkillSpec

    override suspend fun execute(turnId: String, call: ToolCallData): ToolExecutionResult {
        check(call.name == SKILLS && store().expectedTool(turnId, call) == coreSkillSpec) { "Skill tool declaration changed" }
        return store().executeRoomCoreTool(turnId, call) {
            require(call.arguments.encodeToByteArray().size <= 4096) { "Skill arguments are too large" }
            val args = Json.parseToJsonElement(call.arguments) as? JsonObject ?: error("Invalid skill arguments")
            require(args.keys.all { it == "name" || it == "path" }) { "Invalid skill arguments" }
            val name = args["name"] as? JsonPrimitive
            require(name?.isString == true) { "Skill name is required" }
            val path = args["path"]
            require(path == null || path is kotlinx.serialization.json.JsonNull || (path is JsonPrimitive && path.isString)) { "Invalid skill path" }
            ToolExecutionResult(skills.read(name.content, (path as? JsonPrimitive)?.contentOrNull ?: "SKILL.md", permitted = true))
        }
    }
}
