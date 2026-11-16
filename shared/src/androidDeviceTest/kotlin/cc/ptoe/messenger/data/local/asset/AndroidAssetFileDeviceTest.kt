/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import android.system.Os
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AndroidAssetFileDeviceTest {
    private lateinit var dir: Path
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Gate : CoreAssetGate {
        private val mutex = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = mutex.withLock { block() }
    }

    @Before fun setUp() {
        dir = Files.createTempDirectory(context.filesDir.toPath(), "asset-file-test-")
        Os.chmod(dir.toString(), 448)
    }

    @After fun tearDown() {
        if (::dir.isInitialized && Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun importsWithoutHardLinksAndPreservesExistingBytes() = runBlocking {
        val root = Files.createDirectory(dir.resolve("assets"))
        Os.chmod(root.toString(), 448)
        val db = Room.databaseBuilder<LocalDatabase>(context, dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            assets.withExclusive { assets.requireDurableStorageWithGateHeld(); assertTrue(assetUsableSpace(root) > 0) }
            val original = "asset-data".toByteArray()
            val first = assets.importFile(original, "text/plain", CoreAssetOwner("learning", "one"), 100)
            assertArrayEquals(original, assets.read(first.id))
            val second = assets.importFile(original, "text/plain", CoreAssetOwner("learning", "two"), 101)
            assertEquals(first.id, second.id)
            assertEquals(setOf("one", "two"), second.references.map { it.ownerId }.toSet())

            val other = "another asset".toByteArray()
            val id = MessageDigest.getInstance("SHA-256").digest(other).joinToString("") { "%02x".format(it.toInt() and 255) }
            val conflict = root.resolve(id)
            Files.write(conflict, "unrelated".toByteArray())
            val error = runCatching { assets.importFile(other, "text/plain", CoreAssetOwner("learning", "three"), 102) }.exceptionOrNull()
            assertNotNull(error)
            assertArrayEquals("unrelated".toByteArray(), Files.readAllBytes(conflict))
            assertNull(db.assetMetadata().metadata("local_asset:$id"))
        } finally { db.close() }
    }

    @Test fun rejectsNonPrivateAssetRoot() = runBlocking {
        val root = Files.createDirectory(dir.resolve("assets"))
        Os.chmod(root.toString(), 493)
        val db = Room.databaseBuilder<LocalDatabase>(context, dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            assertNotNull(runCatching { requirePrivateAssetRoot(root) }.exceptionOrNull())
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            assertNotNull(runCatching { assets.importFile("secret".toByteArray(), "text/plain", CoreAssetOwner("learning", "one"), 1) }.exceptionOrNull())
            assertEquals(0L, Files.list(root).use { it.count() })
        } finally { db.close(); Os.chmod(root.toString(), 448) }
    }
}
