/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import cc.ptoe.messenger.data.local.asset.CoreAssetReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Copy the assets named by the held source transaction to a separate, empty private directory.
 * The caller must keep the SQLite lease held and publish the directory together with the imported
 * database using a separately validated cutover protocol. This helper is not wired to startup.
 * It does not delete the source or failed/partial destination; failures require recovery.
 */
internal object CoreAssetTransfer {
    fun copy(lease: CoreSourceSqliteLease, sourceRoot: Path, targetRoot: Path): Int {
        return transfer(lease, sourceRoot, targetRoot, markerName = null, resume = false)
    }

    /** Resume only files whose identity and bytes match the still-held source transaction. */
    internal fun resume(lease: CoreSourceSqliteLease, sourceRoot: Path, targetRoot: Path, markerName: String): Int =
        transfer(lease, sourceRoot, targetRoot, markerName, resume = true)

    private fun transfer(lease: CoreSourceSqliteLease, sourceRoot: Path, targetRoot: Path,
        markerName: String?, resume: Boolean): Int {
        val source = sourceRoot.toAbsolutePath().normalize()
        val target = targetRoot.toAbsolutePath().normalize()
        require(source != target && !target.startsWith(source) && !source.startsWith(target)) {
            "Core and Room assets must use separate directories"
        }
        requireRoomPublicationParent(target)
        val assets = lease.assetMetadata()
        val expected = assets.map { it.first }.toSet()
        Files.newDirectoryStream(target).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                check(resume && (name == markerName || name in expected)) { "Asset destination contains an unexpected file" }
            }
        }
        if (assets.isNotEmpty()) {
            check(Files.isDirectory(source, NOFOLLOW_LINKS)) { "Core asset directory is missing" }
            val sourceReal = source.toRealPath()
            val targetReal = target.toRealPath()
            require(sourceReal != targetReal && !targetReal.startsWith(sourceReal) && !sourceReal.startsWith(targetReal)) {
                "Core and Room assets must not share a directory tree"
            }
            val reader = CoreAssetReader(source)
            val verifier = CoreAssetReader(target)
            for ((id, metadata) in assets) {
                lease.requireDistinct(source.resolve(id))
                lease.requireDistinct(target.resolve(id))
                val bytes = reader.read(id, metadata) // checks ID, size, file type and SHA-256
                val destination = target.resolve(id)
                if (!resume || Files.notExists(destination, NOFOLLOW_LINKS)) {
                    FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { file ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) check(file.write(buffer) > 0) { "Asset copy made no progress" }
                        file.force(true)
                    }
                }
                check(verifier.read(id, metadata).contentEquals(bytes)) { "Copied asset does not match source" }
            }
        }
        syncRoomPublicationDirectory(target)
        return assets.size
    }
}
