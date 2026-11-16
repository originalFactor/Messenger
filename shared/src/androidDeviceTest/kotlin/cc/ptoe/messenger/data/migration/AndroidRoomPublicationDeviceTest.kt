/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import android.database.sqlite.SQLiteDatabase
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator

/** Device tests use only a unique directory in this test APK's private storage. */
class AndroidRoomPublicationDeviceTest {
    private lateinit var dir: Path
    private val id = "0123456789abcdef0123456789abcdef"
    private val wrongId = "ffffffffffffffffffffffffffffffff"
    private val staged get() = dir.resolve("staged.db")
    private val target get() = dir.resolve("room.db")
    private val receipt get() = dir.resolve("room.receipt")

    @Before fun setUp() {
        val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.toPath()
        dir = Files.createTempDirectory(files, "room-publication-test-")
        Os.chmod(dir.toString(), 448) // 0700
        SQLiteDatabase.openOrCreateDatabase(staged.toFile(), null).use { db ->
            db.execSQL("CREATE TABLE kv (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO kv VALUES (?, ?)", arrayOf(ROOM_PUBLICATION_ID_KEY, id))
            db.execSQL("CREATE TABLE payload (value TEXT NOT NULL)")
            db.execSQL("INSERT INTO payload VALUES ('preserved')")
        }
        Os.chmod(staged.toString(), 384) // 0600
    }

    @After fun tearDown() {
        if (::dir.isInitialized && Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun verifyRoom(path: Path) {
        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("PRAGMA integrity_check", null).use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            db.rawQuery("SELECT value FROM payload", null).use { assertTrue(it.moveToFirst()); assertEquals("preserved", it.getString(0)) }
        }
    }

    @Test fun publishesThenVerifiesExisting() = runBlocking {
        var visited = false
        AndroidRoomPublication.publish(staged, target, receipt, id) { path ->
            assertFalse(Files.exists(receipt, LinkOption.NOFOLLOW_LINKS))
            verifyRoom(path)
            visited = true
            assertFalse(Files.exists(receipt, LinkOption.NOFOLLOW_LINKS))
        }
        assertTrue(visited)
        assertEquals(id, AndroidRoomFiles.readReceipt(receipt))
        AndroidRoomPublication.verifyExisting(target, receipt) { verifyRoom(it) }
    }

    @Test fun existingTargetIsNeverOverwritten() = runBlocking {
        val old = "existing user data".toByteArray()
        Files.write(target, old)
        val error = assertThrows(ErrnoException::class.java) {
            runBlocking { AndroidRoomPublication.publish(staged, target, receipt, id) { fail("unexpected validation") } }
        }
        assertEquals(OsConstants.EEXIST, error.errno)
        assertArrayEquals(old, Files.readAllBytes(target))
        assertFalse(Files.exists(receipt))
    }

    @Test fun missingAndTruncatedReceiptsFailBeforeDatabaseValidation() = runBlocking {
        AndroidRoomFiles.copyExclusive(staged, target)
        var called = false
        val missing = assertThrows(ErrnoException::class.java) {
            runBlocking { AndroidRoomPublication.verifyExisting(target, receipt) { called = true } }
        }
        assertEquals(OsConstants.ENOENT, missing.errno)
        assertFalse(called)
        Files.write(receipt, "messenger-room-v1:0123".toByteArray())
        Os.chmod(receipt.toString(), 384)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { AndroidRoomPublication.verifyExisting(target, receipt) { called = true } }
        }
        assertFalse(called)
        assertTrue(Files.exists(target))
    }

    @Test fun validationFailurePreservesTargetWithoutReceipt() = runBlocking {
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { AndroidRoomPublication.publish(staged, target, receipt, id) { error("validator failed") } }
        }
        assertEquals("validator failed", error.message)
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(receipt, LinkOption.NOFOLLOW_LINKS))
        verifyRoom(target)
    }

    @Test fun databaseIdMismatchPreservesTargetWithoutReceipt() = runBlocking {
        var visited = false
        assertThrows(IllegalStateException::class.java) {
            runBlocking { AndroidRoomPublication.publish(staged, target, receipt, wrongId) { visited = true } }
        }
        assertFalse(visited)
        assertTrue(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(receipt, LinkOption.NOFOLLOW_LINKS))
    }

    @Test fun nioPrivateDirectoryAndTemporaryFile() {
        val subdir = Files.createDirectory(dir.resolve("private"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(subdir)))
        val file = Files.createTempFile(subdir, "stage-", ".db")
        assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        assertNotNull(Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey())
        AndroidRoomFiles.syncPrivateDirectory(subdir)
    }
}
