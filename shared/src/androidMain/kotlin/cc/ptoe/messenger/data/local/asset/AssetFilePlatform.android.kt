/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import android.os.StatFs
import android.system.ErrnoException
import android.system.OsConstants
import cc.ptoe.messenger.data.migration.AndroidRoomFiles
import java.nio.file.Path

internal actual fun requirePrivateAssetRoot(root: Path) = AndroidRoomFiles.syncPrivateDirectory(root)
internal actual fun syncAssetRoot(root: Path) = AndroidRoomFiles.syncPrivateDirectory(root)
internal actual fun assetUsableSpace(root: Path): Long = StatFs(root.toString()).availableBytes

internal actual fun publishAssetExclusive(staged: Path, destination: Path): Boolean = try {
    AndroidRoomFiles.copyExclusive(staged, destination)
    true
} catch (error: ErrnoException) {
    if (error.errno == OsConstants.EEXIST) false else throw error
}
