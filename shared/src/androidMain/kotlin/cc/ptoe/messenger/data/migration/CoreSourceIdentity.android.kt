/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import android.system.Os
import java.nio.file.Path

internal actual fun coreSourceLinkCount(path: Path): Long = Os.lstat(path.toString()).st_nlink
