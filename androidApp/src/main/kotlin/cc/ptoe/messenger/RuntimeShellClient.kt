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

package cc.ptoe.messenger

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import cc.ptoe.messenger.domain.tool.RUNTIME_PACKAGE
import cc.ptoe.messenger.domain.tool.ShellResult
import cc.ptoe.messenger.domain.tool.ShellRuntimeBridge
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import cc.ptoe.messenger.runtime.IShellCallback
import cc.ptoe.messenger.runtime.IShellService
import cc.ptoe.messenger.runtime.ToolResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AIDL client for the Messenger Runtime companion app (targetSdk 28). Shell
 * commands + workspace file operations execute in the companion's legacy
 * SELinux domain; the companion's own terminal UI (TerminalActivity) is opened
 * with an explicit intent instead of being driven from here. The binder
 * connection is held for the app lifetime; every call returns null when the
 * companion is absent — the terminal reports a not-installed state and the
 * agent tools are disabled.
 */
class RuntimeShellClient(private val context: Context) : ShellRuntimeBridge {

    private val requestIds = AtomicInteger(1)

    override fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(RUNTIME_PACKAGE, 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    // ---------------------------------------------------------------------
    // Persistent binding (session + streaming need a live connection)
    // ---------------------------------------------------------------------

    private val connectLock = Any()
    private var cached: IShellService? = null
    private var connectLatch: CompletableDeferred<IShellService?>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = IShellService.Stub.asInterface(binder)
            synchronized(connectLock) {
                cached = service
                connectLatch?.complete(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(connectLock) {
                cached = null
                connectLatch = null
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            // The framework cleans up the dead binding; the next call re-binds.
            synchronized(connectLock) {
                cached = null
                connectLatch = null
            }
        }
    }

    private suspend fun service(): IShellService? = withContext(Dispatchers.IO) {
        synchronized(connectLock) { cached }?.let { return@withContext it }
        val latch = synchronized(connectLock) {
            connectLatch ?: CompletableDeferred<IShellService?>().also {
                connectLatch = it
                val intent = Intent().setClassName(RUNTIME_PACKAGE, "$RUNTIME_PACKAGE.ShellService")
                val bound = runCatching {
                    context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)
                if (!bound) {
                    connectLatch = null
                    it.complete(null)
                }
            }
        }
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { latch.await() }
    }

    // ---------------------------------------------------------------------
    // One-shot execution (agent terminal tool)
    // ---------------------------------------------------------------------

    override suspend fun execute(
        command: String,
        timeoutMs: Long,
        workingDir: String?,
        onOutput: ((String) -> Unit)?
    ): ShellResult? = withContext(Dispatchers.IO) {
        val service = service() ?: return@withContext null
        val id = requestIds.getAndIncrement()
        val finished = CompletableDeferred<ShellResult>()
        val streamed = StringBuilder()
        val callback = object : IShellCallback.Stub() {
            override fun onOutput(requestId: Int, chunk: String?) {
                if (requestId != id || chunk == null) return
                synchronized(streamed) { streamed.append(chunk) }
                onOutput?.invoke(chunk)
            }

            override fun onFinished(requestId: Int, exitCode: Int, output: String?, timedOut: Boolean) {
                if (requestId != id) return
                val merged = synchronized(streamed) { streamed.toString() }
                finished.complete(
                    ShellResult(
                        output = merged.ifEmpty { output.orEmpty() },
                        exitCode = exitCode,
                        timedOut = timedOut
                    )
                )
            }
        }
        try {
            withTimeoutOrNull(timeoutMs + COMPLETION_GRACE_MS) {
                service.submit(id, command, workingDir, timeoutMs, callback)
                finished.await()
            } ?: ShellResult(
                output = "Runtime request timed out after ${(timeoutMs + COMPLETION_GRACE_MS) / 1000} seconds.",
                exitCode = -1,
                timedOut = true
            )
        } catch (e: CancellationException) {
            runCatching { service.cancel(id) }
            throw e
        }
    }

    // ---------------------------------------------------------------------
    // Workspace file operations
    // ---------------------------------------------------------------------

    override suspend fun workspaceGlob(root: String, pattern: String, maxResults: Int): ToolExecutionResult =
        workspaceCall { it.workspaceGlob(root, pattern, maxResults) }

    override suspend fun workspaceGrep(
        root: String,
        pattern: String,
        path: String,
        fileGlob: String?,
        caseSensitive: Boolean,
        fixedString: Boolean,
        maxResults: Int
    ): ToolExecutionResult = workspaceCall {
        it.workspaceGrep(root, pattern, path, fileGlob, caseSensitive, fixedString, maxResults)
    }

    override suspend fun workspaceRead(
        root: String,
        path: String,
        startLine: Int,
        maxLines: Int
    ): ToolExecutionResult = workspaceCall { it.workspaceRead(root, path, startLine, maxLines) }

    override suspend fun workspaceEdit(
        root: String,
        path: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean
    ): ToolExecutionResult = workspaceCall {
        it.workspaceEdit(root, path, oldText, newText, replaceAll)
    }

    override suspend fun workspaceCreate(
        root: String,
        path: String,
        content: String,
        overwrite: Boolean
    ): ToolExecutionResult = workspaceCall {
        it.workspaceCreate(root, path, content, overwrite)
    }

    private suspend fun workspaceCall(call: (IShellService) -> ToolResult?): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val result = withService { service -> call(service) }
                ?: ToolResult(output = "Messenger Runtime companion app is not available.", isError = true)
            ToolExecutionResult(output = result.output, isError = result.isError)
        }

    /** One-shot operations bind transiently (no session state to preserve). */
    private suspend fun <T> withService(block: (IShellService) -> T?): T? {
        val latch = CompletableDeferred<IShellService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = IShellService.Stub.asInterface(binder)
                latch.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent().setClassName(RUNTIME_PACKAGE, "$RUNTIME_PACKAGE.ShellService")
        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "bindService threw", e)
            false
        }
        if (!bound) return null
        try {
            val service = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { latch.await() } ?: return null
            return block(service)
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    // ---------------------------------------------------------------------
    // MCP process management
    // ---------------------------------------------------------------------

    override suspend fun startMcpProcess(
        sessionId: Int,
        command: String,
        envJson: String?,
        onOutput: (String) -> Unit,
        onError: (String) -> Unit,
        onClosed: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val service = service() ?: return@withContext false
        val callback = object : cc.ptoe.messenger.runtime.IMcpCallback.Stub() {
            override fun onOutput(reqId: Int, line: String?) {
                line?.let(onOutput)
            }

            override fun onError(reqId: Int, error: String?) {
                error?.let(onError)
            }

            override fun onClosed(reqId: Int, exitCode: Int) {
                onClosed(exitCode)
            }
        }
        try {
            service.startMcpProcess(sessionId, command, envJson, callback)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "startMcpProcess failed", e)
            false
        }
    }

    override suspend fun sendMcpInput(sessionId: Int, line: String): Boolean = withContext(Dispatchers.IO) {
        val service = service() ?: return@withContext false
        try {
            service.sendMcpInput(sessionId, line)
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun stopMcpProcess(sessionId: Int): Unit = withContext(Dispatchers.IO) {
        val service = service() ?: return@withContext
        try {
            service.stopMcpProcess(sessionId)
        } catch (_: Exception) {}
    }

    private companion object {
        const val TAG = "ShellRT"
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val COMPLETION_GRACE_MS = 15_000L
    }
}
