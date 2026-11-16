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

package cc.ptoe.messenger

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import cc.ptoe.messenger.core.AcpProcessChannel
import cc.ptoe.messenger.data.chat.InMemoryWorkspaceTracker
import cc.ptoe.messenger.data.chat.TrackedWorkspaceHost
import cc.ptoe.messenger.data.chat.TrackedWorkspaceStatus
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.runtime.IMcpCallback
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import cc.ptoe.messenger.domain.tool.RUNTIME_PACKAGE
import cc.ptoe.messenger.domain.tool.ShellResult
import cc.ptoe.messenger.domain.tool.ShellRuntimeBridge
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import cc.ptoe.messenger.domain.tool.WorkspaceOperation
import cc.ptoe.messenger.domain.tool.WorkspaceTool
import cc.ptoe.messenger.runtime.IShellCallback
import cc.ptoe.messenger.runtime.IShellService
import cc.ptoe.messenger.runtime.ToolResult
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
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
class RuntimeShellClient(private val context: Context) : ShellRuntimeBridge, TrackedWorkspaceHost {

    private val workspaceTracker = InMemoryWorkspaceTracker()
    @Volatile private var safeGrepAvailable = false
    override val supportedOperations: Set<String>
        get() = if (safeGrepAvailable) setOf("glob", "grep", "read", "edit", "create")
                else setOf("glob", "read", "edit", "create")

    @Volatile
    override var protocolAvailable: Boolean = false
        private set

