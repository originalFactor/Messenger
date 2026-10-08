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

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.data.cloud.RustCloudFacade
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.createMessengerDataStore
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import kotlinx.coroutines.sync.Mutex

/**
 * Web stores: the Rust core owns durable data (SQLite mirrored into OPFS),
 * so the cloud facade is [RustCloudFacade]; preferences live in an
 * OPFS-backed DataStore. There is no Room database on wasmJs.
 */
actual fun createLocalStores(appDirs: AppDirs): LocalStores {
    val core = requireNotNull(CoreBridgeRegistry.bridge) {
        "CoreBridgeRegistry.bridge must be installed before AppContainer is built"
    }
    val dataStore = createMessengerDataStore(appDirs.filesDir)
    val appPreferences = AppPreferences(dataStore)
    val themePreferences = ThemePreferences(dataStore)
    val cloud = RustCloudFacade(
        core = core,
        appPreferences = appPreferences,
        localDataMutex = Mutex(),
    )
    return LocalStores(cloud = cloud, appPreferences = appPreferences, themePreferences = themePreferences)
}

/**
 * Unreachable: [AppContainer] only calls this when no CoreBridge is installed,
 * and the web entry point always installs one.
 */
actual fun createRoomRepositories(
    stores: LocalStores,
    chatImageStore: ChatImageStore,
    hooks: LocalChangeHooks,
): RoomRepositories = error("Room is not available on wasmJs")

/**
 * Wipes the Rust store's rows and the session/user key-value state, then lets
 * [AppContainer] re-seed the default agent. This is deliberately NOT
 * `cloudSync(replaceLocal = true)`, which would pull the remote state straight
 * back into the freshly cleared local one.
 */
actual suspend fun clearLocalData(appDirs: AppDirs, stores: LocalStores) {
    val core = requireNotNull(stores.cloud as? RustCloudFacade) {
        "clearLocalData requires the Rust cloud facade"
    }.core
    core.clearLocalDataForReinit()
}
