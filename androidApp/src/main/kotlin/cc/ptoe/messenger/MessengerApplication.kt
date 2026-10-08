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

package cc.ptoe.messenger

import android.app.Application
import android.content.Intent
import cc.ptoe.messenger.core.AndroidCoreBridge
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.core.CoreHandle
import cc.ptoe.messenger.data.local.AndroidChatImageStore
import cc.ptoe.messenger.data.wear.MobileHttpServer
import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.di.AppDirs
import cc.ptoe.messenger.domain.tool.ShellRuntimeRegistry
import cc.ptoe.messenger.presentation.platform.AndroidContextHolder
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import java.io.File
import okio.Path.Companion.toPath

class MessengerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.appContext = applicationContext
        // Optional companion runtime app (targetSdk 28, shared UID) hosts the
        // Termux bootstrap in its legacy SELinux domain; shell commands route
        // there when installed and fall back to the system shell otherwise.
        ShellRuntimeRegistry.bridge = RuntimeShellClient(applicationContext)

        // Initialize Rust Core SQLite store and import Room database on first run
        val storeDir = File(filesDir, "messenger_core").apply { mkdirs() }
        val legacyDir = getDatabasePath("messenger_database").parentFile
        val core = CoreHandle.open(
            storePath = File(storeDir, "store.db").absolutePath,
            legacyDbDir = legacyDir?.takeIf { it.exists() }?.absolutePath,
            nowMs = System.currentTimeMillis()
        )
        CoreBridgeRegistry.bridge = AndroidCoreBridge(core)

        val container = AppContainer(
            appDirs = AppDirs(
                filesDir = filesDir.absolutePath.toPath(),
                cacheDir = cacheDir.absolutePath.toPath()
            ),
            chatImageStore = AndroidChatImageStore(this)
        )
        AppContainerHolder.initialize(container)
        container.initializeLocalAndCloudData()
        setupImageLoader(container)
        startWearSync()
    }

    /** Coil with the cookie-aware cloud client so authenticated avatars load. */
    private fun setupImageLoader(container: AppContainer) {
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(KtorNetworkFetcherFactory(
                        httpClient = { container.cloud.avatarHttpClient }
                    ))
                }
                .build()
        }
    }

    /**
     * Spin up the WebSocket server that the watch companion connects to.
     * Discovery is via Android NSD (mDNS) so no pairing or runtime
     * permissions are required — Wear OS watches tether their network to
     * the phone (Bluetooth PAN), so the watch and phone are always on the
     * same L2 network.
     */
    private fun startWearSync() {
        val intent = Intent(this, MobileHttpServer::class.java)
        runCatching { startForegroundService(intent) }
    }
}
