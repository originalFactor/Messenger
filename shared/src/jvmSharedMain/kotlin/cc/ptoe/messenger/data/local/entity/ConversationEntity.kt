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
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = AgentEntity::class,
            parentColumns = ["id"],
            childColumns = ["agentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("agentId"), Index("projectId")]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val providerId: String,
    val agentId: String,
    /** 所属项目；null = 普通会话（不声明工作区工具）。 */
    val projectId: String? = null,
    val overrideModelId: String? = null,
    val overrideTemperature: Float? = null,
    val overrideTopP: Float? = null,
    val overrideMaxTokens: Int? = null,
    val overrideReasoningEffort: String? = null,
    /** 工具总开关的会话级覆盖（null = 跟随 Agent 生效值）。 */
    val overrideToolsEnabled: Boolean? = null,
    /** 每工具开关的会话级覆盖 JSON（null = 跟随 Agent；空对象 = 默认全开）。 */
    val overrideToolsConfig: String? = null,
    /** 本会话的 Agent 模式（只读/可写）。 */
    val writable: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessage: String?,
    val reasoningFormat: String? = null,
    /** 80% 上下文自动摘要状态（详见 domain Conversation 注释）。 */
    val contextSummary: String? = null,
    val contextSummaryUntil: Long = 0,
    val contextTokens: Long = 0,
    val contextTokensAt: Long = 0
)
