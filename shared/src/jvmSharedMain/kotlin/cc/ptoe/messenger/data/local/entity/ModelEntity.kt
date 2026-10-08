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

package cc.ptoe.messenger.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "models",
    foreignKeys = [
        ForeignKey(
            entity = ProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("providerId")]
)
data class ModelEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val isEnabled: Boolean,
    /** 上下文窗口（tokens）。0 = 未知/不限（服务商未提供元数据）。 */
    val contextWindow: Long = 0,
    /** 输入/输出倍率。null = 未知（服务商未提供元数据），0 = 免费。 */
    val inputRate: Double? = null,
    val outputRate: Double? = null,
    /** 输入/输出模态（逗号分隔的 [ModelModality] code）。默认仅文本。 */
    val inputModalities: String = "text",
    val outputModalities: String = "text",
    /** 是否支持工具调用（Function Calling / Tools）。 */
    val supportsToolCalling: Boolean = false,
    /** 是否支持思考/推理。 */
    val supportsThinking: Boolean = false,
    /** 是否支持 JSON 输出（Structured Output）。 */
    val supportsJsonOutput: Boolean = false,
    /** 是否支持 temperature 采样参数。默认不支持。 */
    val supportsTemperature: Boolean = false,
    val createdAt: Long
)