    /** Synchronous workspace AIDL is tracked in this process, never by Runtime's journal. */
    suspend fun refreshTrackedProtocol(): Boolean = withContext(Dispatchers.IO) {
        val remote = try { service() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val grep = try { remote?.safeWorkspaceProtocol() == 1 }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        synchronized(connectLock) {
            val available = remote != null && cached === remote && remote.asBinder().isBinderAlive
            if (available) { safeGrepAvailable = grep; protocolAvailable = true } else workspaceDisconnected()
            available
        }
    }

    override suspend fun execute(token: String, workspace: String, call: ToolCallData): ToolExecutionResult {
        check(protocolAvailable) { "Runtime workspace connection is unavailable" }
        return workspaceTracker.run(token) {
            check(protocolAvailable) { "Runtime workspace connection is unavailable" }
            val remote = service() ?: error("Runtime workspace connection is unavailable")
            check(protocolAvailable && remote.asBinder().isBinderAlive) { "Runtime workspace connection is unavailable" }
            require(call.name in supportedOperations) { "Unsupported workspace operation" }
            WorkspaceTool(call.name, workspace).executeWith(call.arguments) { operation, root ->
                withContext(Dispatchers.IO) {
                    check(protocolAvailable && remote.asBinder().isBinderAlive) { "Runtime workspace connection is unavailable" }
                    val result = try {
                        when (operation) {
                            is WorkspaceOperation.Glob -> remote.workspaceGlob(root, operation.pattern, operation.maxResults)
                            is WorkspaceOperation.Grep -> {
                                check(safeGrepAvailable) { "Runtime safe grep is unavailable" }
                                remote.safeWorkspaceGrep(root, operation.pattern, operation.path, operation.fileGlob,
                                    operation.caseSensitive, operation.fixedString, operation.maxResults)
                            }
                            is WorkspaceOperation.Read -> remote.workspaceRead(root, operation.path, operation.startLine, operation.maxLines)
                            is WorkspaceOperation.Edit -> remote.workspaceEdit(root, operation.path, operation.oldText, operation.newText, operation.replaceAll)
                            is WorkspaceOperation.Create -> remote.workspaceCreate(root, operation.path, operation.content, operation.overwrite)
                        }
                    } catch (error: RemoteException) {
                        workspaceDisconnected()
                        throw error
                    }
                    requireNotNull(result) { "Runtime workspace operation was rejected" }
                    ToolExecutionResult(result.output, result.isError)
                }
            }
        }
    }

    override suspend fun status(token: String): TrackedWorkspaceStatus =
        if (protocolAvailable) workspaceTracker.status(token) else TrackedWorkspaceStatus.UNKNOWN

    override suspend fun cancelAndAwaitStopped(token: String): TrackedWorkspaceStatus {
        if (!protocolAvailable) return TrackedWorkspaceStatus.UNKNOWN
        val status = workspaceTracker.cancelAndAwaitStopped(token)
        return if (protocolAvailable) status else TrackedWorkspaceStatus.UNKNOWN
    }

    private fun workspaceDisconnected() {
        protocolAvailable = false
        safeGrepAvailable = false
        workspaceTracker.disconnected()
    }

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
    private val mcpConnections = mutableMapOf<Int, McpConnection>()

    /** One session stays bound to the Runtime process that accepted its start call. */
    private inner class McpConnection(
        val sessionId: Int,
        val service: IShellService,
        val onClosed: (Int) -> Unit,
        val onConnectionLost: () -> Unit,
    ) {
        val binder: IBinder = service.asBinder()
        private val ended = AtomicBoolean(false)
        val death = IBinder.DeathRecipient { lost() }

        private fun end(notify: () -> Unit) {
            if (!ended.compareAndSet(false, true)) return
            synchronized(connectLock) {
                if (mcpConnections[sessionId] === this) mcpConnections.remove(sessionId)
            }
            runCatching { binder.unlinkToDeath(death, 0) }
            notify()
        }

        fun closed(code: Int) = end { onClosed(code) }
        fun lost() = end(onConnectionLost)
        fun rejected() = end { }
        fun isEnded(): Boolean = ended.get()
    }

    private fun disconnectMcp(binder: IBinder?) {
        if (binder == null) return
        val sessions = synchronized(connectLock) { mcpConnections.values.filter { it.binder == binder } }
        sessions.forEach { session -> runCatching { session.lost() } }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = IShellService.Stub.asInterface(binder)
            synchronized(connectLock) {
                cached = service
                connectLatch?.complete(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val binder = synchronized(connectLock) {
                val previous = cached?.asBinder()
                workspaceDisconnected()
                cached = null
                connectLatch = null
                previous
            }
            disconnectMcp(binder)
        }

        override fun onBindingDied(name: ComponentName?) {
            // The framework cleans up the dead binding; the next call re-binds.
            val binder = synchronized(connectLock) {
                val previous = cached?.asBinder()
                workspaceDisconnected()
                cached = null
                connectLatch = null
                previous
            }
            disconnectMcp(binder)
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

    override suspend fun temporaryChatWorkspace(conversationId: String): String = withContext(Dispatchers.IO) {
        val service = service() ?: error("Messenger Runtime is unavailable.")
        service.temporaryChatWorkspace(conversationId) ?: error("Could not create temporary chat workspace.")
    }

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

    suspend fun runAcpProcess(channel: AcpProcessChannel) = withContext(Dispatchers.IO) {
        val service = service() ?: error("Messenger Runtime is unavailable.")
        val id = requestIds.getAndIncrement()
        val stdout = Channel<String>(32)
        val callback = object : IMcpCallback.Stub() {
            override fun onOutput(reqId: Int, line: String?) {
                if (line != null && !stdout.trySend(line).isSuccess) {
                    stdout.close(IllegalStateException("ACP stdout buffer exceeded"))
                }
            }
            override fun onError(reqId: Int, error: String?) = Unit
            override fun onClosed(reqId: Int, exitCode: Int) { stdout.close() }
        }
        val death = IBinder.DeathRecipient { stdout.close() }
        val binder = service.asBinder()
        binder.linkToDeath(death, 0)
        try {
            check(service.startAcpProcess(id, channel.command, channel.arguments.toTypedArray(),
                channel.envJson, channel.cwd, callback)) {
                "Messenger Runtime could not start the ACP agent."
            }
            coroutineScope {
                val input = launch {
                    try {
                        channel.readInput { line -> check(service.sendAcpInput(id, line)) }
                    } finally {
                        stdout.close()
                    }
                }
                try {
                    for (line in stdout) channel.output(line)
                } finally {
                    input.cancel()
                }
            }
        } finally {
            binder.unlinkToDeath(death, 0)
            runCatching { service.stopAcpProcess(id) }
            stdout.cancel()
        }
    }

    override suspend fun startMcpProcess(
        sessionId: Int,
        command: String,
        envJson: String?,
        onOutput: (String) -> Unit,
        onError: (String) -> Unit,
        onClosed: (Int) -> Unit,
        onConnectionLost: () -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val service = service() ?: return@withContext false
        val session = McpConnection(sessionId, service, onClosed, onConnectionLost)
        synchronized(connectLock) {
            if (cached?.asBinder() != session.binder) return@withContext false
            check(mcpConnections.putIfAbsent(sessionId, session) == null) { "Duplicate MCP session" }
        }
        val callback = object : cc.ptoe.messenger.runtime.IMcpCallback.Stub() {
            override fun onOutput(reqId: Int, line: String?) {
                if (reqId == sessionId && !session.isEnded()) line?.let(onOutput)
            }

            override fun onError(reqId: Int, error: String?) {
                if (reqId == sessionId && !session.isEnded()) error?.let(onError)
            }

            override fun onClosed(reqId: Int, exitCode: Int) {
                if (reqId == sessionId) session.closed(exitCode)
            }
        }
        try {
            session.binder.linkToDeath(session.death, 0)
            if (session.isEnded()) return@withContext false
            service.startMcpProcess(sessionId, command, envJson, callback).also { started ->
                if (!started) session.rejected()
            }
        } catch (e: Exception) {
            // A failed Binder transaction can have started the child before the reply was lost.
            session.lost()
            android.util.Log.e(TAG, "startMcpProcess failed", e)
            false
        }
    }

    override suspend fun sendMcpInput(sessionId: Int, line: String): Boolean = withContext(Dispatchers.IO) {
        val session = synchronized(connectLock) { mcpConnections[sessionId] } ?: return@withContext false
        try {
            session.service.sendMcpInput(sessionId, line)
        } catch (e: Exception) {
            session.lost()
            false
        }
    }

    override suspend fun stopMcpProcess(sessionId: Int): Unit = withContext(Dispatchers.IO) {
        val session = synchronized(connectLock) { mcpConnections[sessionId] } ?: return@withContext
        try {
            session.service.stopMcpProcess(sessionId)
        } catch (error: Exception) {
            session.lost()
        }
    }

    private companion object {
        const val TAG = "ShellRT"
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val COMPLETION_GRACE_MS = 15_000L
    }
}
