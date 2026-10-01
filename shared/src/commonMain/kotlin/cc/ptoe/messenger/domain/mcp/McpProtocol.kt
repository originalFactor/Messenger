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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val method: String,
    val params: JsonElement? = null
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

@Serializable
data class McpInitializeParams(
    val protocolVersion: String = "2024-11-05",
    val capabilities: McpClientCapabilities = McpClientCapabilities(),
    val clientInfo: McpImplementation = McpImplementation(name = "Messenger", version = "1.0.0")
)

@Serializable
data class McpClientCapabilities(
    val roots: JsonObject? = null,
    val sampling: JsonObject? = null
)

@Serializable
data class McpImplementation(
    val name: String,
    val version: String
)

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject
)

@Serializable
data class McpToolsListResult(
    val tools: List<McpToolDefinition> = emptyList(),
    val nextCursor: String? = null
)

@Serializable
data class McpCallToolResult(
    val content: List<McpContentPart> = emptyList(),
    val isError: Boolean = false
)

@Serializable
data class McpContentPart(
    val type: String,
    val text: String? = null
)
