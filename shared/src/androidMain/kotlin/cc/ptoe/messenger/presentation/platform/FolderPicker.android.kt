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

package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.Composable

/**
 * Android has no folder picker that returns a path the runtime companion can
 * open: the Storage Access Framework hands back a tree URI granted to this
 * app's UID, while workspace operations execute in the companion's process.
 * Keep the editor on manual path entry rather than offering a picker that
 * would produce an unusable workspace.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
actual fun rememberFolderPicker(
    title: String,
    onPicked: (path: String) -> Unit
): FolderPickerLauncher? = null
