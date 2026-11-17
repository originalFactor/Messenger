/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.syncId(): String = string("_id")?.takeIf { it.isNotBlank() } ?: error("Cloud sync document is missing _id")
internal fun JsonObject.tombstone() = bool("deleted") ?: false
private fun JsonObject.array(key: String): List<JsonObject> = when (val value = this[key]) {
    null -> emptyList()
    is JsonArray -> value.map { it as? JsonObject ?: error("Invalid $key element") }
    else -> error("Invalid $key list")
}
private fun JsonObject.field(key: String): String? = (this[key] as? JsonObject)?.toString()

internal fun JsonObject.agentRow(old: AgentRow?): AgentRow = AgentRow(
    id = syncId(), name = string("name") ?: "", avatar = old?.avatar?.takeUnless { it.startsWith("https://") || it.startsWith("http://") } ?: string("avatarUrl"),
    systemPrompt = string("systemPrompt") ?: "", description = string("description") ?: "", defaultModelId = string("defaultModelId"),
    temperature = double("temperature").takeUnless { bool("temperatureEnabled") == false },
    topP = double("topP").takeUnless { bool("topPEnabled") == false }, maxTokens = long("maxTokens"), reasoningEffort = string("reasoningEffort"),
    isDefault = bool("isDefault") ?: false, followDefaultSystemPrompt = bool("followDefaultSystemPrompt") ?: false,
    followDefaultModel = bool("followDefaultModel") ?: false, followDefaultTemperature = bool("followDefaultTemperature") ?: false,
    followDefaultTopP = bool("followDefaultTopP") ?: false, followDefaultMaxTokens = bool("followDefaultMaxTokens") ?: false,
    followDefaultReasoningEffort = bool("followDefaultReasoningEffort") ?: false, marketAgentId = string("marketAgentId"),
    marketAgentVersion = long("marketAgentVersion"), marketAgentRole = string("marketAgentRole"), role = string("role") ?: "chat",
    toolsEnabled = bool("toolsEnabled") ?: false, toolsFollowDefault = bool("toolsFollowDefault") ?: false,
    toolsConfig = field("toolsConfig") ?: "", createdAt = long("createdAt") ?: 0, updatedAt = long("updatedAt") ?: 0,
)
internal fun JsonObject.providerRow(old: ProviderRow?, restoring: Boolean): ProviderRow = ProviderRow(
    id = syncId(), name = string("name") ?: "", baseUrl = string("baseUrl") ?: "", apiKey = string("apiKey") ?: "",
    apiFormat = string("apiFormat")?.takeIf { it in setOf("chat", "responses", "messages", "interactions", "auto") } ?: "chat",
    nativeWebSearch = !restoring && old?.nativeWebSearch == true, createdAt = long("createdAt") ?: 0, updatedAt = long("updatedAt") ?: 0,
)
internal fun JsonObject.modelRows(providerId: String, existing: Map<String, ModelRow>): List<ModelRow> = array("models").map { m ->
    val id = m.string("id")?.takeIf { it.isNotBlank() } ?: error("Cloud model is missing id")
    val modelId = m.string("modelId") ?: ""
    // An ID reused for a different model must not inherit the former model's local capabilities.
    val previous = existing[id]?.takeIf { it.modelId == modelId }
    val metadataText = if ("metadata" in m) m.field("metadata") else previous?.metadata
    fun parseMetadata(text: String?): JsonObject? = text?.let {
        kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject ?: error("Invalid model metadata")
    }
    val metadata = parseMetadata(metadataText)
    val overrides = parseMetadata(previous?.metadataOverrides)
    fun modalities(obj: JsonObject?): String? = (obj?.get("inputModalities") as? JsonArray)?.map {
        (it as? JsonPrimitive)?.contentOrNull ?: error("Invalid model modality")
    }?.joinToString(",")
    ModelRow(id, providerId, modelId, m.string("displayName") ?: "", m.bool("isEnabled") ?: false,
        overrides?.long("contextWindow") ?: m.long("contextWindow") ?: metadata?.long("contextWindow") ?: previous?.contextWindow ?: 0,
        m.double("inputRate"), m.double("outputRate"), m.double("cacheReadRate"), m.double("cacheWriteRate"),
        modalities(overrides) ?: modalities(metadata) ?: m.string("inputModalities") ?: previous?.inputModalities ?: "text",
        m.string("outputModalities") ?: previous?.outputModalities ?: "text", m.bool("supportsToolCalling") ?: previous?.supportsToolCalling ?: false,
        overrides?.bool("reasoning") ?: metadata?.bool("reasoning") ?: m.bool("supportsThinking") ?: previous?.supportsThinking ?: false,
        m.bool("supportsJsonOutput") ?: previous?.supportsJsonOutput ?: false,
        overrides?.bool("supportsTemperature") ?: metadata?.bool("supportsTemperature") ?: m.bool("supportsTemperature") ?: previous?.supportsTemperature ?: false,
        m.long("createdAt") ?: 0, metadataText, previous?.metadataOverrides)
}
internal fun JsonObject.projectRow(old: ProjectRow?): ProjectRow = ProjectRow(
    syncId(), string("name") ?: "", string("workspace") ?: "", string("deviceId") ?: old?.deviceId,
    long("createdAt") ?: 0, long("updatedAt") ?: 0,
)
internal fun JsonObject.conversationRow(): ConversationRow {
    val messages = messageRows()
    return ConversationRow(syncId(), string("title") ?: "", string("providerId") ?: "", string("agentId") ?: "", string("projectId"),
        string("overrideModelId"), double("overrideTemperature"), double("overrideTopP"), long("overrideMaxTokens"), string("overrideReasoningEffort"),
        bool("overrideToolsEnabled"), field("overrideToolsConfig"), bool("writable") ?: false, long("createdAt") ?: 0, long("updatedAt") ?: 0,
        messages.lastOrNull()?.content, string("reasoningFormat"), string("contextSummary"), long("contextSummaryUntil") ?: 0,
        long("contextTokens") ?: 0, long("contextTokensAt") ?: 0)
}
internal fun JsonObject.messageRows(): List<MessageRow> = array("messages").map { m ->
    MessageRow(m.string("id")?.takeIf { it.isNotBlank() } ?: error("Cloud message is missing id"), syncId(), m.string("role") ?: "", m.string("content") ?: "",
        m.string("partsJson"), m.long("timestamp") ?: 0, m.string("status")?.lowercase() ?: "", m.string("errorMessage"))
}

