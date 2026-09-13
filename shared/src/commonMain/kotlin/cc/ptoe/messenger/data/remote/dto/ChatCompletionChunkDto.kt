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

@Serializable
data class ChatCompletionChunkDto(
    @SerialName("id") val id: String = "",
    @SerialName("choices") val choices: List<ChatChunkChoiceDto> = emptyList(),
    @SerialName("model") val model: String = "",
    /** 流末尾的用量统计块（stream_options.include_usage），choices 为空。 */
    @SerialName("usage") val usage: UsageDto? = null
)
