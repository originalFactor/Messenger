/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Transactor
import androidx.room.useReaderConnection
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope

/** The server scopes the relay to an account but does not authenticate the claimed fromDeviceId. */
internal data class RoomInboundWorkspaceCall(
    val callId: String,
    val fromDeviceId: String,
    val targetDeviceId: String,
    val projectId: String,
    val conversationId: String,
    val name: String,
    val argumentsJson: String,
)

/** Captured owner data is advisory until revalidated under the executor's writer fence. */
internal data class RoomInboundWorkspaceGrant(val scope: KotlinRemoteControlScope, val call: RoomInboundWorkspaceCall, val workspace: String)

/**
 * Validates the persisted session, project and conversation for an inbound workspace call.
 * This is a read snapshot, not a lock or permission to execute: the host must revalidate
 * it under a shared gate that also excludes ownership/mode/session writes through the
 * external operation and must prove the operation stopped before releasing that gate.
 */
internal class RoomWorkspaceRelayAuthority(private val db: LocalDatabase) {
    suspend fun prepare(scope: KotlinRemoteControlScope, call: RoomInboundWorkspaceCall): RoomInboundWorkspaceGrant {
        require(call.callId.isNotBlank() && call.callId.length <= 128 && call.callId.none(Char::isISOControl)) { "Invalid tool call identity" }
        val device = Regex("[A-Za-z0-9_-]{1,64}")
        val entity = Regex("[A-Za-z0-9_-]{1,200}")
        require(call.fromDeviceId.matches(device) && call.targetDeviceId == scope.deviceId && call.projectId.matches(entity) &&
            call.conversationId.matches(entity) && call.argumentsJson.length <= 1_000_000) { "Invalid workspace relay call" }
        require(call.name in setOf("terminal", "glob", "grep", "read", "edit", "create")) { "Unknown workspace tool" }
        return db.useReaderConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
            val rows = db.rows()
            rows.requireCloudSession(scope)
            val project = rows.project(call.projectId) ?: error("Project not found on this device")
            check(project.deviceId == scope.deviceId) { "Project is not owned by this device" }
            check(project.workspace.isNotBlank() && project.workspace.length <= 2_000) { "Project workspace is unavailable" }
            // Terminal can write even in a nominally read-only mode; require writable
            // for it as well as for explicit editing tools.
            if (call.name in setOf("terminal", "edit", "create")) {
                val conversation = rows.conversation(call.conversationId) ?: error("Conversation not found on this device")
                check(conversation.projectId == project.id && conversation.writable) { "Workspace write requires a writable conversation of this project" }
            }
            RoomInboundWorkspaceGrant(scope, call, project.workspace)
        } }
    }
}
