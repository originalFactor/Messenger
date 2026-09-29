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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The built-in terminal tool. Runs one read-only inspection command in the
 * app-private workspace. File mutation is exposed separately through the
 * consent-gated workspace edit/create tools.
 */
class TerminalTool(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) : ChatTool {

    override val name: String = TOOL_NAME

    override val description: String =
        "Run one read-only inspection command in Messenger's isolated app-private workspace and return its combined output and exit code. " +
            "Do not use shell operators, interpreters, redirection, or commands that modify files. File changes use the workspace edit/create tools."

    override val requiresUserConfirmation: Boolean = true

    override val parametersJson: String =
        """{"type":"object","properties":{"command":{"type":"string",""" +
            """"description":"One read-only inspection command to execute"}},"required":["command"]}"""

    override suspend fun execute(argumentsJson: String): ToolExecutionResult {
        val command = parseCommand(argumentsJson)
            ?: return ToolExecutionResult(
                output = "Invalid arguments: expected a JSON object with a string \"command\" field.",
                isError = true
            )
        ShellCommandPolicy.rejectionReason(command)?.let { reason ->
            return ToolExecutionResult(
                output = "Command rejected: $reason. File changes use the workspace edit/create tools.",
                isError = true
            )
        }
        val result = executeShellCommand(command, timeoutMs, workingDir = null, onOutput = null)
        val exitNote = if (result.exitCode == 0) "Exit code: 0" else "Exit code: ${result.exitCode} (command failed)"
        return ToolExecutionResult(
            output = "$exitNote\n${truncateOutput(result.output)}",
            isError = result.exitCode != 0
        )
    }

    companion object {
        const val TOOL_NAME = "terminal"
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_OUTPUT_CHARS = 10_000

        /** 解析模型给出的 arguments JSON,提取 command 字段;格式非法或非字符串返回 null。 */
        fun parseCommand(argumentsJson: String): String? {
            return try {
                val element = Json.parseToJsonElement(argumentsJson)
                val command = (element as? JsonObject)?.get("command")?.jsonPrimitive
                (command as? JsonPrimitive)?.takeIf { it.isString }?.content
            } catch (_: Exception) {
                null
            }
        }

        /** 超长输出仅保留末尾（错误信息通常在末尾），头部加截断标记。 */
        fun truncateOutput(output: String, maxChars: Int = MAX_OUTPUT_CHARS): String {
            if (output.length <= maxChars) return output.ifEmpty { "(no output)" }
            return "(output truncated, showing last $maxChars characters)\n" + output.takeLast(maxChars)
        }
    }
}
