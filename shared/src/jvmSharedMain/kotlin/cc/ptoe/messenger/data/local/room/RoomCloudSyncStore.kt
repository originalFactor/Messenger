/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import cc.ptoe.messenger.data.cloud.CloudImageRehydrator
import cc.ptoe.messenger.data.cloud.KotlinSyncStore
import cc.ptoe.messenger.data.cloud.PendingSyncChange
import cc.ptoe.messenger.data.cloud.PulledCloudDelta
import cc.ptoe.messenger.data.cloud.SyncSession

/**
 * The expiration callback must atomically compare the identity, clear that session's credentials
 * and local cloud state, and publish session expiry. It must not clear a newer login.
 */
internal class RoomCloudSyncStore(
    private val dao: CloudSyncDao,
    private val expireSession: suspend (SyncSession) -> Unit,
    private val images: CloudImageRehydrator,
    private val replacementToken: String? = null,
) : KotlinSyncStore {
    override suspend fun session(): SyncSession = checkNotNull(dao.currentSession()) { "Please sign in first" }
    override suspend fun cursor(session: SyncSession) = dao.cursor(session, replacementToken)
    override suspend fun pending(session: SyncSession) = dao.pendingSnapshots(session, replacementToken)
    override suspend fun acknowledgePush(session: SyncSession, change: PendingSyncChange) = dao.acknowledge(session, change, replacementToken)
    override suspend fun commitPull(session: SyncSession, delta: PulledCloudDelta, replaceLocal: Boolean) =
        dao.applyPull(session, delta, replaceLocal, images, replacementToken = replacementToken)
    override suspend fun expireIfCurrent(session: SyncSession) = expireSession(session)
}
