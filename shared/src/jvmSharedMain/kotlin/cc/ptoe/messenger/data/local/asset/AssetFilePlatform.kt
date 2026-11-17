/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import java.nio.file.Path

/** All operations require the same exclusive asset gate and a trusted application-private root. */
internal expect fun requirePrivateAssetRoot(root: Path)
internal expect fun syncAssetRoot(root: Path)
internal expect fun assetUsableSpace(root: Path): Long
/** Returns false only if another publisher already created the destination. Never overwrites. */
internal expect fun publishAssetExclusive(staged: Path, destination: Path): Boolean
