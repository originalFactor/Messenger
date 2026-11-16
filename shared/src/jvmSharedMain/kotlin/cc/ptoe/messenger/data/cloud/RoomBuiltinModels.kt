/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.local.room.CloudSyncDao
import cc.ptoe.messenger.data.util.currentTimeMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Refresh the separately authenticated Cloud model catalog without retrying failed requests. */
internal class RoomBuiltinModels(private val dao: CloudSyncDao, private val api: RoomCloudApi, private val session: SyncSession) {
    private val mutex = Mutex()
    suspend fun sync(force: Boolean = false): Int = mutex.withLock {
        check(dao.currentSession() == session) { "Cloud session changed" }
        val provider = dao.providerRow(BUILTIN_PROVIDER_ID) ?: return@withLock 0
        val existing = dao.providerModels(BUILTIN_PROVIDER_ID)
        val now = currentTimeMillis()
        val previous = dao.value("last_builtin_models_sync_at")?.value?.toLongOrNull() ?: 0L
        if (!force && existing.isNotEmpty() && existing.all { it.metadata != null } && now >= previous && now - previous < 3_600_000) return@withLock existing.size
        val entries = api.builtinModels(provider.apiKey)
        dao.replaceBuiltinModels(session, provider, entries, now)
    }
}
