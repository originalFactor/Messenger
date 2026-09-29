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
import cc.ptoe.messenger.domain.tool.ShellResult
import cc.ptoe.messenger.domain.tool.ShellRuntimeBridge
import cc.ptoe.messenger.runtime.IShellCallback
import cc.ptoe.messenger.runtime.IShellService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AIDL client for the Messenger Runtime companion app (targetSdk 28, shared
 * UID). Binding to its [cc.ptoe.messenger.runtime.ShellService] routes shell
 * commands into the companion's legacy SELinux domain where executing the
 * extracted Termux bootstrap is permitted. Every method returns null (or
 * completes null) when the companion is absent — callers fall back to the
 * in-process system shell.
 */
class RuntimeShellClient(private val context: Context) : ShellRuntimeBridge {

    private val requestIds = AtomicInteger(1)
    private val active = AtomicBoolean(false)

    override fun isActive(): Boolean = active.get()

    override suspend fun ensureRuntime(): String? = withContext(Dispatchers.IO) {
        android.util.Log.i(TAG, "ensureRuntime: begin")
        active.set(false)
        // The binding must stay alive for the WHOLE exchange: unbinding early
        // turns the companion process into a cached app and the freezer
        // suspends it mid-extraction (do_freezer_trap).
        withService { service ->
            val id = requestIds.getAndIncrement()
            val finished = CompletableDeferred<String?>()
            val callback = object : IShellCallback.Stub() {
                override fun onOutput(requestId: Int, chunk: String?) = Unit
                override fun onFinished(requestId: Int, exitCode: Int, output: String?, timedOut: Boolean) {
                    if (requestId != id) return
                    if (exitCode == 0 && output != null) {
                        active.set(true)
                        finished.complete(output)
                    } else {
                        finished.complete(null)
                    }
                }
            }
            try {
                withTimeoutOrNull(ENSURE_TIMEOUT_MS) {
                    service.ensureRuntime(id, callback)
                    android.util.Log.i(TAG, "ensureRuntime: submitted id=$id, awaiting")
                    finished.await()
                }.also { if (it == null) android.util.Log.i(TAG, "ensureRuntime: timed out or failed") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "ensureRuntime: exception", e)
                null
            }
        }
    }

    override suspend fun execute(
        command: String,
        timeoutMs: Long,
        workingDir: String?,
        onOutput: ((String) -> Unit)?
    ): ShellResult? = withContext(Dispatchers.IO) {
        withService { service ->
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
            // Caller stopped the command: tell the service to kill the process.
            runCatching { service.cancel(id) }
            throw e
        }
        }
    }

    private suspend fun <T> withService(block: suspend (IShellService) -> T?): T? {
        val latch = CompletableDeferred<IShellService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = IShellService.Stub.asInterface(binder)
                if (service != null) latch.complete(service) else latch.complete(null)
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
        android.util.Log.i(TAG, "bindService -> $bound")
        if (!bound) return null
        try {
            val service = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { latch.await() } ?: return null
            return block(service)
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private companion object {
        const val TAG = "ShellRT"
        const val RUNTIME_PACKAGE = "cc.ptoe.messenger.runtime"
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val ENSURE_TIMEOUT_MS = 120_000L
        const val COMPLETION_GRACE_MS = 15_000L
    }
}
