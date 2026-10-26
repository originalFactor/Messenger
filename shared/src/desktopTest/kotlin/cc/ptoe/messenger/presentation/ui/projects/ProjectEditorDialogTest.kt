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

package cc.ptoe.messenger.presentation.ui.projects

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import cc.ptoe.messenger.presentation.viewmodel.ProjectEditorState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Choose folder" was a no-op once: the callback was hard-wired to `{}`, so
 * tapping it did nothing and the button looked broken. The dialog must only
 * render the action when a platform picker exists, and route the tap to it.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectEditorDialogTest {

    private fun state() = ProjectEditorState(name = "Messenger", autoWorkspaceFolder = "messenger")

    @Test
    fun chooseFolderLaunchesThePlatformPicker() = runComposeUiTest {
        var launched = false
        setContent {
            MaterialTheme {
                ProjectEditorDialog(
                    title = "New project",
                    state = state(),
                    onNameChange = {},
                    onWorkspaceChange = {},
                    onPickWorkspace = { launched = true },
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }

        onNodeWithTag(PROJECT_WORKSPACE_PICKER_TAG).assertIsDisplayed().performClick()
        assertTrue(launched)
    }

    /** Platforms without a usable directory chooser hide the action entirely. */
    @Test
    fun chooseFolderIsHiddenWithoutAPicker() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ProjectEditorDialog(
                    title = "New project",
                    state = state(),
                    onNameChange = {},
                    onWorkspaceChange = {},
                    onPickWorkspace = null,
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }

        assertFalse(onAllNodesWithTag(PROJECT_WORKSPACE_PICKER_TAG).fetchSemanticsNodes().isNotEmpty())
    }
}
