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

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Desktop tool execution contract.
 *
 * Regressions pinned here:
 *  - the terminal tool ran in the JVM's process cwd (the app's working
 *    directory), not the agent workspace, so a file the model created with
 *    the `create` tool was invisible to `terminal`/`cat`; Android already
 *    defaulted to the workspace.
 *  - the grep row post-processing dropped a "vimgrep column" that the rg
 *    invocation never emits (`--no-heading -n` prints `path:line:text`), so
 *    every matched line containing a colon lost the text before it.
 */
class DesktopToolsTest {
    private fun args(vararg pairs: Pair<String, String>): String = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
    }.toString()

    private fun workspace(): File =
        File(System.getProperty("user.home"), ".messenger/agent-runtime/workspace")

    @Test
    fun terminalRunsInTheAgentWorkspace(): Unit = runBlocking {
        val unique = "tools-test-${System.nanoTime()}"
        val created = WorkspaceTool(WorkspaceTool.CREATE, workspace().absolutePath)
            .execute(args("path" to "$unique/marker.txt", "content" to "workspace-marker\n"))
        assertTrue(!created.isError, "create failed: ${created.output}")

        // A command referencing the workspace-relative path must resolve:
        // the terminal process cwd is the workspace, not the app's directory.
        val terminal = TerminalTool(workspaceRoot = workspace().absolutePath).execute(args("command" to "cat $unique/marker.txt"))
        assertTrue(
            terminal.output.contains("workspace-marker") && !terminal.isError,
            "terminal did not run inside the workspace: ${terminal.output}"
        )

        File(workspace(), unique).deleteRecursively()
    }

    @Test
    fun grepPreservesColonsInsideMatchedLines(): Unit = runBlocking {
        val unique = "grep-test-${System.nanoTime()}"
        val created = WorkspaceTool(WorkspaceTool.CREATE, workspace().absolutePath)
            .execute(args("path" to "$unique/colon.txt", "content" to "key: value needle\nplain needle\n"))
        assertTrue(!created.isError, "create failed: ${created.output}")

        val result = WorkspaceTool(WorkspaceTool.GREP, workspace().absolutePath)
            .execute(args("pattern" to "needle", "path" to unique))
        assertTrue(!result.isError, "grep failed: ${result.output}")
        assertTrue(
            result.output.contains("key: value needle"),
            "grep mangled a colon in the matched line: ${result.output}"
        )
        assertTrue(
            result.output.lines().any { it.startsWith("$unique/colon.txt:1:") },
            "grep row lost its path:line prefix: ${result.output}"
        )

        File(workspace(), unique).deleteRecursively()
    }

    @Test
    fun terminalTimeoutTerminatesTheProcess() = runBlocking {
        val start = System.currentTimeMillis()
        val result = executeShellCommand(
            command = "Start-Sleep -Seconds 30",
            timeoutMs = 1_000,
            workingDir = null,
            onOutput = null
        )
        val elapsed = System.currentTimeMillis() - start
        assertTrue(result.timedOut, "expected timedOut=true, got ${result.exitCode}")
        // The process must be killed at the deadline, not after the command
        // finishes: a single blocking waitFor left the sleep running to 30 s.
        assertTrue(elapsed < 10_000, "timeout did not terminate the process (took ${elapsed}ms)")
    }
}
