/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import cc.ptoe.messenger.data.local.room.CoreAssetMetadataDao
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Must exclude all Core, learning, artifact and package writers while the block executes. */
internal interface CoreAssetGate {
    suspend fun <T> withExclusiveAssets(block: suspend () -> T): T

    /** Caller must first prove all asset users have stopped and hold exclusive store ownership. */
    suspend fun <T> withRecoveryAssets(block: suspend () -> T): T = withExclusiveAssets(block)
}

/**
 * Unconnected asset migration service. Image imports require bounded platform decoding; package import remains unimplemented.
 * The caller supplies a private directory and a gate shared by every resource/domain mutation.
 */
internal class CoreAssetService(private val assetRoot: Path, private val dao: CoreAssetMetadataDao, private val gate: CoreAssetGate) {
    internal val root: Path = assetRoot.toAbsolutePath().normalize()
    private val json = Json { encodeDefaults = true }

    /** Caller must use this same gate for learning, artifacts and package mutations. */
    internal suspend fun <T> withExclusive(block: suspend () -> T): T = gate.withExclusiveAssets(block)
    /** Only offline recovery with a verified stopped-owner proof may use this path. */
    internal suspend fun <T> withRecovery(block: suspend () -> T): T = gate.withRecoveryAssets(block)
    internal fun verifyWithGateHeld(id: String, raw: String): ByteArray = CoreAssetReader(root).read(id, raw)

    suspend fun read(id: String): ByteArray = gate.withExclusiveAssets {
        require(CoreAssetReader.validAssetId(id)) { "Invalid asset ID" }
        val metadata = dao.metadata("local_asset:$id") ?: error("Asset not found")
        CoreAssetReader(root).read(id, metadata)
    }

    suspend fun metadata(id: String): CoreAssetMetadata = gate.withExclusiveAssets {
        require(CoreAssetReader.validAssetId(id)) { "Invalid asset ID" }
        val raw = dao.metadata("local_asset:$id") ?: error("Asset not found")
        val item = json.decodeFromString<CoreAssetMetadata>(raw)
        require(item.id == id && item.sha256 == id && CoreAssetReader.validMime(item.mimeType) && item.size in 0..CoreAssetReader.MAX_BYTES &&
            item.references.size <= 10_000 && item.references.all(CoreAssetReader::validOwner)) { "Invalid asset metadata" }
        item
    }

    suspend fun retain(id: String, owner: CoreAssetOwner): CoreAssetMetadata = gate.withExclusiveAssets {
        require(CoreAssetReader.validAssetId(id) && CoreAssetReader.validOwner(owner)) { "Invalid asset or owner" }
        val raw = dao.metadata("local_asset:$id") ?: error("Asset not found")
        CoreAssetReader(root).read(id, raw)
        dao.retainExisting(id, owner)
    }

    suspend fun release(id: String, owner: CoreAssetOwner, now: Long): CoreAssetMetadata = gate.withExclusiveAssets {
        dao.releaseOwner(id, owner, now)
    }

    /** Physical GC is disabled until DB commit and directory durability are verified together. */
    suspend fun collect(now: Long): CoreAssetGcResult = gate.withExclusiveAssets {
        error("Asset GC is unavailable until durable SQLite and directory ordering is verified")
    }

