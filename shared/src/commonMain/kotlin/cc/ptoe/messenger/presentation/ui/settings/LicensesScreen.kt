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

package cc.ptoe.messenger.presentation.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.settings_animated_shape_license
import cc.ptoe.messenger.generated.resources.settings_license_app
import cc.ptoe.messenger.generated.resources.settings_license_core
import cc.ptoe.messenger.generated.resources.settings_license_intro
import cc.ptoe.messenger.generated.resources.settings_license_link_error
import cc.ptoe.messenger.generated.resources.settings_license_notices
import cc.ptoe.messenger.generated.resources.settings_license_rendering
import cc.ptoe.messenger.generated.resources.settings_licenses
import cc.ptoe.messenger.presentation.platform.showPlatformToast
import cc.ptoe.messenger.presentation.ui.components.ExpressiveScaffold
import cc.ptoe.messenger.presentation.ui.components.ExpressiveTopAppBar
import cc.ptoe.messenger.presentation.ui.components.ListItem
import cc.ptoe.messenger.presentation.ui.components.SectionHeader
import cc.ptoe.messenger.presentation.ui.components.settingsItems
import org.jetbrains.compose.resources.stringResource

internal const val MESSENGER_GITHUB_URL = "https://github.com/originalFactor/Messenger"

// Keep this curated list aligned with shared/build.gradle.kts, the embedded
// renderer and the Core Cargo manifests. These are the principal components,
// rather than a resolved inventory of every transitive/platform dependency.
private data class OpenSourceComponent(val name: String, val license: String, val url: String)

