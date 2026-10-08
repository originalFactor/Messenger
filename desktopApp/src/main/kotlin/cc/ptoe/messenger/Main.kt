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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import javax.imageio.ImageIO
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

fun main() {
    val home = System.getProperty("user.home").toPath()
    val appDirs = AppDirs(
        filesDir = home.resolve(".messenger").resolve("files"),
        cacheDir = home.resolve(".messenger").resolve("cache")
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
        val themeMode by AppContainerHolder.instance.themePreferences.themeMode
            .collectAsState(initial = ThemeMode.SYSTEM)

        val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(
            onCloseRequest = ::exitApplication,
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
                Column(modifier = Modifier.fillMaxSize()) {
                    DesktopTitleBar(
                        title = "Messenger",
                        onCloseRequest = ::exitApplication
                    )
                    MainScaffold()
                }
            }
        }
    }
}
