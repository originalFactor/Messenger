/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Transactor
import androidx.room.useReaderConnection
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.LocalRowsDao
import cc.ptoe.messenger.data.local.room.MessageRow
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A credential-free projection; a host must provide snapshots for both native and ACP tasks. */
internal fun interface RoomRemoteTaskSnapshots {
    suspend fun snapshot(conversationId: String): RoomRemoteTaskSnapshot
}
internal data class RoomRemoteTaskSnapshot(val active: Boolean, val requestId: String? = null, val events: List<JsonObject> = emptyList())

/**
 * Remote read-only commands over the same Room owner as chat and Cloud. Each database
 * snapshot checks the account-scoped explicit control grant. Notification access is
 * restricted to a specific existing local session; catalog/history require a project
 * owned by this device. Mutating commands remain unavailable until they can recheck
 * grant, session, target and active-task identity in the same admission transaction.
 */
internal class RoomDeviceControlDispatcher(
    private val db: LocalDatabase,
    private val stored: StoredCloudSession,
    private val deviceId: String,
    private val tasks: RoomRemoteTaskSnapshots,
    private val operations: RoomRemoteTurnOperations? = null,
) : RoomDeviceCommandDispatcher {
    private class Failure(val code: String, override val message: String) : IllegalStateException(message)
    private data class SessionPage(val title: String, val writable: Boolean?, val messages: List<JsonObject>, val before: String?)
    internal val scope = KotlinRemoteControlScope(stored.session.accountId, stored.session.generation, stored.host, deviceId)

    init { require(deviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) }

    override suspend fun dispatch(grantEpoch: Long, command: JsonObject, permissions: ReceiveChannel<String>, update: suspend (JsonObject) -> Unit): JsonObject {
        val scope = scope.copy(grantEpoch = grantEpoch)
        return try {
            when (val action = command.requiredText("action")) {
                "catalog" -> { command.only("action"); transaction(scope) { catalog(it) } }
                "history", "notificationOpen" -> {
                    command.only("action", "sessionId", "before")
                    val id = command.requiredText("sessionId").validatedId()
                    val before = command.optionalText("before")?.validatedId()
                    val notification = action == "notificationOpen"
                    val page = transaction(scope) { page(it, id, before, notification) }
                    if (!notification) buildJsonObject {
                        put("type", "History"); put("messages", JsonArray(page.messages)); put("before", page.before?.let(::JsonPrimitive) ?: JsonNull)
                        put("writable", page.writable?.let(::JsonPrimitive) ?: JsonNull)
                    } else {
                        val snapshot = tasks.snapshot(id)
                        // The task snapshot may suspend; do not expose it after revocation or
                        // deletion while it was being collected.
                        transaction(scope) { rows -> requireSession(rows, id, requireOwnedProject = false) }
                        require(snapshot.events.size <= 16 && snapshot.events.sumOf { it.toString().encodeToByteArray().size } <= 128_000) { "Invalid task snapshot" }
                        require(!snapshot.active || !snapshot.requestId.isNullOrBlank()) { "Active task has no identity" }
                        snapshot.requestId?.validatedId()
                        buildJsonObject {
                            put("type", "Session"); put("title", shortText(page.title, 160)); put("writable", page.writable?.let(::JsonPrimitive) ?: JsonNull)
                            put("messages", JsonArray(page.messages)); put("before", page.before?.let(::JsonPrimitive) ?: JsonNull)
                            put("active", snapshot.active); put("requestId", snapshot.requestId?.let(::JsonPrimitive) ?: JsonNull)
                            put("events", JsonArray(snapshot.events.mapNotNull(::sanitizeTaskEvent)))
                        }
                    }
                }
                "notificationDecision" -> {
                    command.only("action", "sessionId", "requestId", "optionId")
                    val id = command.requiredText("sessionId").validatedId()
                    val turnId = command.requiredText("requestId").validatedId()
                    val option = command.requiredText("optionId").validatedId()
                    transaction(scope) { rows -> requireSession(rows, id, requireOwnedProject = false) }
                    (operations ?: throw Failure("unavailable", "Remote task decisions are unavailable")).decide(scope, id, turnId, option)
                    buildJsonObject { put("type", "DecisionAccepted") }
                }
                "notificationCancel" -> {
                    command.only("action", "sessionId", "requestId")
                    val id = command.requiredText("sessionId").validatedId()
                    val turnId = command.requiredText("requestId").validatedId()
                    transaction(scope) { rows -> requireSession(rows, id, requireOwnedProject = false) }
                    (operations ?: throw Failure("unavailable", "Remote task cancellation is unavailable")).cancel(scope, id, turnId)
                    buildJsonObject { put("type", "CancelAccepted") }
                }
                "prompt" -> {
                    command.only("action", "sessionId", "text", "content")
                    val id = command.requiredText("sessionId").validatedId()
                    val text = command.requiredText("text")
                    require(text.isNotBlank() && text.encodeToByteArray().size <= 128_000) { "Invalid prompt" }
                    if (id.startsWith("acp:")) throw Failure("unavailable", "Remote ACP prompts are unavailable")
                    val content = command["content"]
                    require(content == null || content is JsonArray) { "Invalid prompt content" }
                    val parts = (content as? JsonArray).orEmpty()
                    if (parts.any { (it as? JsonObject)?.get("type").stringOrNull() != "text" })
                        throw Failure("unavailable", "Remote prompt attachments are unavailable")
                    if (parts.isNotEmpty() && parts.joinToString("") { (it as JsonObject)["text"].stringOrNull().orEmpty() } != text)
                        throw Failure("bad_request", "Prompt text and content differ")
                    transaction(scope) { rows -> requireSession(rows, id, requireOwnedProject = true) }
                    (operations ?: throw Failure("unavailable", "Remote prompts are unavailable")).prompt(scope, id, text, permissions, update)
                }
                "create", "setMode", "steer", "sessionSettings", "setSessionConfig", "store" ->
                    throw Failure("unavailable", "Remote command is not yet available")
                else -> throw Failure("bad_request", "Unknown remote command")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Failure) { buildJsonObject { put("type", "Error"); put("code", failure.code); put("message", failure.message) } }
        catch (_: Exception) { buildJsonObject { put("type", "Error"); put("code", "device_error"); put("message", "Remote command failed") } }
    }

    private suspend fun <T> transaction(scope: KotlinRemoteControlScope, block: suspend (LocalRowsDao) -> T): T =
        db.useReaderConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            val rows = db.rows()
            authorize(rows, scope)
            block(rows)
        } }

    private suspend fun authorize(rows: LocalRowsDao, scope: KotlinRemoteControlScope) {
        try {
            rows.requireRemoteControl(scope)
            check(rows.getValue("cloud_session")?.value == stored.cookie)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw Failure("control_disabled", "Remote control is disabled") }
    }

    private suspend fun catalog(rows: LocalRowsDao): JsonObject {
        val agents = rows.controlAgents().map { agent -> buildJsonObject { put("id", agent.id); put("name", shortText(agent.name, 160)); put("kind", "messenger") } }
        val settings = parseObject(requireNotNull(rows.getValue("device_control_config")).value)
        val profiles = (settings["agents"] as? JsonArray).orEmpty().mapNotNull { value ->
            val profile = value as? JsonObject ?: return@mapNotNull null
            val id = profile["id"].stringOrNull()?.takeIf { it.isNotBlank() && it.length <= 80 } ?: return@mapNotNull null
            val enabled = when (val setting = profile["enabled"]) {
                null -> true
                is JsonPrimitive -> !setting.isString && setting.booleanOrNull == true
                else -> false
            }
            if (!enabled) return@mapNotNull null
            buildJsonObject {
                put("id", "acp:$id"); put("name", shortText(profile["name"].stringOrNull().orEmpty(), 160))
                put("description", shortText(profile["description"].stringOrNull().orEmpty(), 512)); put("avatar", JsonNull); put("kind", "acp")
            }
        }
        val projects = rows.controlProjects(deviceId).map { project -> buildJsonObject { put("id", project.id); put("name", shortText(project.name, 160)) } }
        val native = rows.controlConversations(deviceId).map { conversation -> buildJsonObject {
            put("id", conversation.id); put("title", shortText(conversation.title, 160)); put("kind", "messenger")
            put("projectId", conversation.projectId); put("updatedAt", conversation.updatedAt)
        } }
        val external = acpSessions(rows).mapNotNull { (id, record) ->
            val project = record["project"].stringOrNull() ?: return@mapNotNull null
            if (!ownsProject(rows, project)) return@mapNotNull null
            val updated = (record["updated_at"] as? JsonPrimitive)?.longOrNull ?: 0L
            updated to buildJsonObject {
                put("id", id); put("title", shortText(record["title"].stringOrNull().orEmpty(), 160)); put("kind", "acp")
                put("projectId", project); put("updatedAt", updated)
            }
        }.sortedByDescending { it.first }.take(100).map { it.second }
        return buildJsonObject {
            put("type", "Catalog"); put("agents", JsonArray((agents + profiles).take(100)))
            put("projects", JsonArray(projects)); put("sessions", JsonArray(native + external))
        }
    }

    private suspend fun page(rows: LocalRowsDao, id: String, before: String?, notification: Boolean): SessionPage {
        val record = requireSession(rows, id, requireOwnedProject = !notification)
        val candidates: List<JsonObject>
        val hasExtra: Boolean
        if (id.startsWith("acp:")) {
            val all = (requireNotNull(record.acp)["messages"] as? JsonArray).orEmpty().map { it as? JsonObject ?: throw Failure("device_error", "Invalid session history") }
            val position = before?.let { anchor -> all.indexOfFirst { it["id"].stringOrNull() == anchor }.takeIf { it >= 0 } } ?: all.size
            val chosen = all.subList(maxOf(0, position - 21), position)
            hasExtra = chosen.size > 20
            candidates = chosen.takeLast(20).map { sanitizeAcp(it, notification) }
        } else {
            val validAnchor = before?.let { rows.message(it)?.conversationId == id } == true
            val reversed = if (validAnchor) rows.messagesBefore(id, requireNotNull(before), 21) else rows.recentMessages(id, 21)
            hasExtra = reversed.size > 20
            candidates = reversed.take(20).asReversed().map { sanitizeNative(it, notification) }
        }
        val maxBytes = if (notification) 120_000 else 235_000
        var size = 0
        val selected = mutableListOf<JsonObject>()
        for (row in candidates.asReversed()) {
            val bytes = row.toString().encodeToByteArray().size
            if (size + bytes > maxBytes) break
            size += bytes; selected.add(row)
        }
        selected.reverse()
        val more = hasExtra || selected.size < candidates.size
        return SessionPage(record.title, record.writable, selected, if (more) selected.firstOrNull()?.get("id").stringOrNull() else null)
    }

    private data class LocalSession(val title: String, val writable: Boolean?, val acp: JsonObject?)
    private suspend fun requireSession(rows: LocalRowsDao, id: String, requireOwnedProject: Boolean): LocalSession {
        if (id.startsWith("acp:")) {
            val record = acpSessions(rows)[id] ?: throw Failure("not_found", "Session not found")
            if (requireOwnedProject && !ownsProject(rows, record["project"].stringOrNull())) throw Failure("project_unavailable", "Project is not owned by this device")
            return LocalSession(record["title"].stringOrNull().orEmpty(), null, record)
        }
        val row = rows.conversation(id) ?: throw Failure("not_found", "Session not found")
        if (requireOwnedProject && !ownsProject(rows, row.projectId)) throw Failure("project_unavailable", "Project is not owned by this device")
        return LocalSession(row.title, row.writable, null)
    }

    private suspend fun ownsProject(rows: LocalRowsDao, id: String?): Boolean {
        val project = id?.let { rows.project(it) } ?: return false
        return project.deviceId == null || project.deviceId == deviceId
    }
    private suspend fun acpSessions(rows: LocalRowsDao): Map<String, JsonObject> {
        val raw = rows.getValue("local_acp_sessions")?.value ?: return emptyMap()
        require(raw.encodeToByteArray().size <= 16 * 1024 * 1024) { "ACP session data is too large" }
        return parseObject(raw).mapValues { (id, value) ->
            val session = value as? JsonObject ?: error("Invalid ACP session")
            require(id.startsWith("acp:") && session["id"].stringOrNull() == id) { "Invalid ACP identity" }
            session
        }
    }

    private fun sanitizeNative(row: MessageRow, notification: Boolean): JsonObject {
        val basic = buildJsonObject {
            put("id", row.id); put("role", row.role); put("text", shortText(row.errorMessage ?: row.content, if (notification) 12_000 else 36_000)); put("status", row.status)
        }
        if (notification || row.partsJson == null || row.partsJson.encodeToByteArray().size > 100_000) return basic
        val withParts = JsonObject(basic + ("partsJson" to JsonPrimitive(row.partsJson)))
        return if (withParts.toString().encodeToByteArray().size <= 220_000) withParts else basic
    }
    private fun sanitizeAcp(row: JsonObject, notification: Boolean): JsonObject {
        require(!row["id"].stringOrNull().isNullOrBlank()) { "ACP message has no identity" }
        val text = shortText(row["text"].stringOrNull().orEmpty(), if (notification) 12_000 else 36_000)
        val hasExtraContent = (row["content"] as? JsonArray).orEmpty().any {
            (it as? JsonObject)?.get("type").stringOrNull() != "text"
        }
        val hasExtraEvents = (row["events"] as? JsonArray).orEmpty().any {
            (it as? JsonObject)?.get("type").stringOrNull() != "TextDelta"
        }
        val result = buildMap {
            listOf("id", "role", "status").forEach { key -> row[key].stringOrNull()?.let { put(key, JsonPrimitive(it)) } }
            put("text", JsonPrimitive(text))
            if (!notification) {
                listOf("timestamp", "events", "content").forEach { key -> row[key]?.let { put(key, it) } }
            } else if (hasExtraContent || hasExtraEvents) {
                put("detailsOmitted", JsonPrimitive(true))
            }
        }
        val full = JsonObject(result)
        if (full.toString().encodeToByteArray().size <= 220_000) return full
        return JsonObject(result - "events" - "content" + mapOf("eventsTruncated" to JsonPrimitive(true), "detailsOmitted" to JsonPrimitive(true)))
    }

    private fun sanitizeTaskEvent(event: JsonObject): JsonObject? = when (event["type"].stringOrNull()) {
        "TextDelta" -> buildJsonObject { put("type", "TextDelta"); put("text", shortText(event["text"].stringOrNull().orEmpty(), 8_000)) }
        "SummaryFailed", "TitleFailed", "ApprovalFailed", "Error" -> buildJsonObject {
            put("type", requireNotNull(event["type"].stringOrNull()))
            put("message", shortText(event["message"].stringOrNull().orEmpty(), 8_000))
            event["code"].stringOrNull()?.let { put("code", shortText(it, 256)) }
            event["detail"].stringOrNull()?.let { put("detail", shortText(it, 8_000)) }
        }
        "Permission" -> {
            val tool = event["tool"] as? JsonObject ?: error("Invalid task permission")
            val options = (event["options"] as? JsonArray ?: error("Invalid task permission")).map { item ->
                val option = item as? JsonObject ?: error("Invalid task permission option")
                val id = option["id"].stringOrNull()?.validatedId() ?: error("Invalid task permission option")
                buildJsonObject { put("id", id); put("name", shortText(option["name"].stringOrNull().orEmpty(), 160)) }
            }
            require(options.size in 1..16)
            buildJsonObject {
                put("type", "Permission"); put("options", JsonArray(options))
                put("tool", buildJsonObject { put("title", shortText(tool["title"].stringOrNull().orEmpty(), 180)); put("detail", shortText(tool["detail"].stringOrNull().orEmpty(), 1_500)) })
            }
        }
        else -> null
    }
}

private fun shortText(text: String, max: Int): String {
    val count = text.codePointCount(0, text.length)
    return if (count <= max) text else text.substring(0, text.offsetByCodePoints(0, max)) + "…"
}
private fun parseObject(raw: String): JsonObject = Json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid object")
private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.requiredText(key: String): String = this[key].stringOrNull() ?: error("Invalid $key")
private fun JsonObject.optionalText(key: String): String? = when (val value = this[key]) {
    null, JsonNull -> null
    else -> value.stringOrNull() ?: error("Invalid $key")
}
private fun JsonObject.only(vararg keys: String) { require(this.keys.all { it in keys }) { "Unknown remote command field" } }
private fun String.validatedId(): String { require(isNotBlank() && length <= 256 && none(Char::isISOControl)); return this }
