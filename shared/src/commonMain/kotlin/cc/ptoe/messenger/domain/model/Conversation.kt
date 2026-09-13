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

data class Conversation(
    val id: String,
    val title: String,
    val providerId: String,
    val agentId: String,
    val overrideModelId: String? = null,
    val overrideTemperature: Float? = null,
    val overrideTopP: Float? = null,
    val overrideMaxTokens: Int? = null,
    val overrideReasoningEffort: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessage: String?,
    val reasoningFormat: String? = null,
    /**
     * 80% 上下文自动摘要：timestamp < [contextSummaryUntil] 的历史消息
     * 已折叠进 [contextSummary]，请求 API 时以 system 消息形式随摘要后的
     * 近期消息一起发送。[contextTokens] / [contextTokensAt] 记录最近一次
     * 用量记账（prompt+completion）及其时间戳，用于下次发送前的阈值判断。
     */
    val contextSummary: String? = null,
    val contextSummaryUntil: Long = 0,
    val contextTokens: Long = 0,
    val contextTokensAt: Long = 0
)
