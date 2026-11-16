/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.chat.KotlinModelDiscovery
import cc.ptoe.messenger.data.chat.KotlinModelDiscoveryBridge
import cc.ptoe.messenger.data.chat.KotlinRoomToolCatalogBridge
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.domain.chat.KotlinTurnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Partial Room-owned bridge; this does not implement a complete application backend. The caller
 * must close UI and other bridge entry points, then stop every other database user before the
 * enclosing owner closes Room. The managed lifecycle retains ownership if cleanup cannot finish.
 */
internal class KotlinRoomBridgeComposition private constructor(
    val bridge: CoreBridge,
    private val events: KotlinRoomEventsBridge,
    private val discovery: KotlinModelDiscovery,
    private val requests: DiscoveryRequests,
) {
    private val shutdownMutex = Mutex()
    private var stopped = false
    @Volatile private var chatWorkers: Job? = null

    val chatLifecycle: KotlinTurnLifecycle = object : KotlinTurnLifecycle {
        override fun status(conversationId: String, status: String) = events.chatTaskStatus(conversationId, status)
    }

    /** Bind before exposing chat; a failed worker stop must not close its event subscribers. */
    fun bindChatWorkers(job: Job) {
        check(chatWorkers == null) { "Chat workers already bound" }
        chatWorkers = job
    }

    /** Blocks new discovery calls and joins admitted calls and event observers before returning. */
    suspend fun shutdown() = withContext(NonCancellable) {
        shutdownMutex.withLock {
            if (stopped) return@withLock
            check(chatWorkers?.isCompleted != false) { "Chat workers have not stopped" }
            requests.stopAndJoin()
            events.shutdown()
            discovery.close()
            stopped = true
        }
    }

    companion object {
        /** Registers cleanup before returning the bridge to the Room owner. */
        suspend fun attach(
            db: LocalDatabase,
            shutdown: RoomManagedShutdown,
            catalogUrl: String = "https://models.dev/models.json",
            requestTimeoutMillis: Long = 30_000,
            syncBuiltinModels: suspend (Boolean) -> Long = { error("Cloud model synchronization requires the Room Cloud facade") },
        ): KotlinRoomBridgeComposition {
            val acp = RoomAcpConversationStore(db, object : AcpSessionMutationGate {
                override suspend fun <T> withInactiveSession(id: String, deleting: Boolean, action: suspend () -> T): T =
                    error("ACP session mutations require the Kotlin ACP host")
            })
            val base = KotlinRoomCoreBase(db, acp)
            val catalog = KotlinRoomToolCatalogBridge(base, db)
            val events = KotlinRoomEventsBridge(catalog, db)
            val discovery = KotlinModelDiscovery(db, catalogUrl = catalogUrl, requestTimeoutMillis = requestTimeoutMillis)
            val modelBridge = KotlinModelDiscoveryBridge(events, discovery, syncBuiltinModels)
            val requests = DiscoveryRequests()
            val managedBridge = ManagedDiscoveryBridge(modelBridge, requests)
            val result = KotlinRoomBridgeComposition(managedBridge, events, discovery, requests)
            try {
                withContext(NonCancellable) { shutdown.registerStop { result.shutdown() } }
            } catch (failure: Throwable) {
                try { result.shutdown() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
            return result
        }
    }
}

/** Each admitted call is a child job, so cancellation does not cancel the caller's whole scope. */
private class DiscoveryRequests {
    private val mutex = Mutex()
    private var closed = false
    private val pending = mutableSetOf<Job>()

    suspend fun <T> run(block: suspend () -> T): T = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        mutex.withLock {
            check(!closed) { "Model discovery is closed" }
            pending.add(job)
        }
        try { block() } finally { withContext(NonCancellable) { mutex.withLock { pending.remove(job) } } }
    }

    suspend fun stopAndJoin() {
        val jobs = mutex.withLock { closed = true; pending.toList() }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }
}

private class ManagedDiscoveryBridge(private val delegate: CoreBridge, private val requests: DiscoveryRequests) : CoreBridge by delegate {
    override suspend fun discoverProviderModelsJson(baseUrl: String, apiKey: String, apiFormat: String?): String =
        requests.run { delegate.discoverProviderModelsJson(baseUrl, apiKey, apiFormat) }
    override suspend fun modelsDevMetadataJson(modelId: String): String? = requests.run { delegate.modelsDevMetadataJson(modelId) }
    override suspend fun cloudSyncBuiltinModels(force: Boolean): Long = requests.run { delegate.cloudSyncBuiltinModels(force) }
}
