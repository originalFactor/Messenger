/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import java.nio.file.Path

internal const val ROOM_PUBLICATION_ID_KEY = "__messenger_room_publication_id_v1"

/** Used only by the unconnected Room startup coordinator while its lifetime gate is held. */
internal expect val roomPublicationUsesReceipt: Boolean
internal expect fun requireRoomPublicationParent(parent: Path)
internal expect fun syncRoomPublicationDirectory(parent: Path)
internal expect suspend fun publishRoomDatabase(
    staged: Path, target: Path, publicationId: String?, syncParent: (Path) -> Unit,
    verifyRoom: suspend (Path) -> Unit,
)
internal expect suspend fun verifyPublishedRoomDatabase(
    target: Path, syncParent: (Path) -> Unit, verifyRoom: suspend (Path) -> Unit,
)
