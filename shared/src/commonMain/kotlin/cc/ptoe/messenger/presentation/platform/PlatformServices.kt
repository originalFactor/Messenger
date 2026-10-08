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
 * the settings (user avatar) and agent-edit (agent avatar) flows. On web
 * there is no filesystem, so the source (a `data:` URI) is returned as-is.
 */
expect fun copyAvatarToInternal(sourcePath: String, subdir: String): String?

/**
 * Whether this platform ships the Messenger Runtime terminal app (the
 * Termux-style interactive shell app, a separate APK on Android). Desktop has
 * no such app, so the Settings terminal entry is hidden there.
 */
expect val runtimeTerminalSupported: Boolean


/**
 * The platform's default agent workspace directory, used to enumerate the
 * built-in tools for the Agent/conversation tool-config screens. It is NOT a
 * conversation's working directory — that always comes from the owning
 * project — only a concrete directory so the tool factory can construct the
 * workspace-bound tool declarations.
 */
expect fun defaultWorkspaceRoot(): String?


/**
 * Opens the Messenger Runtime terminal app's terminal screen. Returns false
 * when the companion app is not installed, so the caller can tell the user
 * where to get it instead of failing silently.
 */
expect fun openRuntimeTerminal(): Boolean

/** Deletes a previously stored avatar file, if it exists. */
expect fun deleteAvatarFile(path: String?)
