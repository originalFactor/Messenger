/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.local.room.LocalRowsDao
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private fun denied(): Nothing = error("Remote control authorization is no longer valid")
private fun parse(raw: String) = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
private fun text(value: kotlinx.serialization.json.JsonElement?) = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun normalize(value: String?) = value?.let { runCatching { canonicalCloudBase(it) }.getOrNull() }
internal const val REMOTE_CONTROL_EPOCH_KEY = "kotlin_device_control_grant_epoch_v1"

/** The account-scoped workspace relay does not require remote-control opt-in. */
internal suspend fun LocalRowsDao.requireCloudSession(scope: KotlinRemoteControlScope) {
    val host = normalize(scope.baseUrl) ?: denied()
    if (scope.accountId.isBlank() || scope.generation.isBlank() || !scope.deviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) denied()
    val session = getValue("kotlin_sync_session")?.value?.let(::parse) as? JsonArray ?: denied()
    if (session.map(::text) != listOf(scope.accountId, scope.generation)) denied()
    val cookie = getValue("cloud_session")?.value ?: denied()
    if (getValue("device_id")?.value != scope.deviceId || !cookie.startsWith("messenger_session=") ||
        cookie.length <= "messenger_session=".length || cookie.any { it == ';' || it.isWhitespace() || it.isISOControl() }) denied()
    if (normalize(getValue("cloud_session_host")?.value) != host) denied()
    val user = getValue("cloud_user_json")?.value?.let(::parse) as? JsonObject ?: denied()
    if (text(user["id"]) != scope.accountId) denied()
}

/** Call inside the same Room transaction that reads or mutates the authorized target. */
internal suspend fun LocalRowsDao.requireRemoteControl(
    scope: KotlinRemoteControlScope,
    conversationId: String? = null,
    requireOwnedProject: Boolean = false,
) {
    requireCloudSession(scope)
    val host = normalize(scope.baseUrl) ?: denied()
    val epoch = getValue(REMOTE_CONTROL_EPOCH_KEY)?.value?.toLongOrNull()?.takeIf { it > 0 } ?: denied()
    if (scope.grantEpoch != epoch) denied()
    val grant = getValue("device_control_grant")?.value?.let(::parse) as? JsonArray ?: denied()
    if (grant.size != 2 || normalize(text(grant[0])) != host || text(grant[1]) != scope.accountId) denied()
    val settings = getValue("device_control_config")?.value?.let(::parse) as? JsonObject ?: denied()
    if ((settings["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != true) denied()
    if (requireOwnedProject) {
        val id = conversationId ?: denied()
        // ACP uses separate authorities and must never fall through to a native turn.
        if (id.startsWith("acp:")) denied()
        val conversation = conversation(id) ?: denied()
        val project = conversation.projectId?.let { project(it) } ?: denied()
        // Legacy Core treats an unscoped project as local; explicit foreign IDs are remote.
        if (project.deviceId != null && project.deviceId != scope.deviceId) denied()
    }
}
