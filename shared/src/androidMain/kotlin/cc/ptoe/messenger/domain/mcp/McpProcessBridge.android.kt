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

package cc.ptoe.messenger.domain.mcp

import cc.ptoe.messenger.domain.tool.ShellRuntimeRegistry
import cc.ptoe.messenger.domain.tool.ShellRuntimeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

private val androidSessionIds = AtomicInteger(1000)

class AndroidMcpProcessBridge internal constructor(private val stopTimeoutMillis: Long) : McpProcessBridge {
    constructor() : this(10_000)
    private val sessionId = androidSessionIds.incrementAndGet()
    @Volatile private var started = false
    @Volatile private var runtime: ShellRuntimeBridge? = null
    private val stopped = CompletableDeferred<Unit>()
    private val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopLock = Any()
    private var startCall: Deferred<Boolean>? = null
    private var stopCall: Deferred<Unit>? = null

    override suspend fun startProcess(
        command: String,
        env: Map<String, String>,
        onLine: (String) -> Unit,
        onError: (String) -> Unit,
        onClose: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val bridge = ShellRuntimeRegistry.bridge ?: return@withContext false
        runtime = bridge
        val envJson = if (env.isNotEmpty()) {
            Json.encodeToString(JsonObject.serializer(), JsonObject(env.mapValues { JsonPrimitive(it.value) }))
        } else null

        // A cancelled caller cannot cancel a synchronous Binder transaction. Retain
        // its actual completion so close can detect a process created afterwards.
        val call = synchronized(stopLock) {
            check(startCall == null) { "MCP process already started" }
            stopScope.async(start = CoroutineStart.LAZY) {
                bridge.startMcpProcess(sessionId, command, envJson, onLine, onError,
                    onClosed = { code -> try { onClose(code) } finally { stopped.complete(Unit) } },
                    onConnectionLost = {
                        stopped.completeExceptionally(IllegalStateException("Runtime connection was lost; MCP process stop is unverified"))
                    }).also { launched -> started = launched }
            }.also { startCall = it; it.start() }
        }
        call.await()
    }

    override suspend fun sendLine(line: String): Boolean = withContext(Dispatchers.IO) {
        val bridge = runtime ?: return@withContext false
        bridge.sendMcpInput(sessionId, line)
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        val start = synchronized(stopLock) { startCall }
        if (start != null) {
            val finished = withTimeoutOrNull(stopTimeoutMillis) { start.await(); true } ?: false
            check(finished) { "Runtime did not finish starting MCP; stop is unverified" }
        }
        // Even if start returned false, Binder failure may leave an unknown outcome.
        val previous = synchronized(stopLock) { stopCall }
        if (stopped.isCompleted && previous == null) { stopped.await(); return@withContext }
        if (!started && previous == null) return@withContext
        // Binder calls may ignore cancellation. Reuse the same call on retry, and never
        // report success until both it and the direct-process exit callback have completed.
        val call = synchronized(stopLock) {
            stopCall ?: run {
                val bridge = runtime
                    ?: error("Runtime connection is unavailable; MCP process stop is unverified")
                stopScope.async { bridge.stopMcpProcess(sessionId) }.also { stopCall = it }
            }
        }
        val verified = withTimeoutOrNull(stopTimeoutMillis) { stopped.await(); call.await(); true } ?: false
        check(verified) { "Runtime did not confirm MCP process exit; stop is unverified" }
    }
}

actual fun createPlatformMcpBridge(): McpProcessBridge = AndroidMcpProcessBridge()
