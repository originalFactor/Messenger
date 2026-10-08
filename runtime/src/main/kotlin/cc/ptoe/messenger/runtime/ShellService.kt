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

package cc.ptoe.messenger.runtime

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * AIDL facade over [TermuxRuntime]. The main Messenger app binds to this
 * service and routes shell commands + workspace file operations here so
 * they execute in this app's legacy SELinux domain. Binding requires the
 * signature-level [PERMISSION] (only same-key builds can hold it); every
 * call re-checks it defensively.
 */
class ShellService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap<Int, Job>()
    private val mcpSessions = ConcurrentHashMap<Int, TermuxRuntime.McpProcessSession>()

    private val binder = object : IShellService.Stub() {
        override fun submit(
            requestId: Int,
            command: String,
            workingDir: String?,
            timeoutMs: Long,
            callback: IShellCallback
        ) {
            if (!isCallerAllowed()) {
                android.util.Log.w(TAG, "submit rejected: caller lacks $PERMISSION")
                return
            }
            val job = scope.launch {
                try {
                    val result = TermuxRuntime.execute(
                        context = applicationContext,
                        command = command,
                        timeoutMs = timeoutMs,
                        workingDir = workingDir,
                        onOutput = { chunk ->
                            runCatching { callback.onOutput(requestId, chunk) }
                        }
                    )
                    callback.onFinished(requestId, result.exitCode, result.output, result.timedOut)
                } catch (e: CancellationException) {
                    runCatching { callback.onFinished(requestId, -1, "Command terminated", false) }
                    throw e
                } catch (e: Throwable) {
                    runCatching { callback.onFinished(requestId, -1, "Runtime error: ${e.message}", false) }
                } finally {
                    active.remove(requestId)
                }
            }
            active[requestId] = job
        }

        override fun cancel(requestId: Int) {
            active.remove(requestId)?.cancel()
        }

        override fun workspaceGlob(root: String?, pattern: String?, maxResults: Int): ToolResult? =
            ifCallerAllowed(root) {
                runBlocking { TermuxRuntime.workspaceGlob(applicationContext, root, pattern.orEmpty(), maxResults) }
            }

        override fun workspaceGrep(
            root: String?,
            pattern: String?,
            path: String?,
            fileGlob: String?,
            caseSensitive: Boolean,
            fixedString: Boolean,
            maxResults: Int
        ): ToolResult? = ifCallerAllowed(root) {
            runBlocking {
                TermuxRuntime.workspaceGrep(
                    applicationContext, root, pattern.orEmpty(), path.orEmpty(), fileGlob,
                    caseSensitive, fixedString, maxResults
                )
            }
        }

        override fun workspaceRead(
            root: String?,
            path: String?,
            startLine: Int,
            maxLines: Int
        ): ToolResult? = ifCallerAllowed(root) {
            runBlocking {
                TermuxRuntime.workspaceRead(applicationContext, root, path.orEmpty(), startLine, maxLines)
            }
        }

        override fun workspaceEdit(
            root: String?,
            path: String?,
            oldText: String?,
            newText: String?,
            replaceAll: Boolean
        ): ToolResult? = ifCallerAllowed(root) {
            runBlocking {
                TermuxRuntime.workspaceEdit(
                    applicationContext, root, path.orEmpty(), oldText.orEmpty(), newText.orEmpty(), replaceAll
                )
            }
        }

        override fun workspaceCreate(
            root: String?,
            path: String?,
            content: String?,
            overwrite: Boolean
        ): ToolResult? = ifCallerAllowed(root) {
            runBlocking {
                TermuxRuntime.workspaceCreate(applicationContext, root, path.orEmpty(), content.orEmpty(), overwrite)
            }
        }

        override fun startMcpProcess(
            sessionId: Int,
            command: String?,
            envJson: String?,
            callback: IMcpCallback?
        ): Boolean {
            if (!isCallerAllowed() || command.isNullOrBlank() || callback == null) return false
            mcpSessions.remove(sessionId)?.close()
            return runBlocking {
                val session = TermuxRuntime.startMcpProcess(
                    context = applicationContext,
                    command = command,
                    envJson = envJson,
                    onOutput = { line -> runCatching { callback.onOutput(sessionId, line) } },
                    onError = { err -> runCatching { callback.onError(sessionId, err) } },
                    onClosed = { code ->
                        mcpSessions.remove(sessionId)
                        runCatching { callback.onClosed(sessionId, code) }
                    }
                )
                if (session != null) {
                    mcpSessions[sessionId] = session
                    true
                } else {
                    false
                }
            }
        }

        override fun sendMcpInput(sessionId: Int, line: String?): Boolean {
            if (!isCallerAllowed() || line == null) return false
            return mcpSessions[sessionId]?.sendLine(line) ?: false
        }

        override fun stopMcpProcess(sessionId: Int) {
            if (!isCallerAllowed()) return
            mcpSessions.remove(sessionId)?.close()
        }
    }

    /** Only holders of the signature permission (the main app) may call in. */
    private fun isCallerAllowed(): Boolean =
        checkCallingPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Guards a synchronous workspace call: the signature permission first,
     * then the workspace root itself. A project created on another device (or
     * whose folder was removed) names a directory this UID cannot reach, and
     * falling back to the default workspace would run the agent's file
     * operations somewhere it never asked for.
     */
    private fun ifCallerAllowed(root: String?, block: () -> ToolResult): ToolResult {
        if (!isCallerAllowed()) {
            return ToolResult("Caller is not permitted to use the shell runtime.", isError = true)
        }
        TermuxRuntime.resolveWorkspace(applicationContext, root)?.let { return it }
        // Any uncaught exception escaping a synchronous binder method surfaces
        // on the caller as a RuntimeException, which the main app's tool host
        // rethrows through the JNI upcall → Rust panic ("tool host
        // cancelled"). Convert failures into error results instead.
        return try {
            block()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "workspace op failed", e)
            ToolResult("Runtime workspace operation failed: ${e.message ?: e::class.simpleName}", isError = true)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i(TAG, "service created")
    }

    override fun onDestroy() {
        for (session in mcpSessions.values) {
            session.close()
        }
        mcpSessions.clear()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ShellRT"
        const val PERMISSION = "cc.ptoe.messenger.runtime.permission.SHELL"
    }
}
