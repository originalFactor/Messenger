/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.system.Os
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** These tests use only the test APK's private storage, not the installed application store. */
class AndroidRoomDirectoryOwnerDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: Path
    private val target get() = dir.resolve("room.db")
    private val active get() = dir.resolve(".room.db.room-owner.active")
    private val recovery get() = dir.resolve(".room.db.room-owner.recovery")
    private val stopped = RoomWritersStoppedProof { }

    @Before fun setUp() {
        dir = Files.createTempDirectory(context.filesDir.toPath(), "android-room-owner-")
        Os.chmod(dir.toString(), 448)
    }

    @After fun tearDown() {
        if (::dir.isInitialized && Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun activeOwnerExcludesOtherOwnersAndOSProcessUntilStopProof(): Unit = runBlocking {
        val filesMode = Os.lstat(context.filesDir.absolutePath).st_mode
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var priorChecks = 0
        var stopChecks = 0
        val owner = AndroidRoomDirectoryOwner.acquire(context, target,
            RoomWritersStoppedProof { priorChecks++ }, RoomWritersStoppedProof { stopChecks++; assertTrue(Files.isDirectory(active)) })
        val running = async {
            owner.withExclusiveOwnership {
                entered.complete(Unit)
                finish.await()
                "done"
            }
        }
        try {
            withTimeout(5_000) { entered.await() }
            assertEquals(1, priorChecks)
            assertTrue(Files.isDirectory(active))
            assertTrue(runCatching { AndroidRoomDirectoryOwner.acquire(context, target, stopped, stopped) }.isFailure)
            val child = ProcessBuilder("/system/bin/sh", "-c", "mkdir \"\$1\"", "owner-probe", active.toString()).start()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS))
            assertNotEquals(0, child.exitValue())
            finish.complete(Unit)
            assertEquals("done", withTimeout(5_000) { running.await() })
            assertEquals(1, stopChecks)
            assertFalse(Files.exists(active))
            assertFalse(Files.exists(recovery))
            val next = AndroidRoomDirectoryOwner.acquire(context, target, stopped, stopped)
            next.withExclusiveOwnership { assertTrue(Files.isDirectory(active)) }
            assertFalse(Files.exists(active))
            assertEquals(filesMode, Os.lstat(context.filesDir.absolutePath).st_mode)
        } finally {
            finish.complete(Unit)
            withTimeout(5_000) { running.await() }
        }
    }

    @Test fun failedProofOrCallbackRequiresGuardedRecovery(): Unit = runBlocking {
        var entered = false
        val owner = AndroidRoomDirectoryOwner.acquire(context, target, RoomWritersStoppedProof { error("old Core is active") }, stopped)
        assertTrue(runCatching { owner.withExclusiveOwnership { entered = true } }.isFailure)
        assertFalse(entered)
        assertTrue(Files.isDirectory(active))
        assertTrue(runCatching { owner.recoverAndRelease(RoomWritersStoppedProof { error("workers still active") }) }.isFailure)
        assertTrue(Files.isDirectory(recovery))
        assertTrue(runCatching { AndroidRoomDirectoryOwner.acquire(context, target, stopped, stopped) }.isFailure)
        owner.recoverAndRelease(stopped)
        assertFalse(Files.exists(active))
        assertFalse(Files.exists(recovery))

        val second = AndroidRoomDirectoryOwner.acquire(context, target, stopped, RoomWritersStoppedProof { error("worker did not stop") })
        assertTrue(runCatching { second.withExclusiveOwnership { Unit } }.isFailure)
        assertTrue(Files.isDirectory(active))
        second.recoverAndRelease(stopped)
        assertFalse(Files.exists(active))
    }

    @Test fun roomLifetimeRemainsInsideOwnerUntilDatabaseCloses(): Unit = runBlocking {
        var database: LocalDatabase? = null
        val owner = AndroidRoomDirectoryOwner.acquire(context, target, stopped, RoomWritersStoppedProof {
            assertTrue(Files.isDirectory(active))
            assertTrue(runCatching { checkNotNull(database).rows().defaultAgents() }.isFailure)
        })
        RoomStoreStartup.withRoomStore(dir.resolve("missing-core.db"), target, owner, RoomManagedShutdown { },
            { path -> Room.databaseBuilder<LocalDatabase>(context = context, name = path.toString())
                .setDriver(BundledSQLiteDriver()).build() }) { room ->
            database = room
            assertTrue(Files.isDirectory(active))
            assertEquals(1, room.rows().defaultAgents().size)
        }
        assertFalse(Files.exists(active))
        assertFalse(Files.exists(recovery))
    }

    @Test fun previousProcessMarkerRequiresIndependentRecoveryProof(): Unit = runBlocking {
        val child = ProcessBuilder("/system/bin/sh", "-c", "mkdir \"\$1\"", "owner-probe", active.toString()).start()
        assertTrue(child.waitFor(5, TimeUnit.SECONDS))
        assertEquals(0, child.exitValue())
        Os.chmod(active.toString(), 448)
        assertTrue(runCatching { AndroidRoomDirectoryOwner.acquire(context, target, stopped, stopped) }.isFailure)
        val repair = AndroidRoomDirectoryOwner.acquireRecovery(context, target)
        assertTrue(runCatching { repair.recoverAndRelease(RoomWritersStoppedProof { error("old worker is alive") }) }.isFailure)
        assertTrue(Files.isDirectory(active))
        assertTrue(Files.isDirectory(recovery))
        repair.recoverAndRelease(stopped)
        assertFalse(Files.exists(active))
        assertFalse(Files.exists(recovery))
        val owner = AndroidRoomDirectoryOwner.acquire(context, target, stopped, stopped)
        owner.withExclusiveOwnership { Unit }
    }

    @Test fun secondaryAndroidProcessCannotAcquireOwner(): Unit = runBlocking {
        val connected = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, AndroidRoomOwnerProbeService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            val service = withTimeout(5_000) { connected.await() }
            val request = Parcel.obtain()
            val response = Parcel.obtain()
            try {
                request.writeString(target.toString())
                assertTrue(service.transact(IBinder.FIRST_CALL_TRANSACTION, request, response, 0))
                response.readException()
                assertNotEquals(Process.myPid(), response.readInt())
                assertEquals(1, response.readInt())
                assertFalse(Files.exists(active))
            } finally { request.recycle(); response.recycle() }
        } finally { context.unbindService(connection) }
    }

    @Test fun rejectsNonPrivateAndSymlinkedDestinations(): Unit = runBlocking {
        val outside = context.cacheDir.toPath().resolve("outside-room.db")
        assertTrue(runCatching { AndroidRoomDirectoryOwner.acquire(context, outside, stopped, stopped) }.isFailure)
        val actual = dir.resolve("actual.db")
        Files.writeString(actual, "keep")
        Os.chmod(actual.toString(), 384)
        Os.symlink(actual.toString(), target.toString())
        assertTrue(runCatching { AndroidRoomDirectoryOwner.acquire(context, target, stopped, stopped) }.isFailure)
        assertEquals("keep", Files.readString(actual))
        Files.delete(target)
        val exposed = Files.createDirectory(dir.resolve("exposed"))
        Os.chmod(exposed.toString(), 493) // 0755
        assertTrue(runCatching { AndroidRoomDirectoryOwner.acquire(context, exposed.resolve("room.db"), stopped, stopped) }.isFailure)
        assertFalse(Files.exists(exposed.resolve(".room.db.room-owner.active")))
    }

    @Test fun rejectsUnsafeDataAncestorOrFilesDirectory(): Unit = runBlocking {
        val dataRoot = Files.createDirectory(dir.resolve("pretend-data"))
        val filesRoot = Files.createDirectory(dataRoot.resolve("files"))
        val privateRoom = Files.createDirectory(filesRoot.resolve("room"))
        Os.chmod(privateRoom.toString(), 448)
        val fake = object : ContextWrapper(context) {
            override fun getDataDir() = dataRoot.toFile()
            override fun getFilesDir() = filesRoot.toFile()
        }
        Os.chmod(dataRoot.toString(), 493) // A public ancestor cannot protect filesDir.
        Os.chmod(filesRoot.toString(), 505) // 0771 is safe only under a private data ancestor.
        assertTrue(runCatching { AndroidRoomDirectoryOwner.acquire(fake, privateRoom.resolve("room.db"), stopped, stopped) }.isFailure)
        Os.chmod(dataRoot.toString(), 448)
        Os.chmod(filesRoot.toString(), 511) // world read/write are rejected even under the ancestor
        assertTrue(runCatching { AndroidRoomDirectoryOwner.acquire(fake, privateRoom.resolve("room.db"), stopped, stopped) }.isFailure)
        assertFalse(Files.exists(privateRoom.resolve(".room.db.room-owner.active")))
    }
}
