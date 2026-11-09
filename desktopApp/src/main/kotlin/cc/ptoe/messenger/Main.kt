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
import kotlinx.coroutines.withTimeout
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
    // Must run before any AWT/Compose class initializes its toolkit.
    val nativeWayland = configureDesktopBackend()
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
    val acpDescriptor = coreDir.resolve("acp-connection.json").toPath()
    runCatching { writeAcpDescriptor(acpDescriptor, coreHttpServer) }
        .onFailure { System.err.println("Messenger ACP descriptor unavailable: ${it.message}") }
    val restCore = RestCoreBridge(
        localAddress = coreHttpServer.localAddr(),
        token = coreHttpServer.token(),
    )
    CoreBridgeRegistry.bridge = restCore
    // Stop the loopback server when the process ends (window close, Ctrl+C).
    // The stop is graceful and waits for in-flight responses, so it never
    // belongs on the UI thread; the timeout keeps the JVM from hanging if a
    // response refuses to drain.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching {
                runBlocking { withTimeout(5_000) { coreHttpServer.stop() } }
                removeAcpDescriptor(acpDescriptor, coreHttpServer.token())
            }
        }
    )

    val container = AppContainer(
        appDirs = appDirs,
        chatImageStore = DesktopChatImageStore(appDirs.filesDir)
    )
    restCore.platformTools = container::availableTools
    restCore.publishPlatformTools(container.mcpManager.activeTools)
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

    if (nativeWayland) {
        launchWaylandWindow()
        return
    }

    application {
        // Closing must never block AWT's event thread: `stop` is a suspending
        // call that waits for the server to drain, and a hung wait there left
        // the window painted but dead to every click. The shutdown hook in
        // `main` owns the actual server stop, so this only closes the UI.
        val closeApplication = { exitApplication() }
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
