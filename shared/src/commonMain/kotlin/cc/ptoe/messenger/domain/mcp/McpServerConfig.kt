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

enum class McpTransportType {
    STDIO,
    SSE
}

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val transportType: McpTransportType,
    val isEnabled: Boolean = true,
    // Stdio / Command transport
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    // SSE transport
    val url: String = "",
    val headers: Map<String, String> = emptyMap()
)
