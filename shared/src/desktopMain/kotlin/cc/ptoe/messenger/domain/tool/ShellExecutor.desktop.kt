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

import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible

/**
 * JVM desktop actual: Windows runs the command in Windows PowerShell
 * (powershell.exe, available on every Windows install), other desktop
 * platforms use /bin/sh. Output is decoded as UTF-8 — on Windows the command
 * preamble forces PowerShell's [Console]::OutputEncoding to UTF-8 so CJK
 * locales don't mojibake through the legacy OEM codepage.
 *
 * The command runs in the agent workspace by default, mirroring the Android
 * runtime: the workspace tools address files relative to that directory, so
 * the terminal must share it or a file just created by `create` is invisible
 * to `cat`.
 *
 * [timeoutMs] bounds the whole execution and cancelling the caller terminates
 * the process. Both paths MUST kill the process, because on Windows a process
 * holds its stdout pipe open and a reader can never reach EOF while it lives —
 * a single blocking `waitFor` therefore hung the whole turn.
 */
actual suspend fun executeShellCommand(
    command: String,
    timeoutMs: Long,
    workingDir: String?,
    onOutput: ((String) -> Unit)?
): ShellResult {
    val process = try {
        startProcess(command, workingDir)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        return ShellResult(output = "Failed to execute command: ${e.message}", exitCode = -1)
    }
    return try {
        coroutineScope {
            val drained = async(Dispatchers.IO) { drainOutput(process, onOutput) }
            // waitFor is interruptible, so the timeout and caller cancellation
            // stop the wait instead of blocking on the process.
            val exited = try {
                runInterruptible {
                    process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                }
            } finally {
                // MUST run before this scope awaits `drained`. Killing the
                // process closes the pipe, which is what unblocks the reader;
                // leaving it alive would deadlock the scope. No-op when the
                // process already exited normally.
                terminate(process)
            }
            if (exited) {
                ShellResult(output = drained.await(), exitCode = process.exitValue())
            } else {
                ShellResult(
                    output = "Command timed out after ${timeoutMs / 1000} seconds and was terminated.",
                    exitCode = -1,
                    timedOut = true
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ShellResult(output = "Failed to execute command: ${e.message}", exitCode = -1)
    }
}

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
    // Default to the agent workspace, mirroring the Android runtime: the
    // workspace tools create files relative to it, so a terminal command must
    // start there too or `cat <path-just-created>` cannot see them. A caller
    // supplied directory only wins when it actually exists.
    val directory = workingDir?.let { java.io.File(it) }?.takeIf { it.isDirectory } ?: agentWorkspace
    builder.directory(directory)
    return builder.redirectErrorStream(true).start()
}

/**
 * 读取合并后的输出直到 EOF；内存预算超限时丢弃头部，只保留滚动尾部。
 * 进程被超时终止时读取会以 I/O 异常收尾 —— 此时返回已读到的部分而非上抛，
 * 否则该异常会顺着 coroutineScope 冒泡、把正常的超时结果变成未知错误。
 */
private fun drainOutput(process: Process, onOutput: ((String) -> Unit)?): String {
    val sb = StringBuilder()
    val buffer = CharArray(8192)
    val reader = InputStreamReader(process.inputStream, Charsets.UTF_8)
    try {
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            onOutput?.invoke(String(buffer, 0, n))
            sb.append(buffer, 0, n)
            if (sb.length > 1_100_000) {
                sb.delete(0, sb.length - 1_000_000)
            }
        }
    } catch (_: java.io.IOException) {
        // 进程被杀导致管道关闭；返回已读内容。
    } finally {
        runCatching { reader.close() }
    }
    return sb.toString()
}

/** 温和终止，超时才强制；对已退出的进程是 no-op。 */
private fun terminate(process: Process) {
    if (!process.isAlive) return
    process.destroy()
    if (!runCatching { process.waitFor(250L, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
        process.destroyForcibly()
        runCatching { process.waitFor(2L, TimeUnit.SECONDS) }
    }
}
