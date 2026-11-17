/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.di

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.cloud.CloudImageRehydrator
import cc.ptoe.messenger.data.cloud.RoomAvatarCache
import cc.ptoe.messenger.data.cloud.RoomCloudFacade
import cc.ptoe.messenger.data.cloud.RoomCloudHttpClient
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.createMessengerDataStore
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.repository.room.row
import cc.ptoe.messenger.domain.model.Agent
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath

/** Minimal Kotlin/Room production host. Unsupported capabilities stay explicitly fail-closed. */
object KotlinAppBackendFactory {
    private const val FRESH_KEY = "__messenger_room_fresh_v1"
    private const val PUBLICATION_KEY = "__messenger_room_publication_id_v1"

    fun create(appDirs: AppDirs, openRoom: (Path) -> LocalDatabase): AppBackend {
        val databasePath = appDirs.filesDir.resolve("messenger-room.db").toString().let(::javaPath)
        val preferences = createMessengerDataStore(appDirs.filesDir)
        val appPreferences = AppPreferences(preferences)
        val themePreferences = ThemePreferences(preferences)
        val db = openRoom(databasePath)
        initialize(db)
        val transport = RoomCloudHttpClient()
        val cloud = runSuspend {
            RoomCloudFacade.open(db, appPreferences, transport,
                RoomAvatarCache(appDirs.filesDir.resolve("cloud_avatars").toString().let(::okioPath)),
                CloudImageRehydrator(appDirs.filesDir.resolve("chat_images").toString().let(::javaPath)))
        }
        val stores = LocalStores(cloud, appPreferences, themePreferences)
        val slice = runSuspend {
            val shutdown = cc.ptoe.messenger.data.migration.RoomManagedShutdown { }
            shutdown.enter()
            cc.ptoe.messenger.data.repository.room.KotlinRoomBackendSlice.attach(
                db = db,
                shutdown = shutdown,
                appPreferences = appPreferences,
                themePreferences = themePreferences,
                assetRoot = appDirs.filesDir.resolve("assets").toString().let(::javaPath),
                avatarRoot = appDirs.filesDir.resolve("cloud_avatars").toString().let(::javaPath),
                chatImageRoot = appDirs.filesDir.resolve("chat_images").toString().let(::javaPath),
                clearAll = cc.ptoe.messenger.data.repository.room.IsolatedRoomClearAllConfig {
                    cc.ptoe.messenger.data.migration.RoomClearAll(
                        db = db,
                        ownership = object : cc.ptoe.messenger.data.migration.RoomClearAllOwnership {
                            override suspend fun <T> withStoppedExclusiveOwner(block: suspend () -> T): T = block()
                        },
                        external = object : cc.ptoe.messenger.data.migration.RoomClearAllExternal {
                            override suspend fun checkReady() {}
                            override suspend fun clearAndVerify() {}
                        },
                    ).clear()
                },
            )
        }
        val backend = slice.asAppBackend()
        return AppBackend(stores, backend.coreBridge, backend.providers, backend.models, backend.agents,
            backend.conversations, backend.projects, backend.messages, backend.currentAgent, backend.learning,
            shutdown = { backend.shutdown(); cloud.shutdown(); transport.close(); db.close() },
            clearLocalData = backend.clearLocalData,
            artifactUiHost = backend.artifactUiHost,
            platformToolsEnabled = backend.platformToolsEnabled,
            turnFailureNotices = backend.turnFailureNotices,
            mcpOwner = backend.mcpOwner,
            mcpExecutionEnabled = backend.mcpExecutionEnabled)
    }

    private fun initialize(db: LocalDatabase) = runSuspend {
        withContext(NonCancellable) {
            db.useWriterConnection { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val rows = db.rows()
                    if (rows.getValue(FRESH_KEY)?.value == "complete") {
                        check(rows.defaultAgents().size == 1) { "Room database is missing exactly one default Agent" }
                        return@withTransaction
                    }
                    val now = System.currentTimeMillis()
                    val seed = Agent(id = UUID.randomUUID().toString(), name = Agent.DEFAULT_AGENT_NAME,
                        systemPrompt = Agent.DEFAULT_AGENT_PROMPT, description = Agent.DEFAULT_AGENT_DESCRIPTION,
                        defaultModelId = null, temperature = 0.7f, topP = 1f, maxTokens = null,
                        isDefault = true, toolsEnabled = true, createdAt = now, updatedAt = now)
                    rows.ensureDefaultAgent(seed.row())
                    db.cloudSync().saveAgent(Agent.builtinTitleAgent(now).row())
                    rows.upsertValues(listOf(
                        KeyValueRow(FRESH_KEY, "complete"),
                        KeyValueRow(PUBLICATION_KEY, UUID.randomUUID().toString().replace("-", "")),
                    ))
                    check(rows.defaultAgents().size == 1) { "Room database is missing exactly one default Agent" }
                }
            }
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
    private fun javaPath(value: String): Path = java.nio.file.Paths.get(value).toAbsolutePath().normalize()
    private fun okioPath(value: String) = value.toPath()
}
