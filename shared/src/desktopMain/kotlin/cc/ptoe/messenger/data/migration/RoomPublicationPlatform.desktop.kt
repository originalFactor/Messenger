/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal actual val roomPublicationUsesReceipt = false
internal actual fun requireRoomPublicationParent(parent: Path) = CoreStoreCutover.requirePrivateParent(parent)
internal actual fun syncRoomPublicationDirectory(parent: Path) = FileChannel.open(parent, StandardOpenOption.READ).use { it.force(true) }
internal actual suspend fun publishRoomDatabase(
    staged: Path, target: Path, publicationId: String?, syncParent: (Path) -> Unit,
    verifyRoom: suspend (Path) -> Unit,
) {
    check(publicationId == null)
    Files.createLink(target, staged)
    syncParent(requireNotNull(target.parent))
}
internal actual suspend fun verifyPublishedRoomDatabase(
    target: Path, syncParent: (Path) -> Unit, verifyRoom: suspend (Path) -> Unit,
) {
    verifyRoom(target)
    syncParent(requireNotNull(target.parent))
}
