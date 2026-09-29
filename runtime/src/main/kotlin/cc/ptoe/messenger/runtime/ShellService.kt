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
import android.os.Binder
import android.os.IBinder
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AIDL facade over [TermuxRuntime]. The main Messenger app (same shared UID)
 * binds to this service and routes shell commands here so they execute in
 * this app's legacy SELinux domain. Callers sharing any other UID are
 * rejected — the UID check is the entire permission model.
 */
class ShellService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap<Int, Job>()

    private val binder = object : IShellService.Stub() {
        override fun ensureRuntime(requestId: Int, callback: IShellCallback) {
            if (!isSameUid()) {
                android.util.Log.w(TAG, "ensureRuntime rejected: callingUid=${Binder.getCallingUid()} myUid=${Process.myUid()}")
                return
            }
            android.util.Log.i(TAG, "ensureRuntime($requestId) start")
            scope.launch {
                try {
                    val workspace = TermuxRuntime.ensureWorkspace(applicationContext)
                    android.util.Log.i(TAG, "ensureRuntime($requestId) ok: $workspace")
                    callback.onFinished(requestId, 0, workspace, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    android.util.Log.w(TAG, "ensureRuntime($requestId) failed", e)
                    runCatching { callback.onFinished(requestId, -1, e.message ?: "runtime init failed", false) }
                }
            }
        }

        override fun submit(
            requestId: Int,
            command: String,
            workingDir: String?,
            timeoutMs: Long,
            callback: IShellCallback
        ) {
            if (!isSameUid()) {
                android.util.Log.w(TAG, "submit rejected: callingUid=${Binder.getCallingUid()} myUid=${Process.myUid()}")
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
    }

    /** Only the main app (same shared UID) may drive the shell. */
    private fun isSameUid(): Boolean = Binder.getCallingUid() == Process.myUid()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        android.util.Log.i(TAG, "service created, uid=${Process.myUid()}")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ShellRT"
    }
}
