/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking

/** Test-APK-only service for verifying that a secondary Android process cannot acquire an owner. */
class AndroidRoomOwnerProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
            val destination = Paths.get(checkNotNull(data.readString()))
            val rejected = runBlocking {
                runCatching {
                    AndroidRoomDirectoryOwner.acquire(this@AndroidRoomOwnerProbeService, destination,
                        RoomWritersStoppedProof { }, RoomWritersStoppedProof { })
                }.isFailure
            }
            checkNotNull(reply).writeNoException()
            reply.writeInt(Process.myPid())
            reply.writeInt(if (rejected) 1 else 0)
            return true
        }
    }
}
