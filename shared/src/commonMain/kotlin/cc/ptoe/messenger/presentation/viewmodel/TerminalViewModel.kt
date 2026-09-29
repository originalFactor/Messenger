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

package cc.ptoe.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlin.reflect.KClass
import cc.ptoe.messenger.domain.tool.ensureShellRuntime
import cc.ptoe.messenger.domain.tool.executeShellCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 用户手动操作的内置终端（设置 → 终端）。与 Agent 的只读 terminal 工具不同，
 * 这里的命令由用户逐条输入、无策略过滤；运行时仍是同一个应用私有 shell
 * （Android 为打包的 Termux bootstrap）。每条命令独立起进程，目录通过解析
 * cd 命令的 pwd 回显跨次保持。
 */
class TerminalViewModel : ViewModel() {

    sealed interface TerminalEntry {
        data class Command(val text: String) : TerminalEntry
        data class Output(val text: String) : TerminalEntry

        /** 命令以非零退出码结束。 */
        data class Status(val exitCode: Int) : TerminalEntry

        /** 命令被用户手动终止。 */
        data object Terminated : TerminalEntry

        /** 命令超时，进程已被强制结束。 */
        data object TimedOut : TerminalEntry

        data class Info(val text: String) : TerminalEntry
    }

    enum class RuntimeState { Loading, Ready, Failed }

    private val _transcript = MutableStateFlow<List<TerminalEntry>>(emptyList())
    val transcript: StateFlow<List<TerminalEntry>> = _transcript.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _cwd = MutableStateFlow("")
    val cwd: StateFlow<String> = _cwd.asStateFlow()

    private val _runtimeState = MutableStateFlow(RuntimeState.Loading)
    val runtimeState: StateFlow<RuntimeState> = _runtimeState.asStateFlow()

    private val _runtimeError = MutableStateFlow("")
    val runtimeError: StateFlow<String> = _runtimeError.asStateFlow()

    /** 命令历史（旧 → 新，去重），用于输入框旁的历史下拉。 */
    private val history = ArrayDeque<String>()
    val historyItems: List<String> get() = history.toList()

    private var runJob: Job? = null

    init {
        prepareRuntime()
    }

    /** 预热平台 shell 运行时（Android 首次会解包 bootstrap）；失败可重试。 */
    fun prepareRuntime() {
        _runtimeState.value = RuntimeState.Loading
        _runtimeError.value = ""
        viewModelScope.launch {
            try {
                val workspace = ensureShellRuntime()
                _cwd.value = workspace
                _transcript.update { current ->
                    if (current.any { it is TerminalEntry.Info }) current
                    else current + TerminalEntry.Info(workspace)
                }
                _runtimeState.value = RuntimeState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _runtimeError.value = e.message ?: e.toString()
                _runtimeState.value = RuntimeState.Failed
            }
        }
    }

    fun onInputChange(value: String) {
        _input.value = value.filter { it != '\n' && it != '\r' }
    }

    fun submit() {
        val command = _input.value.trim()
        if (command.isEmpty() || _running.value || _runtimeState.value != RuntimeState.Ready) return
        _input.value = ""
        rememberHistory(command)
        appendEntry(TerminalEntry.Command(command))
        // cd 命令需要跨次保持目录：末尾追加 pwd，把落点目录回显出来解析。
        val isCd = command == "cd" || command.startsWith("cd ")
        val effective = if (isCd) "$command\npwd" else command
        _running.value = true
        runJob = viewModelScope.launch {
            try {
                val result = executeShellCommand(
                    command = effective,
                    timeoutMs = TERMINAL_TIMEOUT_MS,
                    workingDir = _cwd.value.takeIf { it.isNotBlank() },
                    onOutput = { chunk -> appendOutput(chunk) }
                )
                if (isCd) {
                    result.output.lineSequence().lastOrNull { it.isNotBlank() }?.let {
                        _cwd.value = it.trim()
                    }
                }
                if (result.timedOut) {
                    appendEntry(TerminalEntry.TimedOut)
                } else if (result.exitCode > 0 && !isCd) {
                    appendEntry(TerminalEntry.Status(result.exitCode))
                }
                // 超时/启动失败等路径没有流式输出（最后一条还不是 Output），
                // 把最终 output 兜底显示；成功路径已在流式回调里展示过。
                val lastEntry = _transcript.value.lastOrNull()
                if (result.exitCode != 0 && result.output.isNotBlank() && lastEntry !is TerminalEntry.Output) {
                    appendOutput(result.output)
                }
            } catch (e: CancellationException) {
                appendEntry(TerminalEntry.Terminated)
                throw e
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() {
        runJob?.cancel()
    }

    fun clearTranscript() {
        if (!_running.value) _transcript.value = emptyList()
    }

    private fun rememberHistory(command: String) {
        history.remove(command)
        history.addLast(command)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private fun appendEntry(entry: TerminalEntry) {
        _transcript.update { current -> capped(current + entry) }
    }

    private fun appendOutput(chunk: String) {
        _transcript.update { current ->
            val updated = when (val last = current.lastOrNull()) {
                is TerminalEntry.Output -> current.dropLast(1) + TerminalEntry.Output(capEntryText(last.text + chunk))
                else -> current + TerminalEntry.Output(capEntryText(chunk))
            }
            capped(updated)
        }
    }

    private fun capped(entries: List<TerminalEntry>): List<TerminalEntry> =
        if (entries.size > MAX_TRANSCRIPT_ENTRIES) entries.takeLast(MAX_TRANSCRIPT_ENTRIES) else entries

    private fun capEntryText(text: String): String =
        if (text.length > MAX_OUTPUT_ENTRY_CHARS) text.takeLast(MAX_OUTPUT_ENTRY_CHARS) else text

    companion object {
        private const val TERMINAL_TIMEOUT_MS = 10 * 60_000L
        private const val MAX_TRANSCRIPT_ENTRIES = 600
        private const val MAX_OUTPUT_ENTRY_CHARS = 200_000
        private const val MAX_HISTORY = 50

        fun provideFactory(): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T =
                TerminalViewModel() as T
        }
    }
}
