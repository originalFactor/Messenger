/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Transactor
import androidx.room.useReaderConnection
import cc.ptoe.messenger.data.local.room.CloudSyncDao
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun interface RoomDeviceReplyGate {
    suspend fun send(epoch: Long, operation: suspend () -> Unit)
}

/**
 * One gate for one Room owner. Every session/grant writer must use [mutate], and
 * every reply must use [replies], for revocation to fence already admitted sends.
 * No other Room instance or legacy Core writer may access this database concurrently.
 */
internal class RoomDeviceControlFence private constructor() {
    private val mutex = Mutex()
    private val admissionLock = Any()
    private var epoch = 0L

    companion object {
        private val instances = WeakHashMap<CloudSyncDao, WeakReference<RoomDeviceControlFence>>()
        fun forDao(dao: CloudSyncDao): RoomDeviceControlFence = synchronized(instances) {
            instances[dao]?.get() ?: RoomDeviceControlFence().also { instances[dao] = WeakReference(it) }
        }
    }

    suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock {
        synchronized(admissionLock) { epoch++ }
        block()
    }

    internal fun authoritySnapshot(): Long = synchronized(admissionLock) { epoch }
    internal fun <T> authorizeAuthority(expected: Long, block: () -> T): T = synchronized(admissionLock) {
        check(epoch == expected) { "Device authority changed" }
        block()
    }

    fun replies(db: LocalDatabase, stored: StoredCloudSession, deviceId: String): RoomDeviceReplyGate {
        check(this === forDao(db.cloudSync())) { "Reply gate belongs to another Room database" }
        return RoomDeviceReplyGate { epoch, send ->
            mutex.withLock {
                db.useReaderConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
                    val rows = db.rows()
                    rows.requireRemoteControl(KotlinRemoteControlScope(stored.session.accountId, stored.session.generation, stored.host, deviceId, epoch))
                    check(rows.getValue("cloud_session")?.value == stored.cookie) { "Cloud session changed" }
                } }
                send()
            }
        }
    }
}
