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

data class ChatModel(
    val id: String,
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val isEnabled: Boolean,
    /** 上下文窗口（tokens）。0 = 未知/不限（服务商未提供元数据）。 */
    val contextWindow: Long = 0,
    /** 输入/输出倍率。null = 未知（服务商未提供元数据），0 = 免费。 */
    val inputRate: Double? = null,
    val outputRate: Double? = null,
    val createdAt: Long
)
