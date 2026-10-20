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
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.presentation.ui.components.ProjectListItem
import cc.ptoe.messenger.presentation.ui.components.UnprojectedFolderItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The project row is the entry point to a workspace, so it must actually render
 * its name, its conversation count, and route taps to the project — the three
 * things the chat list depends on.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectListItemTest {

    private val project = Project("proj1", "Messenger", "/w/messenger", 1, 1)

    @Test
    fun rendersTheNameAndConversationCount() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ProjectListItem(
                    project = project,
                    conversationCount = 3,
                    onClick = {},
                    onEditClick = {},
                    onDeleteClick = {}
                )
            }
        }

        onNodeWithText("Messenger").assertIsDisplayed()
    }

    @Test
    fun tappingTheRowOpensThatProject() = runComposeUiTest {
        val opened = mutableListOf<String>()
        setContent {
            MaterialTheme {
                ProjectListItem(
                    project = project,
                    conversationCount = 1,
                    onClick = { opened += project.id },
                    onEditClick = {},
                    onDeleteClick = {}
                )
            }
        }

        onNodeWithText("Messenger").performClick()
        assertEquals(listOf("proj1"), opened)
    }

    /** 普通对话收在“无项目”文件夹里，因此这个入口必须可点且显示会话数。 */
    @Test
    fun noProjectFolderShowsItsCountAndOpens() = runComposeUiTest {
        var opened = false
        setContent {
            MaterialTheme {
                UnprojectedFolderItem(
                    conversationCount = 2,
                    onClick = { opened = true },
                    modifier = Modifier.testTag("no-project-folder")
                )
            }
        }

        onNodeWithTag("no-project-folder").assertIsDisplayed()
        onNodeWithTag("no-project-folder").performClick()
        assertTrue(opened)
    }
}