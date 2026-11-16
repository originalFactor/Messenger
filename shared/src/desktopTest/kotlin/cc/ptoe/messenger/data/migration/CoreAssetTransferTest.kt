/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class CoreAssetTransferTest {
    private fun SQLiteConnection.exec(sql: String) = prepare(sql).use { it.step(); Unit }
    private fun createPrivate(path: Path) = Files.createDirectory(path,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun fixture(path: Path, bytes: ByteArray): String {
        val id = hash(bytes)
        BundledSQLiteDriver().open(path.toString()).use { db ->
            checkNotNull(javaClass.getResource("/core-store-v9.sql")).readText().split(';')
                .map(String::trim).filter(String::isNotEmpty).forEach { db.exec(it) }
            db.prepare("INSERT INTO kv(key,value) VALUES (?,?)").use { row ->
                row.bindText(1, "local_asset:$id")
                row.bindText(2, """{"id":"$id","sha256":"$id","mimeType":"application/octet-stream","size":${bytes.size},"createdAt":1,"references":[]}""")
                row.step()
            }
        }
        return id
    }

    @Test
    fun copiesVerifiedBytesToIndependentDirectoryWithoutChangingSource() {
        val dir = Files.createTempDirectory("core-assets-transfer")
        try {
            val source = dir.resolve("source.db")
            val oldAssets = createPrivate(dir.resolve("old-assets"))
            val newAssets = createPrivate(dir.resolve("new-assets"))
            val data = "asset bytes".encodeToByteArray()
            val id = fixture(source, data)
            Files.write(oldAssets.resolve(id), data)
            CoreSourceSqliteLease.acquire(source).use { lease ->
                assertEquals(1, CoreAssetTransfer.copy(lease, oldAssets, newAssets))
                assertContentEquals(data, Files.readAllBytes(newAssets.resolve(id)))
                assertContentEquals(data, Files.readAllBytes(oldAssets.resolve(id)))
                assertFails { CoreAssetTransfer.copy(lease, oldAssets, newAssets) }
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test
    fun rejectsCorruptSourceAndPreservesTheSourceFile() {
        val dir = Files.createTempDirectory("core-assets-corrupt")
        try {
            val source = dir.resolve("source.db")
            val oldAssets = createPrivate(dir.resolve("old-assets"))
            val newAssets = createPrivate(dir.resolve("new-assets"))
            val id = fixture(source, "original".encodeToByteArray())
            val corrupt = "modified".encodeToByteArray()
            Files.write(oldAssets.resolve(id), corrupt)
            CoreSourceSqliteLease.acquire(source).use { lease ->
                assertFails { CoreAssetTransfer.copy(lease, oldAssets, newAssets) }
            }
            assertContentEquals(corrupt, Files.readAllBytes(oldAssets.resolve(id)))
            assertTrue(Files.list(newAssets).use { !it.findAny().isPresent })
        } finally { dir.toFile().deleteRecursively() }
    }
}
