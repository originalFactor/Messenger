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

@file:OptIn(ExperimentalWasmJsInterop::class)

package cc.ptoe.messenger.presentation.platform

/** Browser "toast": the console. Real errors surface as in-app snackbars. */
actual fun showPlatformToast(message: String) {
    println("Toast: $message")
}

@JsFun("(text) => { navigator.clipboard.writeText(text); }")
private external fun writeClipboard(text: String)

actual fun copyTextToClipboard(text: String) {
    runCatching { writeClipboard(text) }
}

/** Firefox/Safari install the app with an extension, so a version to show is rare. */
actual fun appVersionName(): String? = null

actual val sendOnEnterShortcut: Boolean = true

/**
 * A browser cannot write into app-private storage, and the picked avatar is a
 * `data:` URI the rest of the pipeline can consume as-is, so the source is
 * returned unchanged.
 */
actual fun copyAvatarToInternal(sourcePath: String, subdir: String): String? = sourcePath

actual fun deleteAvatarFile(path: String?) = Unit

/** No companion runtime app exists in a browser: hide the Settings row. */
actual val runtimeTerminalSupported: Boolean = false

actual fun openRuntimeTerminal(): Boolean = false
