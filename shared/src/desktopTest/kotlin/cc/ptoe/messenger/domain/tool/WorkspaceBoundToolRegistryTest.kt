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

package cc.ptoe.messenger.domain.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A conversation outside any project must register NO built-in tool, and a
 * project conversation must register the terminal plus the five workspace
 * tools bound to that project's directory. This is the whole "only a project
 * can call tools" rule as the Kotlin side sees it.
 */
class WorkspaceBoundToolRegistryTest {

    private val workspaceTools = listOf(
        WorkspaceTool.GLOB,
        WorkspaceTool.GREP,
        WorkspaceTool.READ,
        WorkspaceTool.EDIT,
        WorkspaceTool.CREATE
    )

    @Test
    fun withoutAWorkspaceNoToolIsRegistered() {
        assertTrue(
            createBuiltinChatTools(null).isEmpty(),
            "a conversation outside any project has no working directory, so it must get no tools"
        )
    }

    @Test
    fun withAWorkspaceTheTerminalAndFileToolsAreRegistered() {
        val names = createBuiltinChatTools("/w/messenger").map { it.name }
        assertTrue(TerminalTool.TOOL_NAME in names, "terminal must exist inside a project")
        assertEquals(workspaceTools, names.filter { it in workspaceTools })
    }
}