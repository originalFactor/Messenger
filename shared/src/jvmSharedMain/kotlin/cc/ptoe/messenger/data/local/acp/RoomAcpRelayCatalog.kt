/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.LocalDatabase

/** Resolve the current provider and enabled state at dispatch; credentials never enter the public catalog. */
internal class RoomAcpRelayCatalog(private val db: LocalDatabase) : AcpRelayCatalog {
    override suspend fun find(id: String): AcpRelayModel? = db.useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val row = db.rows().model(id) ?: return@withTransaction null
            val provider = db.rows().provider(row.providerId) ?: return@withTransaction null
            val format = when (provider.apiFormat) {
                "auto", "chat" -> AcpRelayFormat.CHAT
                "responses" -> AcpRelayFormat.RESPONSES
                "messages" -> AcpRelayFormat.MESSAGES
                "interactions" -> AcpRelayFormat.INTERACTIONS
                else -> return@withTransaction null
            }
            AcpRelayModel(row.id, row.displayName, provider.id, row.createdAt, row.isEnabled, row.modelId, provider.baseUrl, provider.apiKey, format)
        }
    }
}
