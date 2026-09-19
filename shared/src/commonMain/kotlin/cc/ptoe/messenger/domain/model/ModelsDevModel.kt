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

/**
 * 从 models.dev 获取的模型元数据（enrichment）。
 * 所有字段可空：null = models.dev 未提供/未知，保持调用方当前值。
 */
data class ModelsDevModel(
    val contextWindow: Long? = null,
    val inputModalities: Set<ModelModality>? = null,
    val outputModalities: Set<ModelModality>? = null,
    val supportsToolCalling: Boolean? = null,
    val supportsThinking: Boolean? = null,
    val supportsJsonOutput: Boolean? = null,
    val supportsTemperature: Boolean? = null,
)

/**
 * 用 models.dev 元数据"默认填充"当前模型：models.dev 提供的字段覆盖，
 * 未提供的保持不变；contextWindow 仅在当前为 0（未知）时补充。
 */
fun ChatModel.applyModelsDev(md: ModelsDevModel?): ChatModel {
    if (md == null) return this
    return copy(
        contextWindow = if (contextWindow > 0) contextWindow else (md.contextWindow ?: 0),
        inputModalities = md.inputModalities ?: inputModalities,
        outputModalities = md.outputModalities ?: outputModalities,
        supportsToolCalling = md.supportsToolCalling ?: supportsToolCalling,
        supportsThinking = md.supportsThinking ?: supportsThinking,
        supportsJsonOutput = md.supportsJsonOutput ?: supportsJsonOutput,
        supportsTemperature = md.supportsTemperature ?: supportsTemperature,
    )
}