/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import java.nio.file.Path

internal actual val roomPublicationUsesReceipt = true
internal actual fun requireRoomPublicationParent(parent: Path) = AndroidRoomFiles.syncPrivateDirectory(parent)
internal actual fun syncRoomPublicationDirectory(parent: Path) = AndroidRoomFiles.syncPrivateDirectory(parent)
private fun receipt(target: Path): Path = target.resolveSibling(".${target.fileName}.room-publication")

internal actual suspend fun publishRoomDatabase(
    staged: Path, target: Path, publicationId: String?, syncParent: (Path) -> Unit,
    verifyRoom: suspend (Path) -> Unit,
) {
    AndroidRoomPublication.publish(staged, target, receipt(target), requireNotNull(publicationId), verifyRoom)
    syncParent(requireNotNull(target.parent))
}
internal actual suspend fun verifyPublishedRoomDatabase(
    target: Path, syncParent: (Path) -> Unit, verifyRoom: suspend (Path) -> Unit,
) {
    AndroidRoomPublication.verifyExisting(target, receipt(target), verifyRoom)
    syncParent(requireNotNull(target.parent))
}
