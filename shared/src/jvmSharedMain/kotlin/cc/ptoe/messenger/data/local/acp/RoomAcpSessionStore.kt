/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.repository.room.AcpSessionMutationGate
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.json.*

/** New ACP records use the same local KV shape as Core and never enter cloud sync. */
internal class RoomAcpSessionStore(private val db: LocalDatabase, private val gate: AcpSessionMutationGate) {
    suspend fun create(binding: AcpWorkerBinding, title: String, now: Long) = gate.withInactiveSession(binding.conversationId, false) {
        createUnderReservation(binding, title, now)
    }

    /** Caller already holds the manager's exclusive session reservation. */
    internal suspend fun createUnderReservation(binding: AcpWorkerBinding, title: String, now: Long) {
        require(binding.conversationId.startsWith("acp:") && binding.conversationId.length <= 256 &&
            binding.profileId.isNotBlank() && binding.profileId.length <= 80 && binding.agentSessionId.isNotBlank() &&
            binding.agentSessionId.length <= 1024 && binding.agentSessionId.none { it.isISOControl() } && binding.projectId.isNotBlank() && title.isNotBlank() && title.encodeToByteArray().size <= 512 && now >= 0) { "Invalid ACP session" }
        db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = db.rows()
            val project = requireNotNull(rows.project(binding.projectId)) { "ACP project is unavailable" }
            val device = rows.getValue("device_id")?.value
            check(!device.isNullOrBlank() && project.deviceId == device && project.workspace == binding.workspace) { "ACP project is not locally owned" }
            val path = Path.of(binding.workspace)
            check(path.isAbsolute && Files.isDirectory(path, NOFOLLOW_LINKS)) { "ACP workspace is unavailable" }
            val root = rows.getValue("local_acp_sessions")?.value?.let { Json.parseToJsonElement(it) as? JsonObject ?: error("Invalid ACP sessions") } ?: JsonObject(emptyMap())
            check(binding.conversationId !in root) { "ACP session already exists" }
            val record = buildJsonObject {
                put("id", binding.conversationId); put("title", title); put("profile", binding.profileId); put("project", binding.projectId)
                put("updated_at", now); put("cwd", binding.workspace); put("agent_session", binding.agentSessionId)
                put("delegated", binding.delegated); put("messages", JsonArray(emptyList()))
            }
            rows.upsertValues(listOf(KeyValueRow("local_acp_sessions", JsonObject(root + (binding.conversationId to record)).toString())))
        } }
    }
}
