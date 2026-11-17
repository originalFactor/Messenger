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

internal fun roomAcpSettings(settings: JsonObject, modeWritable: Boolean = false, modePending: Boolean = false,
    modeInspected: Boolean = false, modePendingId: String? = null, modeConfigId: String? = null,
    writableConfigIds: List<String> = emptyList()): JsonObject = JsonObject(settings + ("_messengerCapabilities" to buildJsonObject {
    put("settingsWritable", false); put("steering", false); put("modeWritable", modeWritable); put("modePending", modePending)
    put("writableConfigIds", JsonArray(writableConfigIds.map(::JsonPrimitive)))
    put("modeInspected", modeInspected); if (modePendingId != null) put("modePendingId", modePendingId)
    if (modeConfigId != null) put("modeConfigId", modeConfigId)
}))

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
        } else if (action == "sessionSettings" && session?.startsWith("acp:") == true) {
            val settings = owner.sessionSettings(session)
            onEvent(buildJsonObject { put("type", "SessionSettings"); put("settings", settings) })
        } else if (action == "setSessionConfig" && session?.startsWith("acp:") == true) {
            val mode = (command["modeId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val config = (command["configId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val value = (command["value"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val deliver: (JsonObject) -> Unit = { settings -> onEvent(buildJsonObject { put("type", "SessionSettings"); put("settings", settings) }) }
            if (mode != null && command["configId"] == null && command["value"] == null) owner.setMode(session, mode, deliver)
            else if (config != null && value != null && command["modeId"] == null) owner.setModeOption(session, config, value, deliver)
            else error("Only ACP mode changes are available")
        } else if (action == "inspectPendingMode" && session?.startsWith("acp:") == true) {
            owner.inspectPendingMode(session) { settings -> onEvent(buildJsonObject { put("type", "SessionSettings"); put("settings", settings) }) }
        } else if (action == "confirmPendingMode" && session?.startsWith("acp:") == true) {
            val marker = (command["pendingId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Pending mode identity is required")
            val mode = (command["modeId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Current mode is required")
            owner.confirmPendingMode(session, marker, mode) { settings -> onEvent(buildJsonObject { put("type", "SessionSettings"); put("settings", settings) }) }
        } else if (action == "steer" && session?.startsWith("acp:") == true) {
            error("This ACP operation is unavailable")
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
