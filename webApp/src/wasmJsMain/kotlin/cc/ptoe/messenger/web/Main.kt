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

@file:OptIn(ExperimentalWasmJsInterop::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package cc.ptoe.messenger.web

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeViewport
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.core.MessengerWasm
import cc.ptoe.messenger.core.WasmCoreBridge
import cc.ptoe.messenger.core.awaitJsUnit
import cc.ptoe.messenger.core.initMessengerWasm
import cc.ptoe.messenger.data.local.WebChatImageStore
import cc.ptoe.messenger.di.AppContainer
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.di.AppDirs
import cc.ptoe.messenger.presentation.theme.MessengerTheme
import cc.ptoe.messenger.presentation.theme.ThemeMode
import cc.ptoe.messenger.presentation.ui.components.MainScaffold
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/**
 * Browser entry point.
 *
 * Bring-up is asynchronous because the persisted database must be read out of
 * the origin-private file system before the store opens; the composition is
 * mounted only once the container exists.
 *
 * Ordering is load-bearing: `AppContainer` reads `CoreBridgeRegistry` during
 * construction and the wasmJs `createLocalStores` requires it non-null, so
 * the bridge is installed first. A failure to load the wasm module is allowed
 * to propagate — a container built without a core would be silently broken.
 */
fun main() {
    CoroutineScope(Dispatchers.Default).launch {
        // wasm-bindgen's web target exposes an async initializer; nothing in
        // the module (including the OPFS store) works until it resolves.
        initMessengerWasm().let { awaitJsUnit(it) }
        MessengerWasm.prepare_wasm_store("messenger").let { awaitJsUnit(it) }
        val core = MessengerWasm.open_wasm_core("messenger.db", jsDateNow())
        CoreBridgeRegistry.bridge = WasmCoreBridge(core)

        val container = AppContainer(
            appDirs = AppDirs(
                filesDir = "opfs:/files".toPath(),
                cacheDir = "opfs:/cache".toPath()
            ),
            chatImageStore = WebChatImageStore()
        )
        AppContainerHolder.initialize(container)
        container.initializeLocalAndCloudData()

        // Coil must have its loader installed before the first image composes;
        // the Rust core is not involved in avatar loading.
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(KtorNetworkFetcherFactory(httpClient = { container.cloud.avatarHttpClient }))
                }
                .build()
        }

        ComposeViewport(document.body!!) {
            val themeMode by AppContainerHolder.instance.themePreferences.themeMode
                .collectAsState(initial = ThemeMode.SYSTEM)
            MessengerTheme(themeMode = themeMode) {
                MainScaffold()
            }
        }
    }
}

@JsFun("() => Date.now()")
private external fun jsDateNow(): Double
