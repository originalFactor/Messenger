/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.json.*

/** Confirms persisted ACP identity and local project ownership before startup and every prompt. */
internal class RoomAcpWorkspaceAuthority(private val db: LocalDatabase) : AcpWorkspaceAuthority {
    override suspend fun requireLocalUnchanged(binding: AcpWorkerBinding) {
        db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = db.rows()
            val sessions = rows.getValue("local_acp_sessions")?.value?.let { Json.parseToJsonElement(it) as? JsonObject }
                ?: error("ACP sessions are unavailable")
            val record = sessions[binding.conversationId] as? JsonObject ?: error("ACP session not found")
            fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            check(record.text("id") == binding.conversationId && record.text("profile") == binding.profileId &&
                record.text("project") == binding.projectId && record.text("agent_session") == binding.agentSessionId &&
                record.text("cwd") == binding.workspace) { "ACP session binding changed" }
            val delegated = record["delegated"]?.let {
                val flag = it as? JsonPrimitive ?: error("Invalid delegation flag")
                check(!flag.isString) { "Invalid delegation flag" }
                flag.booleanOrNull ?: error("Invalid delegation flag")
            } ?: false
            check(delegated == binding.delegated) { "ACP delegation scope changed" }
            val project = requireNotNull(rows.project(binding.projectId)) { "ACP project is unavailable" }
            val deviceId = rows.getValue("device_id")?.value
            check(!deviceId.isNullOrBlank() && project.deviceId == deviceId && project.workspace == binding.workspace) {
                "ACP project is not owned by this device or its workspace changed"
            }
            val path = Path.of(binding.workspace)
            check(path.isAbsolute && Files.isDirectory(path, NOFOLLOW_LINKS)) { "ACP workspace is unavailable" }
        } }
    }
}
