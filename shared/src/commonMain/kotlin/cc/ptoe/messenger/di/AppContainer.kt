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
import cc.ptoe.messenger.data.cloud.BUILTIN_PROVIDER_ID
import cc.ptoe.messenger.data.cloud.CloudSyncRepository
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.MessengerDatabase
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.createMessengerDataStore
import cc.ptoe.messenger.data.repository.AgentRepositoryImpl
import cc.ptoe.messenger.data.repository.ApiRepositoryImpl
import cc.ptoe.messenger.data.repository.ChatRepositoryImpl
import cc.ptoe.messenger.data.repository.ConversationRepositoryImpl
import cc.ptoe.messenger.data.repository.CurrentAgentRepositoryImpl
import cc.ptoe.messenger.data.repository.MessageRepositoryImpl
import cc.ptoe.messenger.data.repository.ModelRepositoryImpl
import cc.ptoe.messenger.data.repository.ModelsDevRepositoryImpl
import cc.ptoe.messenger.data.repository.ProviderRepositoryImpl
import cc.ptoe.messenger.data.util.FileKit
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ApiRepository
import cc.ptoe.messenger.domain.repository.ChatRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.CurrentAgentRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ModelsDevRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Path

/**
 * Platform storage roots supplied by each app entry point.
 * - Android: `context.filesDir` / `context.cacheDir`
 * - Desktop: `~/.messenger/files` / `~/.messenger/cache`
 */
class AppDirs(
    val filesDir: Path,
    val cacheDir: Path,
)

/**
 * Manual service locator shared by every platform entry point
 * (Android `MessengerApplication`, Desktop `main`). Mirrors the
 * dependency graph that `MessengerApplication` used to assemble,
 * minus Android-only pieces (wear bridge, Coil factory).
 */
class AppContainer(
    val appDirs: AppDirs,
    databaseBuilder: RoomDatabase.Builder<MessengerDatabase>,
    val chatImageStore: ChatImageStore,
) {

    val localDataMutex = Mutex()

    val database: MessengerDatabase = databaseBuilder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(MessengerDatabase.MIGRATION_13_14)
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    private val dataStore = createMessengerDataStore {
        appDirs.filesDir.resolve("datastore")
            .resolve("messenger_preferences.preferences_pb").toString()
    }

    val appPreferences = AppPreferences(dataStore)
    val themePreferences = ThemePreferences(dataStore)

    val chatRepository: ChatRepository = ChatRepositoryImpl()

    val cloudSyncRepository = CloudSyncRepository(
        appPreferences = appPreferences,
        database = database,
        filesDir = appDirs.filesDir,
        localDataMutex = localDataMutex
    )

    val providerRepository: ProviderRepository =
        ProviderRepositoryImpl(database.providerDao()) { id, deleted ->
            // 内置云 AI 服务商不参与云同步（各设备本地自建）。
            if (id != BUILTIN_PROVIDER_ID) {
                cloudSyncRepository.requestLocalChange("provider", id, deleted)
            }
        }

    val modelRepository: ModelRepository =
        ModelRepositoryImpl(database.modelDao()) { providerId, _ ->
            if (providerId != BUILTIN_PROVIDER_ID) {
                cloudSyncRepository.requestLocalChange("provider", providerId)
            }
        }

    val agentRepository: AgentRepository = AgentRepositoryImpl(
        agentDao = database.agentDao(),
        onChanged = { previous, current ->
            cloudSyncRepository.requestAgentAvatarChange(previous, current)
            current?.let { cloudSyncRepository.requestLocalChange("agent", it.id) }
                ?: previous?.let { cloudSyncRepository.requestLocalChange("agent", it.id, deleted = true) }
        },
        avatarDirectory = appDirs.filesDir.resolve("agent_avatars")
    )

    val conversationRepository: ConversationRepository =
        ConversationRepositoryImpl(database.conversationDao()) { id, deleted ->
            cloudSyncRepository.requestLocalChange("conversation", id, deleted)
        }

    val messageRepository: MessageRepository =
        MessageRepositoryImpl(database.messageDao()) { conversationId ->
            cloudSyncRepository.requestLocalChange("conversation", conversationId)
        }

    val modelsDevRepository: ModelsDevRepository =
        ModelsDevRepositoryImpl(appDirs.filesDir)

    val apiRepository: ApiRepository = ApiRepositoryImpl(modelsDevRepository)

    val currentAgentRepository: CurrentAgentRepository =
        CurrentAgentRepositoryImpl(appPreferences, agentRepository)

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Kick off initial cloud refresh / default-Agent seeding (was MessengerApplication). */
    fun initializeLocalAndCloudData() {
        applicationScope.launch {
            if (appPreferences.cloudSession.first() != null) {
                runCatching {
                    cloudSyncRepository.refreshUser()
                    cloudSyncRepository.sync()
                    cloudSyncRepository.requestLocalSync()
                }
            }
            createDefaultAgentIfNeeded()
        }
    }

    suspend fun clearAllDataAndReinit() = withContext(Dispatchers.IO) {
        cloudSyncRepository.cancelPendingLocalSync()
        localDataMutex.withLock {
            appPreferences.userAvatar.first()?.let { avatarPath ->
                FileKit.delete(avatarPath)
            }
            database.providerDao().deleteAll()
            database.modelDao().deleteAll()
            database.agentDao().deleteAll()
            database.conversationDao().deleteAll()
            database.messageDao().deleteAll()
            appPreferences.clearAll()
            FileKit.deleteRecursively(appDirs.filesDir)
            FileKit.deleteRecursively(appDirs.cacheDir)
            createDefaultAgentIfNeededLocked()
        }
    }

    private suspend fun createDefaultAgentIfNeeded() {
        localDataMutex.withLock {
            createDefaultAgentIfNeededLocked()
        }
    }

    private suspend fun createDefaultAgentIfNeededLocked() {
        if (appPreferences.cloudSession.first() != null) return
        val agents = agentRepository.getAll().first()
        val existingDefault = agents.firstOrNull { it.isDefault }
        if (existingDefault == null) {
            // 没有默认 Agent，则创建一个
            val now = System.currentTimeMillis()
            val defaultAgent = Agent(
                id = randomUuid(),
                name = "默认 Agent",
                systemPrompt = "You are a helpful assistant.",
                defaultModelId = null,
                temperature = 0.7f,
                topP = 1.0f,
                maxTokens = null,
                isDefault = true,
                createdAt = now,
                updatedAt = now
            )
            agentRepository.insert(defaultAgent)
            // 仅在当前没有选中任何 Agent 时切到默认 Agent，避免覆盖用户选择
            val currentId = appPreferences.currentAgentId.first()
            if (currentId == null) {
                currentAgentRepository.setCurrentAgentId(defaultAgent.id)
            }
        }
        appPreferences.setDefaultAgentInitialized(true)
    }
}

/**
 * Global access point, replacing `MessengerApplication.instance`.
 * Initialized once by each platform entry point before any UI runs.
 */
object AppContainerHolder {
    lateinit var instance: AppContainer
        private set

    fun initialize(container: AppContainer) {
        instance = container
    }
}
