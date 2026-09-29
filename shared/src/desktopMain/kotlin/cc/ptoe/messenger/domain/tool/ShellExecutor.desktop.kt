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

package cc.ptoe.messenger.domain.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

/**
 * JVM desktop actual: Windows runs the command in Windows PowerShell
 * (powershell.exe, available on every Windows install), other desktop
 * platforms use /bin/sh. Output is decoded as UTF-8 — on Windows the command
 * preamble forces PowerShell's [Console]::OutputEncoding to UTF-8 so CJK
 * locales don't mojibake through the legacy OEM codepage.
 */
actual suspend fun executeShellCommand(
    command: String,
    timeoutMs: Long,
    workingDir: String?,
    onOutput: ((String) -> Unit)?
): ShellResult {
    val processRef = AtomicReference<Process?>(null)
    val result = try {
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) {
                val process = startProcess(command, workingDir)
                processRef.set(process)
                ShellResult(output = drainOutput(process, onOutput), exitCode = process.waitFor())
            }
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ShellResult(output = "Failed to execute command: ${e.message}", exitCode = -1)
    } finally {
        // 成功时是 no-op；超时/取消时终止进程以解除被阻塞的读取线程。
        processRef.get()?.destroyForcibly()
    }
    return result
        ?: ShellResult(
            output = "Command timed out after ${timeoutMs / 1000} seconds and was terminated.",
            exitCode = -1,
            timedOut = true
        )
}

actual suspend fun ensureShellRuntime(): String = agentWorkspace.canonicalFile.absolutePath

actual fun isEnhancedShellRuntimeActive(): Boolean = false

actual fun isShellRuntimeAvailable(): Boolean = true

private fun startProcess(command: String, workingDir: String?): Process {
    val isWindows = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
    val builder = if (isWindows) {
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-Command", "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; $command"
        )
    } else {
        ProcessBuilder("/bin/sh", "-c", command)
    }
    if (workingDir != null) {
        val directory = java.io.File(workingDir)
        if (directory.isDirectory) builder.directory(directory)
    }
    return builder.redirectErrorStream(true).start()
}

/** 读取合并后的输出直到 EOF；内存预算超限时丢弃头部，只保留滚动尾部。 */
private fun drainOutput(process: Process, onOutput: ((String) -> Unit)?): String {
    val sb = StringBuilder()
    val buffer = CharArray(8192)
    InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            onOutput?.invoke(String(buffer, 0, n))
            sb.append(buffer, 0, n)
            if (sb.length > 1_100_000) {
                sb.delete(0, sb.length - 1_000_000)
            }
        }
    }
    return sb.toString()
}
