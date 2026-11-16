/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.repository.LearningOwnership
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private const val LEARNING = "messenger_learning"
private val codec = Json { encodeDefaults = true; explicitNulls = false }
private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"action":{"type":"string","enum":["overview","list","get","upsert"]},"kind":{"type":"string","enum":["course","knowledgePoint","item","material","plan","task","reviewCard"]},"id":{"type":"string"},"query":{"type":"object","properties":{"courseId":{"type":"string"},"knowledgePointId":{"type":"string"},"tag":{"type":"string"},"search":{"type":"string"},"archived":{"type":"boolean"},"dueBefore":{"type":"integer"},"offset":{"type":"integer"},"limit":{"type":"integer"}},"additionalProperties":false},"record":{"type":"object"}},"required":["action"],"additionalProperties":false}""").jsonObject
internal val coreLearningSpec = ToolSpecDto("function", ToolSpecFunctionDto(LEARNING,
    "Query the device-local learning library or save editable learning drafts. Requires the learning capability switch. Do not submit review scores or claim mastery; source snapshots remain independent.", schema))

internal suspend fun coreLearningTool(db: LocalDatabase, conversationId: String, store: () -> RoomKotlinTurnStore,
    ownership: LearningOwnership, now: () -> Long): KotlinCoreToolAdapter {
    return KotlinCoreToolAdapter.create(db, conversationId, store, coreLearningSpec, "learning_tools_enabled") { turnId, call ->
        require(call.arguments.encodeToByteArray().size <= 1024 * 1024) { "Learning tool arguments exceed the limit" }
        val args = Json.parseToJsonElement(call.arguments) as? JsonObject ?: error("Invalid learning arguments")
        require(args.keys.all { it in setOf("action", "kind", "id", "query", "record") }) { "Unknown learning argument" }
        when (args.requiredString("action")) {
            "overview" -> store().executeRoomCoreTool(turnId, call) {
                ToolExecutionResult(codec.encodeToString(db.learningReview().overview(now())))
            }
            "list" -> store().executeRoomCoreTool(turnId, call) {
                val query = args["query"]?.let { it as? JsonObject ?: error("Invalid learning query") } ?: JsonObject(emptyMap())
                require(query.keys.all { it in setOf("courseId", "knowledgePointId", "tag", "search", "archived", "dueBefore", "offset", "limit") }) { "Unknown learning query" }
                val page = db.learningReview().listRecords(args.requiredString("kind"), query.optionalString("courseId"),
                    query.optionalString("search").orEmpty(), query.optionalInt("offset") ?: 0, query.optionalBoolean("archived"),
                    query.optionalString("knowledgePointId"), query.optionalString("tag").orEmpty(), query.optionalInt("limit") ?: 50,
                    query.optionalLong("dueBefore"))
                val output = buildJsonObject {
                    put("items", buildJsonArray { page.items.forEach { item -> add(buildJsonObject {
                        put("id", item.id); put("version", item.version); put("title", item.title); put("kind", item.kind)
                        put("courseId", item.courseId?.let(::JsonPrimitive) ?: JsonNull); put("tags", JsonArray(item.tags.map(::JsonPrimitive))); put("archived", item.archived)
                    }) } })
                    put("total", page.total); put("offset", page.offset); put("limit", page.limit)
                }
                ToolExecutionResult(output.toString())
            }
            "get" -> store().executeRoomCoreTool(turnId, call) {
                val record = requireNotNull(db.learningReview().record(args.requiredString("id"))) { "Learning record not found" }
                require(record.kind == args.requiredString("kind")) { "Learning record not found" }
                ToolExecutionResult(codec.encodeToString(record), structuredContent = reference(record))
            }
            "upsert" -> {
                val record = codec.decodeFromJsonElement<LearningRecord>(requireNotNull(args["record"]) { "Learning record required" })
                require(args.optionalString("kind")?.let { it == record.kind } != false && args.optionalString("id")?.let { it == record.id } != false) { "Learning id or kind does not match record" }
                val saved = ownership.save(record, now()) { store().claimCoreToolInTransaction(turnId, call) }
                val output = buildJsonObject { put("id", saved.id); put("version", saved.version); put("title", saved.title); put("content", buildJsonObject { put("kind", saved.kind) }) }
                ToolExecutionResult(output.toString(), structuredContent = reference(saved))
            }
            else -> error("Unsupported learning action; review scoring requires user feedback")
        }
    }
}

private fun reference(record: LearningRecord) = buildJsonObject {
    put("version", 1); put("type", "learningRecord"); put("id", record.id); put("kind", record.kind); put("recordVersion", record.version)
}
internal fun JsonObject.optionalString(key: String): String? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeIf { it.isString }?.content ?: error("$key must be a string")
    else -> error("$key must be a string")
}
internal fun JsonObject.requiredString(key: String): String = optionalString(key)?.takeIf { it.isNotBlank() } ?: error("$key is required")
internal fun JsonObject.optionalLong(key: String): Long? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeUnless { it.isString }?.longOrNull ?: error("$key must be an integer")
    else -> error("$key must be an integer")
}
internal fun JsonObject.optionalInt(key: String): Int? = optionalLong(key)?.let { require(it in Int.MIN_VALUE..Int.MAX_VALUE); it.toInt() }
internal fun JsonObject.optionalBoolean(key: String): Boolean? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.takeUnless { it.isString }?.booleanOrNull ?: error("$key must be boolean")
    else -> error("$key must be boolean")
}
