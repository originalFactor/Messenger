/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.core.CoreBridge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Existing UI polling surface backed by the isolated ACP owner; other commands delegate. */
internal class KotlinAcpBackgroundBridge(
    private val delegate: CoreBridge,
    private val owner: RoomAcpBackgroundOwner,
    private val newSession: AcpNewSessionSource,
) : CoreBridge by delegate {
    override suspend fun startBackgroundAcp(requestId: String, command: JsonObject) {
        require((command["action"] as? JsonPrimitive)?.contentOrNull == "prompt") { "Unsupported background ACP action" }
        val session = (command["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: error("ACP session is required")
        val content = command["content"] as? JsonArray ?: error("ACP prompt content is required")
        owner.start(requestId, session, content)
    }

    override suspend fun backgroundAcpBySession(sessionId: String): JsonObject? = owner.bySession(sessionId)
    override suspend fun backgroundAcpEvents(requestId: String, after: Long): JsonObject = owner.events(requestId, after)
    override suspend fun localAcpHistory(sessionId: String, before: String?): JsonObject = owner.history(sessionId, before)
    override suspend fun controlCommand(requestId: String, command: JsonObject, onEvent: (JsonObject) -> Unit) {
        val action = (command["action"] as? JsonPrimitive)?.contentOrNull
        val session = (command["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val agentId = (command["agentId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (action == "catalog") {
            onEvent(owner.catalog())
        } else if (action == "create" && agentId?.startsWith("acp:") == true) {
            val projectId = (command["projectId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: error("ACP conversations require a project")
            val created = owner.createFrom(agentId.removePrefix("acp:"), projectId, newSession)
            onEvent(buildJsonObject { put("type", "Created"); put("sessionId", created.conversationId) })
        } else if (action == "history" && session?.startsWith("acp:") == true) {
            val before = when (val value = command["before"]) {
                null, JsonNull -> null
                is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull ?: error("Invalid ACP history cursor")
                else -> error("Invalid ACP history cursor")
            }
            onEvent(owner.history(session, before))
        } else delegate.controlCommand(requestId, command, onEvent)
    }
    override suspend fun hasActiveTasks(): Boolean = owner.hasActiveTasks() || delegate.hasActiveTasks()
    override suspend fun controlCancel(requestId: String) {
        if (owner.owns(requestId)) check(owner.cancel(requestId)) { "ACP request is no longer active" }
        else delegate.controlCancel(requestId)
    }
    override suspend fun controlPermission(requestId: String, optionId: String) {
        if (owner.owns(requestId)) check(owner.decide(requestId, optionId)) { "ACP permission is no longer active" }
        else delegate.controlPermission(requestId, optionId)
    }
}
