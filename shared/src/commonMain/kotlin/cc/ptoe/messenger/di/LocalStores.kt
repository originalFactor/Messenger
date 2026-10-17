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

import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.domain.repository.CloudFacade

/**
 * The platform's local stores: the cloud facade (always the Rust-backed
 * [cc.ptoe.messenger.data.cloud.RustCloudFacade]) plus the two preferences
 * stores. Durable entity data lives in the Rust core on every platform; only
 * the preferences DataStore is platform-specific (a file on Android/Desktop,
 * OPFS in the browser).
 */
open class LocalStores(
    val cloud: CloudFacade,
    val appPreferences: AppPreferences,
    val themePreferences: ThemePreferences,
)

/**
 * Builds this platform's stores. Called once from [AppContainer]'s
 * constructor — the per-target selection is what keeps `commonMain` free of
 * Room (`commonMain` has no Room artifact for wasmJs).
 */
expect fun createLocalStores(appDirs: AppDirs): LocalStores

/**
 * Wipes every locally persisted row and re-seeds the default agent.
 * Mirrors the historical `AppContainer.clearAllDataAndReinit` body.
 */
expect suspend fun clearLocalData(appDirs: AppDirs, stores: LocalStores)
