/*
 * Copyright 2026 originalFactor
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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.Dimension
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.core.CoreHandle
import cc.ptoe.messenger.core.HttpServerHandle
import cc.ptoe.messenger.core.RestCoreBridge
import cc.ptoe.messenger.data.local.DesktopChatImageStore
import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.di.AppDirs
import cc.ptoe.messenger.presentation.theme.MessengerTheme
import cc.ptoe.messenger.presentation.theme.ThemeMode
import cc.ptoe.messenger.presentation.ui.components.MainScaffold
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import okio.Path.Companion.toPath

private lateinit var coreHttpServer: HttpServerHandle

fun main() {
    val home = System.getProperty("user.home").toPath()
    val appDirs = AppDirs(
        filesDir = home.resolve(".messenger").resolve("files"),
        cacheDir = home.resolve(".messenger").resolve("cache")
    )
    // The Rust core owns all durable state and the agent loop on every
    // platform. Desktop stores it beside the preferences (the legacy Room file
    // lives under filesDir/databases and is imported once, in place).
    val coreDir = appDirs.filesDir.resolve("messenger_core").toFile().apply { mkdirs() }
    val legacyDir = appDirs.filesDir.resolve("databases").toFile()
    val core = CoreHandle.open(
        storePath = coreDir.resolve("store.db").absolutePath,
        legacyDbDir = legacyDir.takeIf { it.isDirectory }?.absolutePath,
        nowMs = System.currentTimeMillis()
    )
    coreHttpServer = runBlocking { core.startHttpServer(0u) }
    CoreBridgeRegistry.bridge = RestCoreBridge(
        localAddress = coreHttpServer.localAddr(),
        token = coreHttpServer.token(),
    )

    val container = AppContainer(
        appDirs = appDirs,
        chatImageStore = DesktopChatImageStore(appDirs.filesDir)
    )
    AppContainerHolder.initialize(container)
    container.initializeLocalAndCloudData()

    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(
                    httpClient = { container.cloud.avatarHttpClient }
                ))
            }
            .build()
    }

    application {
        val closeApplication = {
            runBlocking { coreHttpServer.stop() }
            exitApplication()
        }
        val themeMode by AppContainerHolder.instance.themePreferences.themeMode
            .collectAsState(initial = ThemeMode.SYSTEM)

        // Clamp the default size to the monitor's work area: a fixed 1280x800
        // overflows smaller/high-DPI screens, pushing the window buttons and
        // the content past the screen edge.
        val windowState = initialWindowState()
        Window(
            onCloseRequest = closeApplication,
            title = "Messenger",
            state = windowState,
            undecorated = true
        ) {
            window.minimumSize = Dimension(800, 600)
            val iconImage = remember {
                Thread.currentThread().contextClassLoader
                    .getResourceAsStream("logo.png")
                    ?.use { ImageIO.read(it) }
            }
            SideEffect {
                if (iconImage != null) {
                    window.iconImage = iconImage
                }
            }
            MessengerTheme(themeMode = themeMode) {
                // The window is undecorated: tell AWT the monitor's work area
                // so maximizing stops above the taskbar instead of covering it.
                WorkAreaMaximizedBounds()
                Column(modifier = Modifier.fillMaxSize()) {
                    DesktopTitleBar(
                        title = "Messenger",
                        onCloseRequest = closeApplication
                    )
                    MainScaffold()
                }
            }
        }
    }
}
