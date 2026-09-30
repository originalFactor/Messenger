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

        override fun workspaceGlob(pattern: String?, maxResults: Int): ToolResult? = ifCallerAllowed {
            runBlocking { TermuxRuntime.workspaceGlob(applicationContext, pattern.orEmpty(), maxResults) }
        }

        override fun workspaceGrep(
            pattern: String?,
            path: String?,
            fileGlob: String?,
            caseSensitive: Boolean,
            maxResults: Int
        ): ToolResult? = ifCallerAllowed {
            runBlocking {
                TermuxRuntime.workspaceGrep(applicationContext, pattern.orEmpty(), path.orEmpty(), fileGlob, caseSensitive, maxResults)
            }
        }

        override fun workspaceRead(path: String?, startLine: Int, maxLines: Int): ToolResult? = ifCallerAllowed {
            runBlocking { TermuxRuntime.workspaceRead(applicationContext, path.orEmpty(), startLine, maxLines) }
        }

        override fun workspaceEdit(
            path: String?,
            oldText: String?,
            newText: String?,
            replaceAll: Boolean
        ): ToolResult? = ifCallerAllowed {
            runBlocking {
                TermuxRuntime.workspaceEdit(applicationContext, path.orEmpty(), oldText.orEmpty(), newText.orEmpty(), replaceAll)
            }
        }

        override fun workspaceCreate(path: String?, content: String?, overwrite: Boolean): ToolResult? = ifCallerAllowed {
            runBlocking { TermuxRuntime.workspaceCreate(applicationContext, path.orEmpty(), content.orEmpty(), overwrite) }
        }
    }

    /** Only holders of the signature permission (the main app) may call in. */
    private fun isCallerAllowed(): Boolean =
        checkCallingPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun ifCallerAllowed(block: () -> ToolResult): ToolResult =
        if (isCallerAllowed()) block() else ToolResult("Caller is not permitted to use the shell runtime.", isError = true)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i(TAG, "service created")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ShellRT"
        const val PERMISSION = "cc.ptoe.messenger.runtime.permission.SHELL"
    }
}
