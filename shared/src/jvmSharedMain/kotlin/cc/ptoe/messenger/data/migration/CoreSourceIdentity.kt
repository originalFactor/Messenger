/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import java.nio.file.Path

/** Fail closed when the platform cannot prove the link count. */
internal expect fun coreSourceLinkCount(path: Path): Long
