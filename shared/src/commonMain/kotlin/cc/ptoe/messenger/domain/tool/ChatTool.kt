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

/**
 * A built-in tool the model can call through the OpenAI function-calling
 * protocol. Implementations are pure Kotlin; platform-specific behavior is
 * reached through expect/actual helpers (see [ShellExecutor]).
 */
interface ChatTool {
    /** Unique function name sent to the model. */
    val name: String

    /** Natural-language description the model uses to decide when to call the tool. */
    val description: String

    /** JSON Schema object (as a JSON string) describing the accepted arguments. */
    val parametersJson: String

    /**
     * Executes the tool with the raw JSON arguments string produced by the
     * model. Must never throw for invalid arguments — return an error
     * [ToolExecutionResult] instead so the model can recover.
     */
    suspend fun execute(argumentsJson: String): ToolExecutionResult
}

/** Result handed back to the model as the `tool` role message content. */
data class ToolExecutionResult(
    val output: String,
    val isError: Boolean = false
)
