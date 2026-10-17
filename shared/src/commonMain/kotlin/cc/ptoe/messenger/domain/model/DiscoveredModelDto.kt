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

package cc.ptoe.messenger.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One model returned by the Rust core's discovery (`GET /models` plus
 * models.dev enrichment). Mirrors `messenger_sync::DiscoveredModel`.
 *
 * Discovery is core-owned so every platform sees the same ids, the same
 * context windows and the same capability flags; the UI only maps it into
 * [ChatModel] for the picker.
 */
@Serializable
data class DiscoveredModelDto(
    @SerialName("model_id") val modelId: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("context_window") val contextWindow: Long = 0,
    @SerialName("input_rate") val inputRate: Double? = null,
    @SerialName("output_rate") val outputRate: Double? = null,
    @SerialName("input_modalities") val inputModalities: String = "text",
    @SerialName("output_modalities") val outputModalities: String = "text",
    @SerialName("supports_tool_calling") val supportsToolCalling: Boolean = false,
    @SerialName("supports_thinking") val supportsThinking: Boolean = false,
    @SerialName("supports_json_output") val supportsJsonOutput: Boolean = false,
    @SerialName("supports_temperature") val supportsTemperature: Boolean = false,
) {

    /** Fresh, not-yet-persisted row for the "pick models to add" list. */
    fun toChatModel(provider: Provider): ChatModel = ChatModel(
        id = "${provider.id}:$modelId",
        providerId = provider.id,
        modelId = modelId,
        displayName = displayName.ifBlank { modelId },
        isEnabled = true,
        contextWindow = contextWindow,
        inputRate = inputRate,
        outputRate = outputRate,
        inputModalities = ModelModality.fromCsv(inputModalities),
        outputModalities = ModelModality.fromCsv(outputModalities),
        supportsToolCalling = supportsToolCalling,
        supportsThinking = supportsThinking,
        supportsJsonOutput = supportsJsonOutput,
        supportsTemperature = supportsTemperature,
        createdAt = 0
    )

    /** The subset [applyModelsDev] consumes (models.dev-only lookups). */
    fun toModelsDevModel(): ModelsDevModel = ModelsDevModel(
        contextWindow = contextWindow.takeIf { it > 0 },
        inputModalities = ModelModality.fromCsv(inputModalities),
        outputModalities = ModelModality.fromCsv(outputModalities),
        supportsToolCalling = supportsToolCalling,
        supportsThinking = supportsThinking,
        supportsJsonOutput = supportsJsonOutput,
        supportsTemperature = supportsTemperature
    )
}