private val appComponents = listOf(
    OpenSourceComponent("Kotlin", "Apache-2.0", "https://github.com/JetBrains/kotlin"),
    OpenSourceComponent("Compose Multiplatform", "Apache-2.0", "https://github.com/JetBrains/compose-multiplatform"),
    OpenSourceComponent("AndroidX / Jetpack Compose / Material 3 / DataStore / SQLite", "Apache-2.0", "https://android.googlesource.com/platform/frameworks/support/"),
    OpenSourceComponent("kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    OpenSourceComponent("kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    OpenSourceComponent("kotlinx-datetime", "Apache-2.0", "https://github.com/Kotlin/kotlinx-datetime"),
    OpenSourceComponent("Ktor", "Apache-2.0", "https://github.com/ktorio/ktor"),
    OpenSourceComponent("OkHttp", "Apache-2.0", "https://github.com/square/okhttp"),
    OpenSourceComponent("Okio", "Apache-2.0", "https://github.com/square/okio"),
    OpenSourceComponent("Coil", "Apache-2.0", "https://github.com/coil-kt/coil"),
    OpenSourceComponent("Java-WebSocket", "MIT", "https://github.com/TooTallNate/Java-WebSocket"),
    OpenSourceComponent("uCrop", "Apache-2.0", "https://github.com/Yalantis/uCrop"),
    OpenSourceComponent("JNA", "Apache-2.0 OR LGPL-2.1-or-later", "https://github.com/java-native-access/jna"),
    OpenSourceComponent("Skiko", "Apache-2.0", "https://github.com/JetBrains/skiko"),
    OpenSourceComponent("Wry", "Apache-2.0 OR MIT", "https://github.com/tauri-apps/wry"),
    OpenSourceComponent("Tao", "Apache-2.0", "https://github.com/tauri-apps/tao"),
    OpenSourceComponent("webkit2gtk-rs", "MIT", "https://github.com/tauri-apps/webkit2gtk-rs"),
    OpenSourceComponent("WebKit / WebKitGTK / WKWebView", "BSD / LGPL · System webview", "https://webkit.org/licensing-webkit/"),
)

private val renderingComponents = listOf(
    OpenSourceComponent("llm-typewriter", "Apache-2.0 · Copyright 2026 ECSDevs; includes work by NadeemIqbal", "https://github.com/ECSDevs/llm-typewriter/tree/3906043b2372c2f9e30e4142f51d9cad53b29658"),
    OpenSourceComponent("Highlights", "Apache-2.0", "https://github.com/SnipMeDev/Highlights"),
    OpenSourceComponent("RaTeX-CMP", "MIT", "https://github.com/darriousliu/RaTeX-CMP"),
    OpenSourceComponent("RaTeX", "MIT", "https://github.com/erweixin/RaTeX"),
    OpenSourceComponent("Cascadia Code", "SIL Open Font License 1.1 · Copyright Microsoft Corporation", "https://github.com/microsoft/cascadia-code"),
)

private val coreComponents = listOf(
    OpenSourceComponent("Axum", "MIT", "https://github.com/tokio-rs/axum"),
    OpenSourceComponent("Tokio / tokio-util / tokio-stream", "MIT", "https://github.com/tokio-rs/tokio"),
    OpenSourceComponent("Serde / serde_json", "MIT OR Apache-2.0", "https://github.com/serde-rs/serde"),
    OpenSourceComponent("serde_yaml_ng", "MIT", "https://github.com/acatton/serde-yaml-ng"),
    OpenSourceComponent("gitoxide / gix / gix-pack", "MIT OR Apache-2.0", "https://github.com/GitoxideLabs/gitoxide"),
    OpenSourceComponent("tempfile", "MIT OR Apache-2.0", "https://github.com/Stebalien/tempfile"),
    OpenSourceComponent("percent-encoding", "MIT OR Apache-2.0", "https://github.com/servo/rust-url"),
    OpenSourceComponent("reqwest", "MIT OR Apache-2.0", "https://github.com/seanmonstar/reqwest"),
    OpenSourceComponent("rustls", "Apache-2.0 OR ISC OR MIT", "https://github.com/rustls/rustls"),
    OpenSourceComponent("rusqlite", "MIT", "https://github.com/rusqlite/rusqlite"),
    OpenSourceComponent("SQLite", "Public domain", "https://www.sqlite.org/copyright.html"),
    OpenSourceComponent("UniFFI", "MPL-2.0", "https://github.com/mozilla/uniffi-rs"),
    OpenSourceComponent("Agent Client Protocol Rust SDK", "Apache-2.0", "https://github.com/agentclientprotocol/rust-sdk"),
    OpenSourceComponent("tokio-tungstenite", "MIT", "https://github.com/snapview/tokio-tungstenite"),
)

/** The same browser action and visible failure handling on Android and Desktop. */
@Composable
internal fun rememberOpenSourceLinkOpener(): (String) -> Unit {
    val uriHandler = LocalUriHandler.current
    val error = stringResource(Res.string.settings_license_link_error)
    return remember(uriHandler, error) {
        { url ->
            try {
                uriHandler.openUri(url)
            } catch (_: Exception) {
                showPlatformToast(error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBackClick: () -> Unit) {
    val openLink = rememberOpenSourceLinkOpener()
    ExpressiveScaffold(
        topBar = {
            ExpressiveTopAppBar(
                title = { Text(stringResource(Res.string.settings_licenses)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.action_back))
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "intro") {
                    Text(
                        stringResource(Res.string.settings_license_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                item(key = "messenger") {
                    ListItem(
                        title = "Messenger",
                        subtitle = "Apache-2.0 · Copyright 2026 originalFactor",
                        onClick = { openLink("$MESSENGER_GITHUB_URL/blob/main/LICENSE") },
                    )
                }
                item(key = "app-heading") { SectionHeader(stringResource(Res.string.settings_license_app)) }
                settingsItems(appComponents, key = { it.name }, contentType = "component") { component ->
                    LicenseComponent(component, openLink)
                }
                item(key = "rendering-heading") { SectionHeader(stringResource(Res.string.settings_license_rendering)) }
                settingsItems(renderingComponents, key = { it.name }, contentType = "component") { component ->
                    LicenseComponent(component, openLink)
                }
                item(key = "core-heading") { SectionHeader(stringResource(Res.string.settings_license_core)) }
                settingsItems(coreComponents, key = { it.name }, contentType = "component") { component ->
                    LicenseComponent(component, openLink)
                }
                item(key = "notices-heading") { SectionHeader(stringResource(Res.string.settings_license_notices)) }
                item(key = "animated-shapes") {
                    ListItem(
                        title = "AnimatedShape.kt",
                        subtitle = stringResource(Res.string.settings_animated_shape_license),
                        onClick = {
                            openLink("https://github.com/Baka-SU/BakaSU/blob/548d1dda060ca198823d8808804d01f5209ef4a9/manager/app/src/main/java/org/bakasu/bakasu/ui/component/settings/material3internal/AnimatedShape.kt")
                        },
                    )
                }
                item(key = "apache-notice") {
                    Text(
                        APACHE_NOTICE,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LicenseComponent(component: OpenSourceComponent, openLink: (String) -> Unit) {
    ListItem(
        title = component.name,
        subtitle = component.license,
        onClick = { openLink(component.url) },
    )
}

private val APACHE_NOTICE = """
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
""".trimIndent()
