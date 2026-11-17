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
    private val acpSessions = RuntimeProcessRegistry<TermuxRuntime.McpProcessSession> { it.close() }
    private val mcpSessions = RuntimeProcessRegistry<TermuxRuntime.McpProcessSession> { it.close() }

    private val binder = object : IShellService.Stub() {
        override fun temporaryChatWorkspace(conversationId: String?): String? {
            if (!isCallerAllowed() || conversationId.isNullOrBlank()) return null
            val key = java.security.MessageDigest.getInstance("SHA-256")
                .digest(conversationId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            val dir = java.io.File(java.io.File(cacheDir, "chat-workspaces"), key)
            return if (dir.isDirectory || dir.mkdirs()) dir.absolutePath else null
        }

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

        // The Runtime shell and workspace operations share this UID and can modify the
        // prototype journal. Do not expose its markers as trustworthy stop evidence.
        override fun trackedWorkspaceProtocol(): Int = 0

        override fun trackedWorkspaceStatus(token: String?): Int = TrackedWorkspaceOperations.UNKNOWN

        override fun trackedWorkspaceOperation(token: String?, root: String?, operation: String?, argumentsJson: String?): ToolResult =
            ToolResult("Tracked workspace operations are unavailable.", isError = true)

        // This protocol exposes no Runtime-owned status journal. The caller tracks a synchronous
        // reply and must treat Binder death as unknown; the packaged rg is joined before return.
        override fun safeWorkspaceProtocol(): Int =
            if (isCallerAllowed() && TermuxRuntime.supportsSafeWorkspaceGrep(applicationContext)) 1 else 0

        override fun safeWorkspaceGrep(root: String?, pattern: String?, path: String?, fileGlob: String?,
            caseSensitive: Boolean, fixedString: Boolean, maxResults: Int): ToolResult = ifCallerAllowed(root) {
            runBlocking {
                TermuxRuntime.trackedWorkspaceGrep(applicationContext, root, pattern.orEmpty(), path.orEmpty(), fileGlob,
                    caseSensitive, fixedString, maxResults.coerceIn(1, 1000)) { }
            }
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
            val entry = sessionShutdownGate.admit { mcpSessions.reserve(sessionId) } ?: return false
            val session = try {
                runBlocking {
                    TermuxRuntime.startMcpProcess(
                        context = applicationContext, command = command, envJson = envJson,
                        onOutput = { line -> mcpSessions.deliver(entry) { runCatching { callback.onOutput(sessionId, line) } } },
                        onError = { err -> mcpSessions.deliver(entry) { runCatching { callback.onError(sessionId, err) } } },
                        onClosed = { code ->
                            mcpSessions.finished(entry) { runCatching { callback.onClosed(sessionId, code) } }
                        },
                    )
                }
            } catch (failure: Throwable) {
                mcpSessions.failLaunch(entry, failure)
                throw failure
            }
            return mcpSessions.publish(entry, session)
        }

        override fun sendMcpInput(sessionId: Int, line: String?): Boolean {
            if (!isCallerAllowed() || line == null) return false
            return mcpSessions.get(sessionId)?.sendLine(line) ?: false
        }

        override fun stopMcpProcess(sessionId: Int) {
            if (isCallerAllowed()) mcpSessions.stop(sessionId)
        }

        override fun startAcpProcess(
            sessionId: Int,
            executable: String?,
            arguments: Array<out String>?,
            envJson: String?,
            cwd: String?,
            callback: IMcpCallback?,
        ): Boolean {
            if (!isCallerAllowed() || executable.isNullOrBlank() || arguments == null ||
                cwd.isNullOrBlank() || callback == null || !java.io.File(cwd).isAbsolute) return false
            val entry = sessionShutdownGate.admit { acpSessions.reserve(sessionId) } ?: return false
            val session = try {
                runBlocking {
                    TermuxRuntime.startMcpProcess(
                        context = applicationContext, command = executable, envJson = envJson,
                        onOutput = { line ->
                            if (line.length <= 192_000) acpSessions.deliver(entry) { callback.onOutput(sessionId, line) }
                            else acpSessions.stop(entry)
                        },
                        onError = {},
                        onClosed = { code ->
                            acpSessions.finished(entry) { runCatching { callback.onClosed(sessionId, code) } }
                        },
                        arguments = arguments.toList(), cwd = cwd,
                    )
                }
            } catch (failure: Throwable) {
                acpSessions.failLaunch(entry, failure)
                throw failure
            }
            return acpSessions.publish(entry, session)
        }

        override fun sendAcpInput(sessionId: Int, line: String?): Boolean {
            if (!isCallerAllowed() || line == null || line.length > 192_000) return false
            return acpSessions.get(sessionId)?.sendLine(line) ?: false
        }

        override fun stopAcpProcess(sessionId: Int) {
            if (isCallerAllowed()) acpSessions.stop(sessionId)
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
        // The service lifecycle runs on the Android main thread. A launch, pipe or Binder
        // callback may take arbitrarily long, so drain separately and fence recreated services.
        sessionShutdownGate.begin()
        Thread {
            val mcp = runCatching { mcpSessions.stopAll() }
            val acp = runCatching { acpSessions.stopAll() }
            if (mcp.isSuccess && acp.isSuccess) {
                runCatching { mcpSessions.awaitDrained(); acpSessions.awaitDrained() }
                    .onSuccess { sessionShutdownGate.complete() }
                    .onFailure { android.util.Log.e(TAG, "Runtime session drain failed", it) }
            } else {
                mcp.exceptionOrNull()?.let { android.util.Log.e(TAG, "MCP shutdown failed", it) }
                acp.exceptionOrNull()?.let { android.util.Log.e(TAG, "ACP shutdown failed", it) }
            }
        }.start()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        val sessionShutdownGate = RuntimeSessionShutdownGate()
        const val TAG = "ShellRT"
        const val PERMISSION = "cc.ptoe.messenger.runtime.permission.SHELL"
    }
}
