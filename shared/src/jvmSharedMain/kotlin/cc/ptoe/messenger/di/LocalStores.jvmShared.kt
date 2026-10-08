/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.di

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.CloudSyncRepository
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.MessengerDatabase
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.createMessengerDataStore
import cc.ptoe.messenger.data.repository.AgentRepositoryImpl
import cc.ptoe.messenger.data.repository.ConversationRepositoryImpl
import cc.ptoe.messenger.data.repository.MessageRepositoryImpl
import cc.ptoe.messenger.data.repository.ModelRepositoryImpl
import cc.ptoe.messenger.data.repository.ProviderRepositoryImpl
import cc.ptoe.messenger.data.util.FileKit
import cc.ptoe.messenger.data.util.ioDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Android/Desktop stores: one Room database plus the on-disk preferences
 * DataStore, held behind the Room-backed [CloudSyncRepository].
 */
internal class RoomLocalStores(
    cloud: CloudSyncRepository,
    appPreferences: AppPreferences,
    themePreferences: ThemePreferences,
) : LocalStores(cloud, appPreferences, themePreferences) {
    val database: MessengerDatabase = cloud.database
}

actual fun createLocalStores(appDirs: AppDirs): LocalStores {
    val database = platformDatabaseBuilder(appDirs)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(ioDispatcher)
        .addMigrations(
            MessengerDatabase.MIGRATION_13_14,
            MessengerDatabase.MIGRATION_14_15,
            MessengerDatabase.MIGRATION_15_16,
            MessengerDatabase.MIGRATION_16_17,
            MessengerDatabase.MIGRATION_17_18,
            MessengerDatabase.MIGRATION_18_19,
            MessengerDatabase.MIGRATION_19_20
        )
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    val dataStore = createMessengerDataStore(appDirs.filesDir)
    val appPreferences = AppPreferences(dataStore)
    val themePreferences = ThemePreferences(dataStore)
    val cloud = CloudSyncRepository(
        appPreferences = appPreferences,
        database = database,
        filesDir = appDirs.filesDir,
        localDataMutex = Mutex(),
    )
    return RoomLocalStores(cloud, appPreferences, themePreferences)
}

/**
 * Room-backed repositories (Android/Desktop). The builtin provider and the
 * builtin title agent are excluded from cloud sync in the change callbacks —
 * they are per-device rows that each device seeds for itself.
 */
actual fun createRoomRepositories(
    stores: LocalStores,
    chatImageStore: ChatImageStore,
    hooks: LocalChangeHooks,
): RoomRepositories {
    val database = (stores as RoomLocalStores).database
    return RoomRepositories(
        providers = ProviderRepositoryImpl(database.providerDao()) { id, deleted ->
            hooks.onProviderChanged(id, deleted)
        },
        models = ModelRepositoryImpl(database.modelDao()) { providerId, _ ->
            hooks.onModelChanged(providerId)
        },
        agents = AgentRepositoryImpl(
            agentDao = database.agentDao(),
            onChanged = { previous, current -> hooks.onAgentChanged(previous, current) },
        ),
        conversations = ConversationRepositoryImpl(database.conversationDao()) { id, deleted ->
            hooks.onConversationChanged(id, deleted)
        },
        messages = MessageRepositoryImpl(database.messageDao()) { conversationId ->
            hooks.onMessagesChanged(conversationId)
        },
    )
}

actual suspend fun clearLocalData(appDirs: AppDirs, stores: LocalStores) =
    withContext(ioDispatcher) {
        val room = stores as RoomLocalStores
        val cloud = room.cloud as CloudSyncRepository
        cloud.cancelPendingLocalSync()
        room.appPreferences.userAvatar.first()?.let { avatarPath ->
            FileKit.delete(avatarPath)
        }
        room.database.providerDao().deleteAll()
        room.database.modelDao().deleteAll()
        room.database.agentDao().deleteAll()
        room.database.conversationDao().deleteAll()
        room.database.messageDao().deleteAll()
        room.appPreferences.clearAll()
        FileKit.deleteRecursively(appDirs.filesDir)
        FileKit.deleteRecursively(appDirs.cacheDir)
        cloud.ensureBuiltinTitleAgent()
    }
