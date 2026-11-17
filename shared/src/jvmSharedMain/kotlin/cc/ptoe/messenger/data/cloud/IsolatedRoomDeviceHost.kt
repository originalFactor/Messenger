/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.chat.KotlinProviderRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinTurnToolPolicy
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** This opt-in prototype cannot execute remote prompts, ACP operations or workspace requests. */
internal class IsolatedRoomDeviceHostConfig(val defaultName: (deviceId: String) -> String)

/**
 * Read-only device host bound to the exact database and Cloud facade of the isolated slice.
 * Failures are observable; the lifecycle must stop the host before the facade or Room closes.
 */
internal class IsolatedRoomDeviceHost private constructor(
    val failures: StateFlow<Throwable?>,
) {
    companion object {
        suspend fun attach(
            db: LocalDatabase,
            cloud: RoomCloudFacade,
            shutdown: RoomManagedShutdown,
            config: IsolatedRoomDeviceHostConfig,
        ): IsolatedRoomDeviceHost {
            cloud.requireDeviceHostOwner(db)
            val ownerJob = SupervisorJob()
            val scope = CoroutineScope(ownerJob + Dispatchers.IO)
            val lifecycle = Mutex()
            val failure = MutableStateFlow<Throwable?>(null)
            var manager: RoomCloudDeviceHostManager? = null
            var stopped = false
            try {
                withContext(NonCancellable) {
                    shutdown.registerStop {
                        lifecycle.withLock {
                            stopped = true
                            try { manager?.shutdown() } finally { ownerJob.cancelAndJoin() }
                        }
                    }
                }
                lifecycle.withLock {
                    check(!stopped) { "Room shutdown has begun" }
                    val source = RoomDeviceHostAccessSource(db, config.defaultName)
                    val deviceId = source.ensureDeviceId()
                    val access = source.watch(scope)
                    val store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(),
                        KotlinTurnToolPolicy { _, _, _ -> false }, cloud.providerGate)
                    val snapshots = RoomRemoteTurnSnapshots(store) { error("Remote ACP task observation is unavailable") }
                    val fence = RoomDeviceControlFence.forDao(db.cloudSync())
                    val hostManager = RoomCloudDeviceHostManager(access, source::stillCurrent, { stored, grant ->
                        RoomCloudDeviceControlHost(stored, deviceId, grant,
                            { session -> session == stored.session && source.stillCurrent(stored) },
                            { session -> if (session == stored.session) cloud.rejectDeviceHostSession(db, stored) },
                            fence.replies(db, stored, deviceId), RoomDeviceControlDispatcher(db, stored, deviceId, snapshots,
                                modeGate = cloud.providerGate, onModeChanged = cloud::requestLocalSync))
                    }, { failure.value = it })
                    manager = hostManager
                    scope.launch {
                        try { hostManager.run() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Throwable) { failure.value = error }
                    }
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    try { manager?.shutdown() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                    ownerJob.cancelAndJoin()
                }
                throw error
            }
            return IsolatedRoomDeviceHost(failure.asStateFlow())
        }
    }
}
