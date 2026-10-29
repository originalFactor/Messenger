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

/** Opens the platform's directory chooser. */
fun interface FolderPickerLauncher {
    fun launch()
}

/**
 * A directory chooser for the project workspace field.
 *
 * Returns `null` when the platform cannot hand the app a filesystem path the
 * workspace tools can open (Android's Storage Access Framework returns content
 * URIs, and native clients has no filesystem). The editor then keeps its manual
 * text field instead of showing a button that cannot do anything.
 */
@Composable
expect fun rememberFolderPicker(
    title: String,
    onPicked: (path: String) -> Unit
): FolderPickerLauncher?
