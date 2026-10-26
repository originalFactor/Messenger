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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.LocalAwtWindow
import java.io.File
import javax.swing.JFileChooser

/**
 * Native Swing directory chooser. It is modal to the Compose window (the app
 * window is undecorated, so passing the parent matters: an unparented chooser
 * can end up behind it) and keeps the last picked directory as its starting
 * point.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun rememberFolderPicker(
    title: String,
    onPicked: (path: String) -> Unit
): FolderPickerLauncher? {
    val window = LocalAwtWindow.current
    val currentOnPicked = rememberUpdatedState(onPicked)
    return remember(title, window) {
        object : FolderPickerLauncher {
            private var lastDirectory: File? = null

            override fun launch() {
                val chooser = JFileChooser(
                    lastDirectory ?: File(System.getProperty("user.home"))
                ).apply {
                    dialogTitle = title
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                    isMultiSelectionEnabled = false
                }
                if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return
                val directory = chooser.selectedFile ?: return
                lastDirectory = directory
                currentOnPicked.value(directory.absolutePath)
            }
        }
    }
}
