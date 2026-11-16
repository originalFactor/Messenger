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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

private val androidSessionIds = AtomicInteger(1000)

class AndroidMcpProcessBridge : McpProcessBridge {
    private val sessionId = androidSessionIds.incrementAndGet()
    @Volatile private var started = false
    private val stopped = CompletableDeferred<Unit>()

    override suspend fun startProcess(
        command: String,
        env: Map<String, String>,
        onLine: (String) -> Unit,
        onError: (String) -> Unit,
        onClose: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val bridge = ShellRuntimeRegistry.bridge ?: return@withContext false
        val envJson = if (env.isNotEmpty()) {
            Json.encodeToString(JsonObject.serializer(), JsonObject(env.mapValues { JsonPrimitive(it.value) }))
        } else null

        // A Binder call can finish creating the process after its caller is cancelled.
        // Record the result before allowing shutdown to decide whether to wait.
        withContext(NonCancellable) {
            bridge.startMcpProcess(sessionId, command, envJson, onLine, onError) { code ->
                try { onClose(code) } finally { stopped.complete(Unit) }
            }.also { launched -> started = launched }
        }
    }

    override suspend fun sendLine(line: String): Boolean = withContext(Dispatchers.IO) {
        val bridge = ShellRuntimeRegistry.bridge ?: return@withContext false
        bridge.sendMcpInput(sessionId, line)
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        ShellRuntimeRegistry.bridge?.stopMcpProcess(sessionId)
        // The legacy stop call is one-way evidence only. Wait for the process exit
        // callback; a lost connection must not be mistaken for a confirmed stop.
        if (started) stopped.await()
    }
}

actual fun createPlatformMcpBridge(): McpProcessBridge = AndroidMcpProcessBridge()
