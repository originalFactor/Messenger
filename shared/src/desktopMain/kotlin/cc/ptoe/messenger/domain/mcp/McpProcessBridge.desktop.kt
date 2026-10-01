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

package cc.ptoe.messenger.domain.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class DesktopMcpProcessBridge : McpProcessBridge {
    private var process: Process? = null
    private var writer: BufferedWriter? = null

    override suspend fun startProcess(
        command: String,
        env: Map<String, String>,
        onLine: (String) -> Unit,
        onError: (String) -> Unit,
        onClose: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
            val builder = if (isWindows) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("/bin/sh", "-c", command)
            }
            if (env.isNotEmpty()) {
                builder.environment().putAll(env)
            }
            val proc = builder.start()
            process = proc
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))

            // Thread for stdout
            Thread {
                try {
                    InputStreamReader(proc.inputStream, StandardCharsets.UTF_8).buffered().useLines { lines ->
                        for (line in lines) {
                            onLine(line)
                        }
                    }
                } catch (_: Exception) {}
                val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                onClose(code)
            }.start()

            // Thread for stderr
            Thread {
                try {
                    InputStreamReader(proc.errorStream, StandardCharsets.UTF_8).buffered().useLines { lines ->
                        for (line in lines) {
                            onError(line)
                        }
                    }
                } catch (_: Exception) {}
            }.start()

            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun sendLine(line: String): Boolean = withContext(Dispatchers.IO) {
        val w = writer ?: return@withContext false
        try {
            w.write(line)
            w.newLine()
            w.flush()
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun close(): Unit = withContext(Dispatchers.IO) {
        runCatching { writer?.close() }
        runCatching { process?.destroyForcibly() }
        writer = null
        process = null
    }
}

actual fun createPlatformMcpBridge(): McpProcessBridge = DesktopMcpProcessBridge()
