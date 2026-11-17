/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

internal actual fun requirePrivateAssetRoot(root: Path) {
    require(Files.isDirectory(root, NOFOLLOW_LINKS) && Files.getFileStore(root).supportsFileAttributeView("posix")) { "Private asset storage is not verified" }
    val denied = setOf(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE)
    require(Files.getPosixFilePermissions(root, NOFOLLOW_LINKS).none { it in denied }) { "Asset storage must be private" }
    Files.newDirectoryStream(requireNotNull(root.toAbsolutePath().normalize().parent)).use {
        check(it is SecureDirectoryStream<*>) { "Secure asset storage unavailable" }
    }
    syncAssetRoot(root)
}

internal actual fun syncAssetRoot(root: Path) = FileChannel.open(root, StandardOpenOption.READ).use { it.force(true) }
internal actual fun assetUsableSpace(root: Path): Long = Files.getFileStore(root).usableSpace
internal actual fun publishAssetExclusive(staged: Path, destination: Path): Boolean = try {
    Files.createLink(destination, staged)
    true
} catch (_: FileAlreadyExistsException) { false }