private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull
private fun nullable(value: Long?): JsonElement = value?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull
private fun nullable(value: Double?): JsonElement = value?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull
private fun nullable(value: Boolean?): JsonElement = value?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull
private fun config(value: String): JsonObject = if (value.isBlank()) JsonObject(emptyMap()) else
    kotlinx.serialization.json.Json.parseToJsonElement(value) as? JsonObject ?: error("Invalid local tools configuration")

internal fun AgentRow.cloudRequest(): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("id", JsonPrimitive(id)); put("name", JsonPrimitive(name)); put("avatarUrl", kotlinx.serialization.json.JsonNull)
    put("systemPrompt", JsonPrimitive(systemPrompt)); put("description", JsonPrimitive(description)); put("defaultModelId", nullable(defaultModelId))
    put("temperature", nullable(temperature)); put("topP", nullable(topP)); put("maxTokens", nullable(maxTokens))
    put("reasoningEffort", nullable(reasoningEffort)); put("isDefault", JsonPrimitive(isDefault)); put("followDefaultSystemPrompt", JsonPrimitive(followDefaultSystemPrompt))
    put("followDefaultModel", JsonPrimitive(followDefaultModel)); put("followDefaultTemperature", JsonPrimitive(followDefaultTemperature)); put("followDefaultTopP", JsonPrimitive(followDefaultTopP))
    put("followDefaultMaxTokens", JsonPrimitive(followDefaultMaxTokens)); put("followDefaultReasoningEffort", JsonPrimitive(followDefaultReasoningEffort))
    put("marketAgentId", nullable(marketAgentId)); put("marketAgentVersion", nullable(marketAgentVersion)); put("marketAgentRole", nullable(marketAgentRole))
    put("role", JsonPrimitive(role)); put("toolsEnabled", JsonPrimitive(toolsEnabled)); put("toolsFollowDefault", JsonPrimitive(toolsFollowDefault))
    put("toolsConfig", config(toolsConfig)); put("createdAt", JsonPrimitive(createdAt)); put("updatedAt", JsonPrimitive(updatedAt))
}
internal fun ProviderRow.cloudRequest(models: List<ModelRow>): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("id", JsonPrimitive(id)); put("name", JsonPrimitive(name)); put("baseUrl", JsonPrimitive(baseUrl)); put("apiKey", JsonPrimitive(apiKey))
    put("apiFormat", JsonPrimitive(apiFormat)); put("createdAt", JsonPrimitive(createdAt)); put("updatedAt", JsonPrimitive(updatedAt))
    put("models", JsonArray(models.map { model -> kotlinx.serialization.json.buildJsonObject {
        put("id", JsonPrimitive(model.id)); put("modelId", JsonPrimitive(model.modelId)); put("displayName", JsonPrimitive(model.displayName))
        put("isEnabled", JsonPrimitive(model.isEnabled)); put("contextWindow", JsonPrimitive(model.contextWindow))
        put("inputRate", nullable(model.inputRate)); put("outputRate", nullable(model.outputRate)); put("cacheReadRate", nullable(model.cacheReadRate)); put("cacheWriteRate", nullable(model.cacheWriteRate))
        model.metadata?.let { put("metadata", config(it)) }
        put("createdAt", JsonPrimitive(model.createdAt))
    } }))
}
internal fun ProjectRow.cloudRequest(): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("id", JsonPrimitive(id)); put("name", JsonPrimitive(name)); put("workspace", JsonPrimitive(workspace)); put("deviceId", nullable(deviceId))
    put("createdAt", JsonPrimitive(createdAt)); put("updatedAt", JsonPrimitive(updatedAt))
}
internal fun ConversationRow.cloudRequest(messages: List<MessageRow>): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("id", JsonPrimitive(id)); put("title", JsonPrimitive(title)); put("agentId", JsonPrimitive(agentId)); put("providerId", JsonPrimitive(providerId)); put("projectId", nullable(projectId))
    put("overrideModelId", nullable(overrideModelId)); put("overrideTemperature", nullable(overrideTemperature)); put("overrideTopP", nullable(overrideTopP)); put("overrideMaxTokens", nullable(overrideMaxTokens))
    put("overrideReasoningEffort", nullable(overrideReasoningEffort)); put("overrideToolsEnabled", nullable(overrideToolsEnabled))
    put("overrideToolsConfig", overrideToolsConfig?.let(::config) ?: kotlinx.serialization.json.JsonNull); put("writable", JsonPrimitive(writable))
    put("reasoningFormat", nullable(reasoningFormat)); put("contextSummary", nullable(contextSummary)); put("contextSummaryUntil", JsonPrimitive(contextSummaryUntil))
    put("contextTokens", JsonPrimitive(contextTokens)); put("contextTokensAt", JsonPrimitive(contextTokensAt)); put("createdAt", JsonPrimitive(createdAt)); put("updatedAt", JsonPrimitive(updatedAt))
    put("messages", JsonArray(messages.map { message -> kotlinx.serialization.json.buildJsonObject {
        put("id", JsonPrimitive(message.id)); put("role", JsonPrimitive(message.role)); put("content", JsonPrimitive(message.content)); put("partsJson", nullable(message.partsJson))
        put("timestamp", JsonPrimitive(message.timestamp)); put("status", JsonPrimitive(message.status)); put("errorMessage", nullable(message.errorMessage))
    } }))
}

