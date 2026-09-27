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

package cc.ptoe.messenger.domain.repository

import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import kotlinx.coroutines.flow.Flow

interface ApiRepository {

    suspend fun fetchModels(provider: Provider): List<ChatModel>

    /**
     * [tools] 非空时以 `tools` 数组随请求发送（OpenAI function calling）；
     * 模型发起的工具调用通过 [ChatStreamEvent.Done.toolCalls] 返回。
     */
    fun streamChatCompletion(
        provider: Provider,
        modelId: String,
        messages: List<Message>,
        systemPrompt: String?,
        temperature: Float,
        topP: Float,
        maxTokens: Int?,
        reasoningEffort: String? = null,
        reasoningFormat: String? = null,
        tools: List<ChatTool>? = null
    ): Flow<ChatStreamEvent>

    suspend fun createChatCompletion(
        provider: Provider,
        modelId: String,
        messages: List<Message>,
        systemPrompt: String?,
        temperature: Float,
        topP: Float,
        maxTokens: Int?,
        reasoningEffort: String? = null,
        reasoningFormat: String? = null
    ): Message
}
