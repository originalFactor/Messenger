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

/**
 * Browsers cannot spawn processes, so stdio MCP servers never start here
 * (`McpManager` treats a `false` start as a failed server). Remote MCP
 * servers over SSE/HTTP remain reachable — that path does not use this
 * bridge at all.
 */
private object WebMcpProcessBridge : McpProcessBridge {
    override suspend fun startProcess(
        command: String,
        env: Map<String, String>,
        onLine: (String) -> Unit,
        onError: (String) -> Unit,
        onClose: (Int) -> Unit
    ): Boolean = false

    override suspend fun sendLine(line: String): Boolean = false

    override suspend fun close() = Unit
}

actual fun createPlatformMcpBridge(): McpProcessBridge = WebMcpProcessBridge
