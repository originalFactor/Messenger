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

/**
 * Bridge to an out-of-process shell runtime host: the targetSdk-28
 * Messenger Runtime companion app, whose legacy untrusted_app_27 SELinux
 * domain retains the execute/execute_no_trans rights on app data that the
 * main app's targetSdk-29+ domain lost to the Android 10+ W^X rule.
 * androidApp registers an AIDL-backed implementation at startup. There is
 * deliberately NO fallback shell: without the companion the terminal app
 * cannot be launched and Android registers no agent tools.
 */
interface ShellRuntimeBridge {
    /**
     * Executes a command and returns the result, or null when the runtime is
     * unavailable (caller falls back). Output chunks stream via [onOutput].
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long,
        workingDir: String?,
        onOutput: ((String) -> Unit)?
    ): ShellResult?

    /** Whether the companion app is installed (cheap PackageManager check). */
    fun isInstalled(): Boolean

    // Workspace-confined file operations. The workspace lives in the
    // companion's own data directory (own UID), so the main app reaches it
    // only through these calls.
    suspend fun workspaceGlob(pattern: String, maxResults: Int): ToolExecutionResult
    suspend fun workspaceGrep(
        pattern: String,
        path: String,
        fileGlob: String?,
        caseSensitive: Boolean,
        fixedString: Boolean,
        maxResults: Int
    ): ToolExecutionResult
    suspend fun workspaceRead(path: String, startLine: Int, maxLines: Int): ToolExecutionResult
    suspend fun workspaceEdit(path: String, oldText: String, newText: String, replaceAll: Boolean): ToolExecutionResult
    suspend fun workspaceCreate(path: String, content: String, overwrite: Boolean): ToolExecutionResult

    // MCP command server process management
    suspend fun startMcpProcess(
        sessionId: Int,
        command: String,
        envJson: String?,
        onOutput: (String) -> Unit,
        onError: (String) -> Unit,
        onClosed: (Int) -> Unit
    ): Boolean
    suspend fun sendMcpInput(sessionId: Int, line: String): Boolean
    suspend fun stopMcpProcess(sessionId: Int)
}

/** androidApp registers the AIDL client here during application startup. */
object ShellRuntimeRegistry {
    @Volatile
    var bridge: ShellRuntimeBridge? = null
}

/**
 * Application ID of the companion runtime app: the AIDL service host and the
 * Termux-style terminal app the Settings screen opens.
 */
const val RUNTIME_PACKAGE = "cc.ptoe.messenger.runtime"

/** Fully-qualified name of the companion's terminal screen. */
const val RUNTIME_TERMINAL_ACTIVITY = "$RUNTIME_PACKAGE.TerminalActivity"
