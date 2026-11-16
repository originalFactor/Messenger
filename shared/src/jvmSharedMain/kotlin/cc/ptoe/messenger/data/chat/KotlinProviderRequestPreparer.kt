/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.ContentPartCodec
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.*
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.chat.KotlinRuntimeToolGrant
import cc.ptoe.messenger.domain.chat.KotlinCoreToolGrant
import cc.ptoe.messenger.presentation.utils.extractThinkContent
import cc.ptoe.messenger.presentation.utils.stripThinkBlock
import kotlinx.serialization.json.*

/** Returns the already scoped, policy-filtered tool declarations; null means unavailable. */
fun interface KotlinToolCatalog {
    fun declarations(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow): List<ToolSpecDto>?
    fun toolGrant(): KotlinRuntimeToolGrant? = null
    fun coreToolGrant(): KotlinCoreToolGrant? = null
    fun promptSuffix(): String = ""
    suspend fun validateAdmission(conversation: ConversationRow, request: ChatCompletionRequestDto) {}
}

/** Builds direct-provider requests with Rust-compatible bounded history. */
class KotlinProviderRequestPreparer(private val catalog: KotlinToolCatalog? = null) : KotlinTurnRequestPreparer {
    override fun toolGrant(): KotlinRuntimeToolGrant? = catalog?.toolGrant()
    override fun coreToolGrant(): KotlinCoreToolGrant? = catalog?.coreToolGrant()
    override suspend fun validateAdmission(conversation: ConversationRow, request: ChatCompletionRequestDto) { catalog?.validateAdmission(conversation, request) }
    override fun effectiveSystemPrompt(agent: AgentRow, defaultAgent: AgentRow, request: ChatCompletionRequestDto): String =
        super.effectiveSystemPrompt(agent, defaultAgent, request) +
            if (request.tools.orEmpty().any { it.function.name == "messenger_skills" }) catalog?.promptSuffix().orEmpty() else ""
    override fun supportsContextCompaction(): Boolean = true
    override fun rebuildContext(conversation: ConversationRow, history: List<MessageRow>, prompt: String): List<ChatMessageDto> = contextMessages(conversation, history, prompt)
    override fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow,
        model: ModelRow, provider: ProviderRow, history: List<MessageRow>): ChatCompletionRequestDto =
        prepare(conversation, agent, defaultAgent, model, provider, history, disableTools = false)

    override fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow,
        model: ModelRow, provider: ProviderRow, history: List<MessageRow>, disableTools: Boolean): ChatCompletionRequestDto {
        val useDefault = !agent.isDefault
        val toolsEnabled = !disableTools && (conversation.overrideToolsEnabled ?: if (useDefault && agent.toolsFollowDefault) defaultAgent.toolsEnabled else agent.toolsEnabled)
        val tools = if (toolsEnabled) {
            requireNotNull(catalog?.declarations(conversation, agent, defaultAgent)) { "Tool policy and catalog are not available" }
                .also { specs ->
                    require(specs.map { it.function.name }.distinct().size == specs.size && specs.all { it.type == "function" && it.function.name.isNotBlank() && it.function.parameters is JsonObject }) { "Invalid tool catalog" }
                }
        } else emptyList()
        val basePrompt = if (useDefault && agent.followDefaultSystemPrompt) defaultAgent.systemPrompt else agent.systemPrompt
        val prompt = basePrompt + if (tools.any { it.function.name == "messenger_skills" }) catalog?.promptSuffix().orEmpty() else ""
        val temperature = conversation.overrideTemperature ?: if (useDefault && agent.followDefaultTemperature) defaultAgent.temperature else agent.temperature
        val topP = conversation.overrideTopP ?: if (useDefault && agent.followDefaultTopP) defaultAgent.topP else agent.topP
        val maxTokens = conversation.overrideMaxTokens ?: if (useDefault && agent.followDefaultMaxTokens) defaultAgent.maxTokens else agent.maxTokens
        val effort = conversation.overrideReasoningEffort ?: if (useDefault && agent.followDefaultReasoningEffort) defaultAgent.reasoningEffort else agent.reasoningEffort
        val messages = contextMessages(conversation, history, prompt)
        require(maxTokens == null || maxTokens in 0..Int.MAX_VALUE.toLong()) { "Invalid maximum token count" }
        return ChatCompletionRequestDto(model = model.modelId, messages = messages, temperature = temperature?.toFloat(),
            topP = topP?.toFloat(), maxTokens = maxTokens?.toInt(), reasoningEffort = effort,
            thinking = if (effort == "none") ThinkingDto("disabled") else null, tools = tools.ifEmpty { null }, stream = true)
    }

    private fun contextMessages(conversation: ConversationRow, history: List<MessageRow>, prompt: String): List<ChatMessageDto> {
        val sent = history.filter { it.status.equals("sent", ignoreCase = true) && it.timestamp >= conversation.contextSummaryUntil }
        val hasSummary = conversation.contextSummaryUntil > 0 && !conversation.contextSummary.isNullOrBlank()
        val includeSummary = hasSummary && sent.size < 20
        val recent = sent.takeLast(20).let { if (includeSummary) it else it.dropWhile { row -> row.role == "tool" } }
        val decoded = recent.map { it to parts(it) }
        val context = buildList {
            if (includeSummary) add(ChatMessageDto(role = "system", content = JsonPrimitive("[Summary of earlier conversation]\n${conversation.contextSummary}")))
            decoded.forEachIndexed { index, (row, parts) ->
                add(message(row, parts, decoded.drop(index + 1), conversation.reasoningFormat))
            }
        }
        return (if (prompt.isNotBlank()) listOf(ChatMessageDto(role = "system", content = JsonPrimitive(prompt))) else emptyList()) + context
    }

    private fun parts(row: MessageRow): List<ContentPart> {
        val raw = row.partsJson
        return if (raw.isNullOrBlank()) emptyList() else {
            val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            requireNotNull(parsed) { "Message contains invalid content parts" }
            ContentPartCodec.decode(raw).also { require(it.size == parsed.size) { "Message contains unsupported content parts" } }
        }
    }

    private fun artifactReference(value: JsonElement?): JsonObject? {
        val ref = value as? JsonObject ?: return null
        val type = ref["type"] as? JsonPrimitive ?: return null
        val id = ref["id"] as? JsonPrimitive ?: return null
        val revision = ref["revision"] as? JsonPrimitive ?: return null
        if (!type.isString || type.content != "artifact" || !id.isString || id.content.isEmpty() ||
            revision.isString || (revision.longOrNull ?: 0) <= 0) return null
        return ref
    }

    private fun compactArtifactCall(call: ContentPart.ToolCall, following: List<Pair<MessageRow, List<ContentPart>>>): String {
        if (call.name != "messenger_artifacts") return call.arguments
        val input = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull() ?: return call.arguments
        val action = input["action"] as? JsonPrimitive ?: return call.arguments
        if (!action.isString || action.content !in setOf("create", "update")) return call.arguments
        val ref = following.asSequence().flatMap { it.second.asSequence() }.filterIsInstance<ContentPart.ToolResult>()
            .filter { it.callId == call.callId && it.name == call.name && !it.isError }
            .mapNotNull { artifactReference(it.structuredContent) }.firstOrNull() ?: return call.arguments
        return buildJsonObject { put("action", action); put("id", ref.getValue("id")); put("revision", ref.getValue("revision")); put("contentStored", true) }.toString()
    }

    private fun message(row: MessageRow, parts: List<ContentPart>, following: List<Pair<MessageRow, List<ContentPart>>>, reasoningFormat: String?): ChatMessageDto {
        require(row.role in setOf("user", "assistant", "system", "tool")) { "Unsupported message role" }
        if (row.role == "tool") {
            val result = parts.filterIsInstance<ContentPart.ToolResult>().singleOrNull()
            requireNotNull(result) { "Tool result is missing its call ID" }
            val ref = if (result.name == "messenger_artifacts" && !result.isError && following.any { it.first.role == "user" }) artifactReference(result.structuredContent) else null
            val output = ref?.let { "Artifact ${it.getValue("id")} revision ${it.getValue("revision")} is stored. Use messenger_artifacts get to read its source when needed." } ?: result.output
            return ChatMessageDto(role = "tool", content = JsonPrimitive(output), toolCallId = result.callId)
        }
        val images = parts.filterIsInstance<ContentPart.Image>()
        val calls = parts.filterIsInstance<ContentPart.ToolCall>()
        require(images.isEmpty() || (row.role == "user" && calls.isEmpty())) { "Unsupported mixed image content" }
        if (images.isNotEmpty()) {
            val content = buildJsonArray { parts.forEach { part -> when (part) {
                is ContentPart.Text -> add(buildJsonObject { put("type", "text"); put("text", part.text) })
                is ContentPart.Image -> { require(part.image.dataUri.startsWith("data:image/")) { "Image data is unavailable" }; add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", part.image.dataUri) }) }) }
                else -> error("Unsupported image content")
            } } }
            return ChatMessageDto(role = row.role, content = content)
        }
        require(parts.none { it is ContentPart.ToolResult } && (calls.isEmpty() || row.role == "assistant")) { "Unsupported tool content" }
        val textParts = parts.filterIsInstance<ContentPart.Text>()
        val text = if (textParts.isEmpty()) row.content else textParts.joinToString("\n") { it.text }
        val (reasoning, body) = when {
            row.role != "assistant" || reasoningFormat == "think_tag" -> null to text
            reasoningFormat == "reasoning_summary" -> null to stripThinkBlock(text)
            else -> extractThinkContent(text)
        }
        return ChatMessageDto(role = row.role, content = if (body.isEmpty() && calls.isNotEmpty()) JsonNull else JsonPrimitive(body), reasoningContent = reasoning,
            toolCalls = calls.takeIf { it.isNotEmpty() }?.map { ToolCallDto(id = it.callId, type = "function", function = ToolCallFunctionDto(it.name, compactArtifactCall(it, following))) })
    }
}
