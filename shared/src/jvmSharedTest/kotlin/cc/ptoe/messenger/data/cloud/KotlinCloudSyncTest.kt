/* Copyright 2026 originalFactor. Licensed under Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinCloudSyncTest {
    private class Store : KotlinSyncStore {
        var identity = SyncSession("account", "one")
        var cursor = 7L
        var learning = "keep"
        var committed: PulledCloudDelta? = null
        var replaced = false
        var expired: SyncSession? = null
        val changes = mutableListOf<PendingSyncChange>()
        override suspend fun session() = identity
        override suspend fun cursor(session: SyncSession) = cursor
        override suspend fun pending(session: SyncSession) = changes.toList()
        override suspend fun acknowledgePush(session: SyncSession, change: PendingSyncChange) { changes.remove(change) }
        override suspend fun commitPull(session: SyncSession, delta: PulledCloudDelta, replaceLocal: Boolean) {
            check(identity == session)
            committed = delta; replaced = replaceLocal; cursor = delta.latestVersion
        }
        override suspend fun expireIfCurrent(session: SyncSession) { if (identity == session) expired = session }
    }
    private class Transport : KotlinSyncTransport {
        override val boundSession = SyncSession("account", "one")
        val calls = mutableListOf<Triple<SyncCollection, Long, Long?>>()
        val pushes = mutableListOf<PendingSyncChange>()
        var fail: Throwable? = null
        var afterPush: ((PendingSyncChange) -> Unit)? = null
        var latest = 12L
        var pageVersion: ((SyncCollection, String?) -> Long)? = null
        override suspend fun page(collection: SyncCollection, since: Long, ack: Long?, cursor: String?): SyncPage {
            fail?.let { throw it }
            calls += Triple(collection, since, ack)
            return SyncPage(emptyList(), pageVersion?.invoke(collection, cursor) ?: latest, null)
        }
        override suspend fun push(change: PendingSyncChange): Long {
            fail?.let { throw it }
            pushes += change
            afterPush?.invoke(change)
            return 10
        }
    }
    private val body = buildJsonObject { put("id", JsonPrimitive("1")) }

    @Test fun incrementalPullAcknowledgesOnlyDurableCursor(): Unit = runBlocking {
        val store = Store(); val remote = Transport()
        store.changes += PendingSyncChange(SyncCollection.CONVERSATION, "1", SyncMutation.UPSERT, 1, body)
        KotlinCloudSyncEngine(store, remote).sync(pushPending = true)
        assertTrue(remote.calls.all { it.second == 7L && it.third == 7L })
        assertEquals(12, store.cursor)
        assertTrue(store.changes.isEmpty())
    }
    @Test fun laterCollectionChangesDoNotAdvancePastEarlierSnapshot(): Unit = runBlocking {
        val store = Store(); val remote = Transport()
        remote.pageVersion = { collection, _ -> if (collection == SyncCollection.AGENT) 8 else 12 }
        val delta = KotlinCloudSyncEngine(store, remote).sync()
        assertEquals(8, delta.latestVersion)
        assertEquals(8, store.cursor)
        // A version 10 agent created after the first page will be requested next cycle.
        KotlinCloudSyncEngine(store, remote).sync()
        assertEquals(8, remote.calls.last().second)
    }
    @Test fun restoreFetchesAllBeforeAtomicReplacementAndKeepsLearning(): Unit = runBlocking {
        val store = Store(); val remote = Transport()
        KotlinCloudSyncEngine(store, remote).sync(replaceLocal = true)
        assertTrue(remote.calls.all { it.second == 0L && it.third == null })
        assertTrue(store.replaced)
        assertEquals("keep", store.learning)
    }
    @Test fun failedPushRetainsQueueAndFailedPullDoesNotCommit(): Unit = runBlocking {
        val store = Store(); val remote = Transport(); remote.fail = IllegalStateException("offline")
        store.changes += PendingSyncChange(SyncCollection.AGENT, "1", SyncMutation.DELETE, 1)
        assertFailsWith<IllegalStateException> { KotlinCloudSyncEngine(store, remote).sync(pushPending = true) }
        assertEquals(1, store.changes.size)
        assertEquals(7, store.cursor)
        assertNull(store.committed)
    }
    @Test fun newerEditSurvivesAcknowledgingOlderPush(): Unit = runBlocking {
        val store = Store(); val remote = Transport()
        store.changes += PendingSyncChange(SyncCollection.AGENT, "1", SyncMutation.UPSERT, 1, body)
        remote.afterPush = { store.changes += it.copy(revision = 2) }
        KotlinCloudSyncEngine(store, remote).sync(pushPending = true)
        assertEquals(2, store.changes.single().revision)
    }
    @Test fun rejectedSessionExpiresOnlyMatchingLogin(): Unit = runBlocking {
        val store = Store(); val remote = Transport(); remote.fail = CloudSessionExpired()
        assertFailsWith<CloudSessionExpired> { KotlinCloudSyncEngine(store, remote).sync() }
        assertEquals(store.identity, store.expired)
        store.expired = null
        val old = store.identity
        remote.fail = null
        remote.afterPush = { store.identity = SyncSession("account", "new"); throw CloudSessionExpired() }
        store.changes += PendingSyncChange(SyncCollection.AGENT, "1", SyncMutation.DELETE, 1)
        assertFailsWith<CloudSessionExpired> { KotlinCloudSyncEngine(store, remote).sync(pushPending = true) }
        assertNull(store.expired)
        assertEquals(1, store.changes.size)
        assertEquals("one", old.generation)
    }
}
