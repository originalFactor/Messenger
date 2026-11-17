/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal const val ACTION_OPEN_CONVERSATION = "cc.ptoe.messenger.OPEN_PUSH"

/** A notification only selects a conversation; it never grants access or executes a command. */
internal data class ConversationNotificationTarget(
    val kind: String,
    val conversationId: String,
    val deviceId: String? = null,
)

internal fun conversationNotificationTarget(kind: String?, conversationId: String?, deviceId: String?): ConversationNotificationTarget? {
    val id = conversationId?.takeIf { it.length in 1..200 && it.all { c -> c.isAsciiIdentifierChar() || c == ':' } } ?: return null
    return when (kind) {
        "local" -> if (deviceId.isNullOrEmpty()) ConversationNotificationTarget(kind, id) else null
        "remote" -> deviceId?.takeIf { it.length in 1..64 && it.all { c -> c.isAsciiIdentifierChar() } }
            ?.let { ConversationNotificationTarget(kind, id, it) }
        else -> null
    }
}

/** JPush/FCM may wrap Android extras in `JMessageExtra.n_extras` as JSON text or an object. */
internal fun notificationTargetFromExtras(
    kind: String?, conversationId: String?, deviceId: String?,
    nExtras: String?, jMessageExtra: String?,
): ConversationNotificationTarget? {
    conversationNotificationTarget(kind, conversationId, deviceId)?.let { return it }
    fun parse(value: String?): JsonObject? {
        if (value == null || value.length > 16_384) return null
        return runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
    }
    fun fromObject(obj: JsonObject?): ConversationNotificationTarget? {
        if (obj == null) return null
        fun field(name: String): String? = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        return conversationNotificationTarget(field("kind"), field("conversationId"), field("deviceId"))
    }
    fromObject(parse(nExtras))?.let { return it }
    val envelope = parse(jMessageExtra) ?: return null
    val nested = when (val value = envelope["n_extras"]) {
        is JsonObject -> value
        is JsonPrimitive -> if (value.isString) parse(value.contentOrNull) else null
        else -> null
    }
    return fromObject(nested) ?: fromObject(envelope)
}

private fun Char.isAsciiIdentifierChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '-' || this == '_'
