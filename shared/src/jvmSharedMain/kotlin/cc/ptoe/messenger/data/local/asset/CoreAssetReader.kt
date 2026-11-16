/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class CoreAssetOwner(val ownerType: String, val ownerId: String)

/** Matches `local_asset:<sha256>` JSON from the Rust Core. */
@Serializable
internal data class CoreAssetMetadata(
    val id: String, val sha256: String, val mimeType: String, val size: Long,
    val width: Long? = null, val height: Long? = null, val createdAt: Long,
    val references: List<CoreAssetOwner>, val unreferencedAt: Long? = null,
)

/**
 * Read-only compatibility helper. The caller must hold the future shared domain/asset gate while
 * loading metadata and reading bytes, and supply the trusted, private Core `assets` directory.
 * No imports, reference release, GC, or package operations are implemented here.
 */
internal class CoreAssetReader(private val root: Path, private val forcePortable: Boolean = false) {
    fun read(id: String, metadataJson: String): ByteArray {
        require(validAssetId(id)) { "Invalid asset ID" }
        val asset = Json { ignoreUnknownKeys = true }.decodeFromString<CoreAssetMetadata>(metadataJson)
        require(asset.id == id && asset.sha256 == id && asset.size in 0..MAX_BYTES) { "Invalid asset metadata" }
        require(validMime(asset.mimeType) && asset.references.size <= 10_000 && asset.references.all(::validOwner)) { "Invalid asset metadata" }
        val directory = root.toAbsolutePath().normalize()
        val parent = requireNotNull(directory.parent) { "Invalid asset directory" }
        val name = requireNotNull(directory.fileName) { "Invalid asset directory" }
        if (!forcePortable) Files.newDirectoryStream(parent).use { stream ->
            val secure = stream as? SecureDirectoryStream<Path>
            if (secure != null) secure.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { assets ->
                val relative = root.fileSystem.getPath(id)
                val attrs = assets.getFileAttributeView(relative, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
                check(attrs.isRegularFile && !attrs.isSymbolicLink && attrs.size() == asset.size) { "Asset file integrity failure" }
                assets.newByteChannel(relative, readOptions).use { return verifiedBytes(it, asset) }
            }
        }
        // Windows/Android providers may not expose SecureDirectoryStream. The private root and
        // exclusive asset gate are mandatory: compare non-null file identities before and after a
        // NOFOLLOW open as well as the bounded SHA-256 contents. Missing identity fails closed.
        val rootBefore = attributes(directory)
        check(rootBefore.isDirectory && !rootBefore.isSymbolicLink && rootBefore.fileKey() != null) { "Unverifiable asset directory" }
        val file = directory.resolve(id)
        val before = attributes(file)
        check(before.isRegularFile && !before.isSymbolicLink && before.size() == asset.size && before.fileKey() != null) { "Asset file integrity failure" }
        val bytes = Files.newByteChannel(file, readOptions).use { verifiedBytes(it, asset) }
        val after = attributes(file)
        val rootAfter = attributes(directory)
        check(after.isRegularFile && !after.isSymbolicLink && after.size() == asset.size && after.fileKey() == before.fileKey() &&
            rootAfter.isDirectory && !rootAfter.isSymbolicLink && rootAfter.fileKey() == rootBefore.fileKey()) { "Asset changed during read" }
        return bytes
    }

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private fun verifiedBytes(channel: SeekableByteChannel, asset: CoreAssetMetadata): ByteArray {
        check(channel.size() == asset.size) { "Asset file integrity failure" }
        val result = ByteArray(asset.size.toInt())
        val buffer = ByteBuffer.wrap(result)
        while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "Asset file truncated" }
        check(channel.read(ByteBuffer.allocate(1)) == -1) { "Asset file exceeds declared size" }
        val digest = MessageDigest.getInstance("SHA-256").digest(result).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(digest == asset.id) { "Asset hash mismatch" }
        return result
    }

    companion object {
        private val readOptions = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        internal const val MAX_BYTES = 20L * 1024 * 1024
        internal fun validAssetId(id: String) = id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }
        internal fun validMime(mime: String): Boolean {
            val parts = mime.split('/')
            return mime.length <= 128 && parts.size == 2 && parts.all(String::isNotEmpty) &&
                mime.all { it in 'a'..'z' || it in '0'..'9' || it in "/!#$&^_.+-" }
        }
        internal fun validOwner(owner: CoreAssetOwner) = listOf(owner.ownerType, owner.ownerId).all {
            it.isNotEmpty() && it.encodeToByteArray().size <= 256 && it.none(Char::isISOControl)
        }
    }
}

/** Pure planning only: the caller must verify bytes and commit this with domain changes atomically. */
internal fun CoreAssetMetadata.retainOwner(owner: CoreAssetOwner): CoreAssetMetadata {
    require(CoreAssetReader.validOwner(owner)) { "Invalid asset owner" }
    if (owner in references) return this
    require(references.size < 10_000) { "Asset reference limit exceeded" }
    return copy(references = references + owner, unreferencedAt = null)
}
