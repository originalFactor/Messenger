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

package cc.ptoe.messenger.presentation.platform

import cc.ptoe.messenger.data.util.FileKit
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.di.AppContainerHolder

/** Shows a short platform toast/notification (Android Toast, Desktop stdout). */
expect fun showPlatformToast(message: String)

/** Copies text to the system clipboard. */
expect fun copyTextToClipboard(text: String)

/** App version name for the settings screen (`null` when unavailable). */
expect fun appVersionName(): String?

/**
 * Whether the chat input field should treat a bare hardware Enter key as
 * "send message" (with Shift/Ctrl+Enter reserved for newline insertion).
 *
 * - Desktop (`desktopMain`): `true` — physical keyboard is the primary
 *   input method, matching Gmail / Messages for web / Slack conventions.
 * - Android (`androidMain`): `false` — virtual IME sends Enter as a
 *   newline via the composing text path; `onPreviewKeyEvent` does not
 *   fire for soft-keyboard input, so keeping this flag off avoids any
 *   accidental interference with the on-screen keyboard.
 *
 * The Wear module has no chat input field at all (voice + canned replies).
 */
expect val sendOnEnterShortcut: Boolean

/**
 * Copies a picked/cropped image file into app-private avatar storage
 * (`filesDir/<subdir>`) and returns the new absolute path. Shared by
 * the settings (user avatar) and agent-edit (agent avatar) flows.
 */
fun copyAvatarToInternal(sourcePath: String, subdir: String): String? {
    return try {
        val dir = AppContainerHolder.instance.appDirs.filesDir.resolve(subdir)
        FileKit.mkdirs(dir)
        val extension = FileKit.extensionOf(sourcePath).ifBlank { "jpg" }
        val dest = dir.resolve("${randomUuid()}.$extension")
        FileKit.copy(sourcePath, dest.toString())
        dest.toString()
    } catch (e: Exception) {
        null
    }
}

/**
 * Whether this platform ships the Messenger Runtime terminal app (the
 * Termux-style interactive shell app, a separate APK on Android). Desktop has
 * no such app, so the Settings terminal entry is hidden there.
 */
expect val runtimeTerminalSupported: Boolean

/**
 * Opens the Messenger Runtime terminal app's terminal screen. Returns false
 * when the companion app is not installed, so the caller can tell the user
 * where to get it instead of failing silently.
 */
expect fun openRuntimeTerminal(): Boolean

/** Deletes a previously stored avatar file, if it exists. */
fun deleteAvatarFile(path: String?) {
    if (path.isNullOrBlank()) return
    FileKit.delete(path)
}