/** Maps Messenger's /v1/models extensions without inferring omitted capabilities. */
internal fun JsonObject.builtinModelRow(providerId: String, old: Map<String, ModelRow>, now: Long): ModelRow {
    val modelId = string("id")?.takeIf { it.isNotBlank() } ?: error("Built-in model is missing id")
    val previous = old[modelId]
    val metadata = kotlinx.serialization.json.buildJsonObject {
        for ((source, destination) in mapOf("display_name" to "displayName", "input_modalities" to "inputModalities",
            "reasoning" to "reasoning", "reasoning_efforts" to "reasoningEfforts", "default_reasoning_effort" to "defaultReasoningEffort",
            "context_window" to "contextWindow", "max_output_tokens" to "maxOutputTokens", "supports_temperature" to "supportsTemperature",
            "supports_top_p" to "supportsTopP", "supports_penalties" to "supportsPenalties")) {
            this@builtinModelRow[source]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.let { put(destination, it) }
        }
    }
    val input = (this["input_modalities"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: error("Invalid input modality") }?.joinToString(",") ?: "text"
    return ModelRow(previous?.id ?: "$providerId:$modelId", providerId, modelId, string("display_name") ?: modelId,
        previous?.isEnabled ?: true, (long("context_window") ?: 0).coerceAtLeast(0), double("input_rate"), double("output_rate"),
        double("cache_read_rate"), double("cache_write_rate"), input, "text", false, bool("reasoning") ?: false, false,
        bool("supports_temperature") ?: false, previous?.createdAt ?: now, metadata.toString(), "{}")
}
