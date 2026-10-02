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

package cc.ptoe.messenger.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI tool call, used in three places with the same shape:
 * - inbound streaming deltas (`delta.tool_calls`, [index] required, id/name
 *   arrive on the first fragment and [ToolCallFunctionDto.arguments] is split
 *   across chunks),
 * - inbound non-streaming responses (`message.tool_calls`),
 * - outbound assistant history echo when re-sending a tool-call turn.
 */
@Serializable
data class ToolCallDto(
    @SerialName("index") val index: Int? = null,
    @SerialName("id") val id: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("function") val function: ToolCallFunctionDto? = null
)

@Serializable
data class ToolCallFunctionDto(
    @SerialName("name") val name: String? = null,
    @SerialName("arguments") val arguments: String? = null
)

/**
 * Tool schema advertised in the request `tools` array. [type] MUST be sent
 * on the wire ("function") — OpenAI-compatible backends silently drop
 * type-less tool entries, so it is a required parameter (a default value
 * would be omitted by kotlinx.serialization's encodeDefaults=false).
 * [parameters] carries the raw JSON Schema object for the function arguments.
 */
@Serializable
data class ToolSpecDto(
    @SerialName("type") val type: String,
    @SerialName("function") val function: ToolSpecFunctionDto
)

@Serializable
data class ToolSpecFunctionDto(
    @SerialName("name") val name: String,
    @SerialName("description") val description: String,
    @SerialName("parameters") val parameters: JsonElement
)
