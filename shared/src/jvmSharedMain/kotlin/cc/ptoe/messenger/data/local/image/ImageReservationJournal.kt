/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import cc.ptoe.messenger.data.local.room.ImageJobDao
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.asset.syncAssetRoot
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val reservationJson = Json { encodeDefaults = true }
internal const val IMAGE_RESERVATION_PREFIX = "local_image_reservation:"
private const val RESERVATION_FILE_PREFIX = ".stage-image-reserve-"
private val RESERVATION_NAME = Regex("\\.stage-image-reserve-[A-Za-z0-9_-]{1,128}\\.tmp")

/** Durable job-to-file identity for quota held before a billable provider request. */
@Serializable
internal data class ImageReservationRecord(
    val jobId: String, val fileName: String, val identity: String, val size: Long, val createdAt: Long,
)

/** Caller must share the Room writer transaction and exclusive asset gate with the image service. */
internal class ImageReservationJournal(private val dao: ImageJobDao) {
    internal fun record(jobId: String, root: Path, reservation: Path, now: Long): ImageReservationRecord {
        require(jobId.length == 64 && jobId.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid image job ID" }
        val normalized = root.toAbsolutePath().normalize()
        val name = reservation.fileName.toString()
        require(reservation.parent?.toAbsolutePath()?.normalize() == normalized && name.matches(RESERVATION_NAME)) {
            "Invalid image reservation path"
        }
        val attributes = Files.readAttributes(reservation, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() > 0) { "Invalid image reservation file" }
        val identity = requireNotNull(attributes.fileKey()) { "Image reservation identity is unavailable" }.toString()
        require(identity.isNotBlank() && '\n' !in identity && identity.length <= 1024) { "Invalid image reservation identity" }
        return ImageReservationRecord(jobId, name, identity, attributes.size(), now)
    }

    internal suspend fun insert(record: ImageReservationRecord) {
        dao.write(KeyValueRow(IMAGE_RESERVATION_PREFIX + record.jobId, reservationJson.encodeToString(record)))
    }

    internal suspend fun entries(): List<ImageReservationRecord> = dao.entries(IMAGE_RESERVATION_PREFIX, IMAGE_RESERVATION_PREFIX.length)
        .map { row ->
            val record = runCatching { reservationJson.decodeFromString<ImageReservationRecord>(row.value) }.getOrNull()
                ?: error("Invalid image reservation journal")
            check(row.key == IMAGE_RESERVATION_PREFIX + record.jobId && valid(record)) { "Invalid image reservation journal" }
            record
        }

    internal suspend fun delete(jobId: String) = dao.deleteKey(IMAGE_RESERVATION_PREFIX + jobId)

    /** Offline recovery only: caller must prove every older worker and other asset writer stopped. */
    internal suspend fun reconcile(root: Path, activeJobs: Set<String>): Int {
        val directory = root.toAbsolutePath().normalize()
        val entries = entries()
        val journalNames = entries.map { it.fileName }.toMutableSet()
        withSecureDirectory(root) { assets ->
            for (entry in assets) {
                val fileName = entry.fileName.toString()
                if (!fileName.startsWith(RESERVATION_FILE_PREFIX)) continue
                check(fileName.matches(RESERVATION_NAME) && fileName in journalNames) {
                    "Unknown image reservation requires recovery"
                }
            }
        }
        for (record in entries) {
            check(record.jobId !in activeJobs) { "An active image job still owns its reservation" }
            val status = dao.read(record.jobId).status
            check(status !in setOf(ImageJobStatus.queued, ImageJobStatus.running)) { "Image job is still active" }
            val existing = withSecureDirectory(root) { assets ->
                runCatching {
                    assets.getFileAttributeView(root.fileSystem.getPath(record.fileName),
                        java.nio.file.attribute.BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
                }.getOrNull()
            }
            if (existing != null) {
                verify(directory, record)
                withSecureDirectory(root) { assets ->
                    val attributes = assets.getFileAttributeView(root.fileSystem.getPath(record.fileName),
                        java.nio.file.attribute.BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
                    check(attributes.fileKey().toString() == record.identity && attributes.size() == record.size) {
                        "Image reservation identity changed"
                    }
                    assets.deleteFile(root.fileSystem.getPath(record.fileName))
                }
            }
            delete(record.jobId)
            journalNames.remove(record.fileName)
        }
        if (entries.isNotEmpty()) syncAssetRoot(root)
        return entries.size
    }

    private fun <T> withSecureDirectory(root: Path, block: (SecureDirectoryStream<Path>) -> T): T {
        val directory = root.toAbsolutePath().normalize()
        val parent = requireNotNull(directory.parent)
        val name = requireNotNull(directory.fileName)
        Files.newDirectoryStream(parent).use { parentStream ->
            val secure = parentStream as? SecureDirectoryStream<Path>
                ?: error("Secure image reservation recovery is unavailable on this platform")
            return secure.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use(block)
        }
    }

    /** Only exact, private regular files bound to the durable record may be removed. */
    internal fun verify(root: Path, record: ImageReservationRecord): Path {
        require(valid(record)) { "Invalid image reservation journal" }
        val file = root.toAbsolutePath().normalize().resolve(record.fileName)
        val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(attributes.isRegularFile && !attributes.isSymbolicLink && attributes.fileKey().toString() == record.identity &&
            attributes.size() == record.size) { "Image reservation identity changed" }
        return file
    }

    private fun valid(record: ImageReservationRecord): Boolean =
        record.jobId.length == 64 && record.jobId.all { it in '0'..'9' || it in 'a'..'f' } &&
            record.fileName.matches(RESERVATION_NAME) && record.identity.isNotBlank() && '\n' !in record.identity &&
            record.identity.length <= 1024 && record.size in 1..4L * 20L * 1024 * 1024 && record.createdAt >= 0
}
