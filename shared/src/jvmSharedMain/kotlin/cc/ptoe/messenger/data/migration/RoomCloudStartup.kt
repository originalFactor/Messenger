/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import cc.ptoe.messenger.data.cloud.CloudImageRehydrator
import cc.ptoe.messenger.data.cloud.RoomAvatarCache
import cc.ptoe.messenger.data.cloud.RoomCloudFacade
import cc.ptoe.messenger.data.cloud.RoomCloudHttpClient
import cc.ptoe.messenger.data.cloud.defaultCloudServerUrl
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.repository.CloudFacade
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Isolated composition; not installed in either app. Ownership must exclude all Core writers
 * and other Room owners for the entire callback. The host must stop and join all non-cloud
 * Room users before returning or throwing from [useRoom]; the cloud facade drains its own work.
 */
internal object RoomCloudStartup {
    /** Fresh installations only; existing Core stores must use [withLeasedCoreCloud]. */
    suspend fun <T> withRoomCloud(
        source: Path,
        destination: Path,
        ownership: RoomStoreOwnership,
        shutdown: RoomManagedShutdown,
        openRoom: (Path) -> LocalDatabase,
        preferences: AppPreferences,
        avatars: RoomAvatarCache,
        images: CloudImageRehydrator,
        defaultServer: String = defaultCloudServerUrl,
        useRoom: suspend (LocalDatabase, CloudFacade) -> T,
    ): T = RoomStoreStartup.withRoomStore(source, destination, ownership, shutdown, openRoom) { db ->
        useManagedCloud(db, shutdown, preferences, avatars, images, defaultServer, useRoom)
    }

    /** Existing Core stores require the source lease and paired asset publication. */
    suspend fun <T> withLeasedCoreCloud(
        source: Path,
        destination: Path,
        sourceAssets: Path,
        destinationAssets: Path,
        ownership: RoomStoreOwnership,
        shutdown: RoomManagedShutdown,
        openRoom: (Path) -> LocalDatabase,
        preferences: AppPreferences,
        avatars: RoomAvatarCache,
        images: CloudImageRehydrator,
        defaultServer: String = defaultCloudServerUrl,
        useRoom: suspend (LocalDatabase, CloudFacade) -> T,
    ): T = RoomStoreStartup.withLeasedCoreStore(source, destination, sourceAssets, destinationAssets, ownership, shutdown, openRoom) { db ->
        useManagedCloud(db, shutdown, preferences, avatars, images, defaultServer, useRoom)
    }

    private suspend fun <T> useManagedCloud(
        db: LocalDatabase,
        shutdown: RoomManagedShutdown,
        preferences: AppPreferences,
        avatars: RoomAvatarCache,
        images: CloudImageRehydrator,
        defaultServer: String,
        useRoom: suspend (LocalDatabase, CloudFacade) -> T,
    ): T {
        val transport = RoomCloudHttpClient()
        try {
            withContext(NonCancellable) { shutdown.registerStop { transport.close() } }
        } catch (failure: Throwable) {
            try { transport.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        val cloud = RoomCloudFacade.open(db, preferences, transport, avatars, images, defaultServer,
            onCreated = { facade -> withContext(NonCancellable) { shutdown.registerStop { facade.shutdown() } } })
        return useRoom(db, cloud)
    }
}
