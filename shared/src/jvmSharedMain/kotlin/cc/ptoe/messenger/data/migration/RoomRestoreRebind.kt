/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal const val ROOM_RESTORE_REBIND_RECEIPT_KEY = "__messenger_room_restore_rebind_receipt_v1"
private const val HEADER = "messenger-room-restore-v1:"
private val REBIND_PATTERN = Regex(
    "^messenger-room-restore-v1:([0-9a-f]{32}):([0-9a-f]{64}):([0-9a-f]{64}):([0-9a-f]{64})\\n$"
)

/** Exact source-identity transition bound to a publication and logical Core content hash. */
internal data class RoomRestoreRebindRecord(
    val publicationId: String, val previousIdentity: String, val reboundIdentity: String, val contentHash: String,
) {
    fun text(): String = "$HEADER$publicationId:$previousIdentity:$reboundIdentity:$contentHash\n"
}

/**
 * Platform proof for a restore rebind. It must establish that old and new Core writers, Room
 * users, asset writers and path replacements are excluded throughout finalization and startup.
 * A test stub is not a production proof; no production implementation is installed.
 */
internal fun interface RoomRestoreRebindAuthority {
    suspend fun verify(record: RoomRestoreRebindRecord)
}

/** Durable v2 rebind receipt in the private publication parent; original pair markers remain. */
internal object RoomRestoreRebind {
    internal fun markerFor(target: Path): Path {
        val absolute = target.toAbsolutePath().normalize()
        return absolute.resolveSibling(".${absolute.fileName}.room-restore-rebind-v1")
    }

    internal fun read(target: Path): RoomRestoreRebindRecord? {
        val marker = markerFor(target)
        if (Files.notExists(marker, NOFOLLOW_LINKS)) return null
        val before = Files.readAttributes(marker, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(before.isRegularFile && !before.isSymbolicLink && before.fileKey() != null && before.size() in 1L..256L) {
            "Restore rebind marker is invalid"
        }
        val bytes = FileChannel.open(marker, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(before.size().toInt())
            while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "Incomplete restore rebind marker" }
            check(channel.read(ByteBuffer.allocate(1)) == -1) { "Invalid restore rebind marker" }
            buffer.array()
        }
        val after = Files.readAttributes(marker, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(after.isRegularFile && !after.isSymbolicLink && after.fileKey() == before.fileKey() &&
            after.size() == before.size()) { "Restore rebind marker changed during read" }
        val text = bytes.toString(Charsets.US_ASCII)
        val match = checkNotNull(REBIND_PATTERN.matchEntire(text)) { "Restore rebind marker is incomplete" }
        val record = RoomRestoreRebindRecord(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4])
        check(text == record.text()) { "Restore rebind marker is malformed" }
        return record
    }

    /** Create the durable marker before the Room transaction that consumes its pending preflight. */
    internal fun write(target: Path, record: RoomRestoreRebindRecord) {
        val marker = markerFor(target)
        if (Files.exists(marker, NOFOLLOW_LINKS)) {
            check(read(target) == record) { "Another restore rebind requires recovery" }
            return
        }
        val bytes = ByteBuffer.wrap(record.text().toByteArray(Charsets.US_ASCII))
        FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
            while (bytes.hasRemaining()) check(channel.write(bytes) > 0) { "Restore rebind marker write made no progress" }
            channel.force(true)
        }
        syncRoomPublicationDirectory(requireNotNull(marker.parent))
        check(read(target) == record) { "Restore rebind marker was not durable" }
    }
}