    /** Inspect old unowned files for offline recovery; never remove files. */
    suspend fun auditOrphans(now: Long): CoreAssetOrphanAudit = gate.withExclusiveAssets {
        val protected = AssetReferenceAudit(dao.referenceSnapshot()).protectedAssetIds()
        val directory = root.toAbsolutePath().normalize()
        val parent = requireNotNull(directory.parent)
        val name = requireNotNull(directory.fileName)
        Files.newDirectoryStream(parent).use { parentStream ->
            val secure = parentStream as? java.nio.file.SecureDirectoryStream<Path>
                ?: error("Secure asset deletion is unavailable on this platform")
            secure.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { assets ->
                val candidates = mutableListOf<String>()
                val failed = mutableListOf<String>()
                for (entry in assets) {
                    val id = entry.fileName.toString()
                    val staged = id.startsWith(".stage-")
                    if (!staged && (!CoreAssetReader.validAssetId(id) || id in protected || dao.metadata("local_asset:$id") != null)) continue
                    val relative = root.fileSystem.getPath(id)
                    val attrs = assets.getFileAttributeView(relative, java.nio.file.attribute.BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
                    val modified = attrs.lastModifiedTime().toMillis()
                    if (!attrs.isRegularFile || attrs.isSymbolicLink || now < Long.MIN_VALUE + 86_400_000 || modified > now - 86_400_000) continue
                    // The `.stage-` namespace is reserved for incomplete writes, not owned data.
                    if (staged) { candidates.add(id); continue }
                    if (attrs.size() !in 1..CoreAssetReader.MAX_BYTES) { failed.add(id); continue }
                    // Corrupt or changing candidates are left untouched and reported.
                    val metadata = CoreAssetMetadata(id, id, "application/octet-stream", attrs.size(), createdAt = 0, references = emptyList())
                    val valid = try { CoreAssetReader(root).read(id, json.encodeToString(metadata)); true }
                        catch (_: java.io.IOException) { false }
                        catch (_: IllegalStateException) { false }
                        catch (_: IllegalArgumentException) { false }
                    if (!valid) { failed.add(id); continue }
                    // The gate prevents any compliant writer from creating references here.
                    check(dao.metadata("local_asset:$id") == null && id !in AssetReferenceAudit(dao.referenceSnapshot()).protectedAssetIds()) { "Asset became owned" }
                    candidates.add(id)
                }
                CoreAssetOrphanAudit(candidates, failed)
            }
        }
    }

    suspend fun importFile(bytes: ByteArray, mime: String, owner: CoreAssetOwner, now: Long): CoreAssetMetadata =
        gate.withExclusiveAssets {
            require(bytes.isNotEmpty() && bytes.size.toLong() <= CoreAssetReader.MAX_BYTES) { "Invalid asset size" }
            require(CoreAssetReader.validMime(mime) && CoreAssetReader.validOwner(owner)) {
                "Unsupported asset MIME or owner"
            }
            val dimensions = if (mime.startsWith("image/")) validateOwnedImage(bytes, mime) else null
            val id = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val proposed = CoreAssetMetadata(id, id, mime, bytes.size.toLong(), width = dimensions?.width, height = dimensions?.height, createdAt = now, references = emptyList())
            stageVerifiedFile(proposed, bytes)
            // Persist the directory entry before Room can commit its metadata. Unsupported
            // directory fsync must fail closed; linked bytes then remain an orphan.
            syncAssetRoot(root)
            // Metadata update is atomic. If it fails, leave the hash file as an orphan; never
            // remove a file that another owner or an interrupted import may already share.
            dao.retainOrInsert(proposed, owner)
        }
    /** Fail before any paid request if private, durable asset storage cannot be established. */
    internal fun requireDurableStorageWithGateHeld() {
        requirePrivateAssetRoot(root)
    }

    /** Caller holds the shared asset gate; fail closed if directory fsync is unsupported. */
    internal fun syncDirectoryWithGateHeld() = syncAssetRoot(root)

    /** Physically reserve up to four maximum-size outputs before sending a billable request. */
    internal fun reserveImageQuotaWithGateHeld(): Path {
        val reservationBytes = 4L * CoreAssetReader.MAX_BYTES
        var used = 0L
        Files.newDirectoryStream(root).use { entries ->
            for (path in entries) {
                if (CoreAssetReader.validAssetId(path.fileName.toString()) || path.fileName.toString().startsWith(".stage-")) {
                    val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    check(attributes.isRegularFile && !attributes.isSymbolicLink) { "Invalid asset catalog file" }
                    used = Math.addExact(used, attributes.size())
                }
            }
        }
        require(used <= 512L * 1024 * 1024 - reservationBytes && assetUsableSpace(root) >= reservationBytes) { "Insufficient asset storage for image generation" }
        val reservation = Files.createTempFile(root, ".stage-image-reserve-", ".tmp")
        try {
            FileChannel.open(reservation, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.allocate(1024 * 1024)
                var remaining = reservationBytes
                while (remaining > 0) {
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                    while (buffer.hasRemaining()) check(channel.write(buffer) > 0) { "Image reservation made no progress" }
                    remaining -= buffer.limit()
                }
                channel.force(true)
            }
            syncDirectoryWithGateHeld()
            return reservation
        } catch (failure: Throwable) {
            Files.deleteIfExists(reservation)
            throw failure
        }
    }

    internal fun releaseImageQuotaWithGateHeld(reservation: Path) {
        require(reservation.parent == root && reservation.fileName.toString().startsWith(".stage-image-reserve-")) { "Invalid image reservation" }
        Files.deleteIfExists(reservation)
        syncDirectoryWithGateHeld()
    }

    /** Caller holds the shared gate and has validated package content. Leaves orphan bytes on failure. */
    internal fun stageVerifiedFile(proposed: CoreAssetMetadata, bytes: ByteArray) {
        val id = proposed.id
        require(CoreAssetReader.validAssetId(id) && proposed.sha256 == id && bytes.size.toLong() == proposed.size &&
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) } == id) { "Invalid package asset" }
        requirePrivateAssetRoot(root)
        val file = root.resolve(id)
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            CoreAssetReader(root).read(id, json.encodeToString(proposed))
        } else {
            var used = 0L
            Files.newDirectoryStream(root).use { entries ->
                for (path in entries) {
                    if (CoreAssetReader.validAssetId(path.fileName.toString()) || path.fileName.toString().startsWith(".stage-")) {
                        val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        check(attrs.isRegularFile && !attrs.isSymbolicLink) { "Invalid asset catalog file" }
                        used = Math.addExact(used, attrs.size())
                    }
                }
            }
            require(used <= 512L * 1024 * 1024 - bytes.size) { "Asset storage quota exceeded" }
            val stage = Files.createTempFile(root, ".stage-", ".tmp")
            try {
                FileChannel.open(stage, StandardOpenOption.WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) check(channel.write(buffer) > 0) { "Asset staging made no progress" }
                    channel.force(true)
                }
                if (!publishAssetExclusive(stage, file)) {
                    // Another publisher won: accept only exact, non-symlinked matching bytes.
                    CoreAssetReader(root).read(id, json.encodeToString(proposed))
                }
            } finally { Files.deleteIfExists(stage) }
        }
    }

}

/** Names in failedOrphans remain on disk and require explicit recovery. */
internal data class CoreAssetGcResult(val removed: List<String>, val failedOrphans: List<String>)
internal data class CoreAssetOrphanAudit(val candidates: List<String>, val invalid: List<String>)
