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

actual suspend fun executeShellCommand(
    command: String,
    timeoutMs: Long,
    workingDir: String?,
    onOutput: ((String) -> Unit)?
): ShellResult {
    // Shell execution exists ONLY through the companion runtime (its legacy
    // SELinux domain can exec app data; the main app's cannot). Defensive
    // error result — the agent tools are only registered when the companion
    // is installed, so this branch should not be reached.
    val bridge = ShellRuntimeRegistry.bridge
        ?: return ShellResult(output = "Messenger Runtime companion app is not installed.", exitCode = -1)
    return bridge.execute(command, timeoutMs, workingDir, onOutput)
        ?: ShellResult(output = "Messenger Runtime companion app is not available.", exitCode = -1)
}

internal actual suspend fun executeWorkspaceOperation(operation: WorkspaceOperation): ToolExecutionResult {
    // The workspace lives in the companion runtime app's own data directory;
    // file operations are brokered over AIDL. The agent tools only exist
    // when the companion is installed, so a missing bridge is defensive.
    val bridge = ShellRuntimeRegistry.bridge
        ?: return ToolExecutionResult("Messenger Runtime companion app is not installed.", isError = true)
    return when (operation) {
        is WorkspaceOperation.Glob -> bridge.workspaceGlob(operation.pattern, operation.maxResults)
        is WorkspaceOperation.Grep -> bridge.workspaceGrep(
            operation.pattern, operation.path, operation.fileGlob, operation.caseSensitive, operation.maxResults
        )
        is WorkspaceOperation.Read -> bridge.workspaceRead(operation.path, operation.startLine, operation.maxLines)
        is WorkspaceOperation.Edit -> bridge.workspaceEdit(operation.path, operation.oldText, operation.newText, operation.replaceAll)
        is WorkspaceOperation.Create -> bridge.workspaceCreate(operation.path, operation.content, operation.overwrite)
    }
}
