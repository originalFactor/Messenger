/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Isolated test-APK storage only; supplied proofs model the platform fences required by the host. */
class AndroidRoomCutoverHostDeviceTest {
    private lateinit var context: Context
    private lateinit var dir: Path
    private lateinit var legacyDir: Path

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        dir = Files.createTempDirectory(target.filesDir.toPath(), "android-cutover-host-")
        Os.chmod(dir.toString(), 448)
        legacyDir = Files.createDirectory(dir.resolve("legacy-databases"))
        context = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = legacyDir.resolve(name).toFile()
        }
    }

    @Test fun legacyDatabaseAndSidecarsBlockFreshStartWithoutMutation(): Unit = runBlocking {
        val stopped = RoomWritersStoppedProof { }
        val proofs = AndroidRoomCutoverProofs(stopped, stopped, stopped, stopped)
        for (name in listOf("messenger_database", "messenger_database.db")) {
            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                val candidate = context.getDatabasePath(name + suffix).toPath()
                val contents = "legacy-$name$suffix".toByteArray()
                Files.write(candidate, contents)
                val target = dir.resolve("target-${name.replace('.', '-')}-${suffix.ifEmpty { "main" }}.db")
                var opened = false
                val host = AndroidRoomCutoverHost.create(context, dir.resolve("missing-core.db"), target, proofs,
                    {}, { opened = true; error("must not open Room") })
                try {
                    assertTrue(runCatching { host.withFreshStore { Unit } }.exceptionOrNull()?.message.orEmpty().contains("Legacy Room"))
                    assertFalse(opened)
                    assertFalse(Files.exists(target))
                    assertTrue(Files.readAllBytes(candidate).contentEquals(contents))
                } finally {
                    host.recoverFailedStart()
                    Files.delete(candidate)
                }
            }
        }
    }

    @Test fun legacyAppearingDuringInitializationBlocksPublication(): Unit = runBlocking {
        val stopped = RoomWritersStoppedProof { }
        val target = dir.resolve("room.db")
        val candidate = context.getDatabasePath("messenger_database-wal").toPath()
        var introduced = false
        val host = AndroidRoomCutoverHost.create(context, dir.resolve("missing-core.db"), target,
            AndroidRoomCutoverProofs(stopped, stopped, stopped, stopped), {}, { path ->
                if (!introduced) {
                    introduced = true
                    Files.write(candidate, byteArrayOf(7, 8, 9))
                }
                Room.databaseBuilder<LocalDatabase>(context, path.toString()).setDriver(BundledSQLiteDriver()).build()
            })
        try {
            assertTrue(runCatching { host.withFreshStore { error("must not enter Room") } }.exceptionOrNull()?.message.orEmpty().contains("Legacy Room"))
            assertTrue(introduced)
            assertFalse(Files.exists(target))
            assertTrue(Files.readAllBytes(candidate).contentEquals(byteArrayOf(7, 8, 9)))
        } finally { host.recoverFailedStart() }
    }

    @Test fun legacySidecarBlocksReopeningFreshStoreAndPreservesIt(): Unit = runBlocking {
        val stopped = RoomWritersStoppedProof { }
        val proofs = AndroidRoomCutoverProofs(stopped, stopped, stopped, stopped)
        val target = dir.resolve("room.db")
        fun open(path: Path) = Room.databaseBuilder<LocalDatabase>(context, path.toString())
            .setDriver(BundledSQLiteDriver()).build()
        val first = AndroidRoomCutoverHost.create(context, dir.resolve("missing-core.db"), target, proofs, {}, ::open)
        first.withFreshStore { assertEquals(1, it.rows().defaultAgents().size) }
        val before = Files.readAllBytes(target)
        val candidate = context.getDatabasePath("messenger_database.db-shm").toPath()
        Files.createSymbolicLink(candidate, dir.resolve("missing-sidecar"))
        var entered = false
        val second = AndroidRoomCutoverHost.create(context, dir.resolve("missing-core.db"), target, proofs, {}, ::open)
        try {
            assertTrue(runCatching { second.withFreshStore { entered = true } }.exceptionOrNull()?.message.orEmpty().contains("Legacy Room"))
            assertFalse(entered)
            assertTrue(Files.readAllBytes(target).contentEquals(before))
            assertTrue(Files.isSymbolicLink(candidate))
        } finally { second.recoverFailedStart() }
    }

    private fun makeCore(path: Path, marker: String? = null) {
        val sql = InstrumentationRegistry.getInstrumentation().context.assets.open("core-store-v9.sql")
            .bufferedReader().use { it.readText() }
        BundledSQLiteDriver().open(path.toString()).use { db ->
            fun exec(statement: String) = db.prepare(statement).use { it.step(); Unit }
            sql.split(';').map(String::trim).filter(String::isNotEmpty).forEach(::exec)
            exec("INSERT INTO agents(id,name,systemPrompt,isDefault,createdAt,updatedAt) VALUES ('core-default','Default','prompt',1,1,1)")
            exec("INSERT INTO kv VALUES ('migration-test','retained')")
            if (marker != null) db.prepare("INSERT INTO kv VALUES (?, ?)").use { row ->
                row.bindText(1, "legacy_import_done"); row.bindText(2, marker); row.step()
            }
        }
    }

    @Test fun existingCoreAndAnyLegacyFileBlockCutoverRegardlessOfImportMarker(): Unit = runBlocking {
        val stopped = RoomWritersStoppedProof { }
        val proofs = AndroidRoomCutoverProofs(stopped, stopped, stopped, stopped)
        for (marker in listOf(null, "none:100", "v1:100")) {
            for (name in listOf("messenger_database", "messenger_database.db")) {
                for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                    val source = dir.resolve("core.db")
                    if (!Files.exists(source)) makeCore(source, marker)
                    val sourceBytes = Files.readAllBytes(source)
                    val candidate = legacyDir.resolve(name + suffix)
                    val original = "legacy-$name$suffix".toByteArray()
                    Files.write(candidate, original)
                    val target = dir.resolve("room.db")
                    var opened = false
                    val host = AndroidRoomCutoverHost.create(context, source, target, proofs, {},
                        { opened = true; error("must not open Room") })
                    try {
                        assertTrue(runCatching { host.withCoreStore(dir.resolve("old-assets"), dir.resolve("new-assets")) { Unit } }
                            .exceptionOrNull()?.message.orEmpty().contains("Legacy Room"))
                        assertFalse(opened)
                        assertFalse(Files.exists(target))
                        assertTrue(Files.readAllBytes(source).contentEquals(sourceBytes))
                        assertTrue(Files.readAllBytes(candidate).contentEquals(original))
                    } finally {
                        host.recoverFailedStart()
                        Files.delete(candidate)
                    }
                }
            }
            Files.delete(dir.resolve("core.db"))
        }
    }

    @Test fun legacyAppearingDuringCoreMigrationBlocksPublication(): Unit = runBlocking {
        val source = dir.resolve("core.db")
        makeCore(source, "v1:100")
        val target = dir.resolve("room.db")
        val candidate = legacyDir.resolve("messenger_database-wal")
        val stopped = RoomWritersStoppedProof { }
        var introduced = false
        val host = AndroidRoomCutoverHost.create(context, source, target,
            AndroidRoomCutoverProofs(stopped, stopped, stopped, stopped), {}, { path ->
                if (!introduced) { introduced = true; Files.write(candidate, byteArrayOf(7, 8, 9)) }
                Room.databaseBuilder<LocalDatabase>(context, path.toString()).setDriver(BundledSQLiteDriver()).build()
            })
        try {
            assertTrue(runCatching { host.withCoreStore(dir.resolve("old-assets"), dir.resolve("new-assets")) { error("must not enter Room") } }
                .exceptionOrNull()?.message.orEmpty().contains("Legacy Room"))
            assertTrue(introduced)
            assertFalse(Files.exists(target))
            assertTrue(Files.readAllBytes(candidate).contentEquals(byteArrayOf(7, 8, 9)))
            assertTrue(Files.exists(source))
        } finally { host.recoverFailedStart() }
    }

    @Test fun existingCoreWithoutLegacyFilesStillMigrates(): Unit = runBlocking {
        val source = dir.resolve("core.db")
        makeCore(source, "none:100")
        val target = dir.resolve("room.db")
        val stopped = RoomWritersStoppedProof { }
        val host = AndroidRoomCutoverHost.create(context, source, target,
            AndroidRoomCutoverProofs(stopped, stopped, stopped, stopped), {}, { path ->
                Room.databaseBuilder<LocalDatabase>(context, path.toString()).setDriver(BundledSQLiteDriver()).build()
            })
        host.withCoreStore(dir.resolve("old-assets"), dir.resolve("new-assets")) { room ->
            assertEquals("retained", room.rows().getValue("migration-test")?.value)
            assertEquals("core-default", room.rows().defaultAgents().single().id)
        }
        assertTrue(Files.exists(source))
        assertTrue(Files.exists(CoreStorePairedCutover.receiptFor(target)))
    }

    @After fun tearDown() {
        if (::dir.isInitialized && Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun eachPreviousWriterProofIsRequiredBeforeRoomOpensAndRecoveryRechecksAll(): Unit = runBlocking {
        for (failed in 0..2) {
            val root = Files.createDirectory(dir.resolve("case-$failed"))
            Os.chmod(root.toString(), 448)
            val target = root.resolve("room.db")
            val active = root.resolve(".room.db.room-owner.active")
            val checks = IntArray(4)
            var fail = failed
            var opened = false
            fun proof(index: Int) = RoomWritersStoppedProof { checks[index]++; check(fail != index) }
            val host = AndroidRoomCutoverHost.create(context, root.resolve("core.db"), target,
                AndroidRoomCutoverProofs(proof(0), proof(1), proof(2), proof(3)), {},
                { opened = true; error("Room must not open") })
            assertTrue(runCatching { host.withFreshStore { Unit } }.isFailure)
            assertFalse(opened)
            assertTrue(Files.isDirectory(active))
            assertTrue(runCatching { host.recoverFailedStart() }.isFailure)
            assertTrue(Files.isDirectory(active))
            fail = 3
            assertTrue(runCatching { host.recoverFailedStart() }.isFailure)
            assertTrue(Files.isDirectory(active))
            fail = -1
            host.recoverFailedStart()
            assertFalse(Files.exists(active))
            assertTrue(checks.all { it > 0 })
        }
    }

    @Test fun unusedHostCanReleaseItsMarkerOnlyAfterIndependentProofs(): Unit = runBlocking {
        val target = dir.resolve("room.db")
        val active = dir.resolve(".room.db.room-owner.active")
        var runtimeStopped = false
        val stopped = RoomWritersStoppedProof { }
        val host = AndroidRoomCutoverHost.create(context, dir.resolve("core.db"), target,
            AndroidRoomCutoverProofs(stopped, RoomWritersStoppedProof { check(runtimeStopped) }, stopped, stopped),
            {}, { error("Room must not open") })
        assertTrue(Files.isDirectory(active))
        assertTrue(runCatching { host.recoverFailedStart() }.isFailure)
        assertTrue(Files.isDirectory(active))
        runtimeStopped = true
        host.recoverFailedStart()
        assertFalse(Files.exists(active))
        assertTrue(runCatching { host.withFreshStore { Unit } }.isFailure)
    }

    @Test fun missingFinalWorkerProofRetainsTheMarkerForRecovery(): Unit = runBlocking {
        val target = dir.resolve("room.db")
        val active = dir.resolve(".room.db.room-owner.active")
        var verified = false
        var database: LocalDatabase? = null
        val stopped = RoomWritersStoppedProof { }
        val host = AndroidRoomCutoverHost.create(context, dir.resolve("core.db"), target,
            AndroidRoomCutoverProofs(stopped, stopped, stopped, RoomWritersStoppedProof { check(verified) }),
            {}, { path -> Room.databaseBuilder<LocalDatabase>(context, path.toString())
                .setDriver(BundledSQLiteDriver()).build() })
        assertTrue(runCatching { host.withFreshStore { database = it } }.isFailure)
        assertTrue(Files.isDirectory(active))
        assertTrue(runCatching { checkNotNull(database).rows().defaultAgents() }.isFailure)
        assertTrue(runCatching { host.recoverFailedStart() }.isFailure)
        verified = true
        host.recoverFailedStart()
        assertFalse(Files.exists(active))
    }

    @Test fun failedWorkerStopKeepsRoomAndOwnerLiveUntilRetryAndFinalProof(): Unit = runBlocking {
        val target = dir.resolve("room.db")
        val active = dir.resolve(".room.db.room-owner.active")
        val checks = IntArray(4)
        var stopAttempts = 0
        var database: LocalDatabase? = null
        fun proof(index: Int) = RoomWritersStoppedProof { checks[index]++ }
        val host = AndroidRoomCutoverHost.create(context, dir.resolve("core.db"), target,
            AndroidRoomCutoverProofs(proof(0), proof(1), proof(2), proof(3)),
            { stopAttempts++; if (stopAttempts == 1) error("worker is still active") },
            { path -> Room.databaseBuilder<LocalDatabase>(context, path.toString())
                .setDriver(BundledSQLiteDriver()).build() })
        val running = async {
            host.withFreshStore { room -> database = room; assertEquals(1, room.rows().defaultAgents().size) }
        }
        withTimeout(15_000) { host.shutdownState.first { it is RoomShutdownState.RecoveryRequired } }
        assertTrue(Files.isDirectory(active))
        assertEquals(1, checkNotNull(database).rows().defaultAgents().size)
        assertTrue(runCatching { host.recoverFailedStart() }.isFailure)
        assertEquals(0, checks[3])
        host.retryStop()
        withTimeout(15_000) { running.await() }
        assertEquals(2, stopAttempts)
        assertTrue(checks.all { it >= 1 })
        assertTrue(runCatching { checkNotNull(database).rows().defaultAgents() }.isFailure)
        assertFalse(Files.exists(active))
    }
}
