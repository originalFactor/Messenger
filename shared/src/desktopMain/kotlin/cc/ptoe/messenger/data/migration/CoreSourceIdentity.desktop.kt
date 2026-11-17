/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal actual fun coreSourceLinkCount(path: Path): Long =
    (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toLong()
