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

package cc.ptoe.messenger.domain.usecase

import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ApiRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.presentation.utils.stripThinkBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 首轮回复完成后为「未命名」对话生成标题：
 * 优先使用内置标题智能体（[Agent.BUILTIN_TITLE_AGENT_ID]）自带的模型配置，
 * 未配置时回退到调用方传入的当轮聊天 (provider, model)。
 *
 * 标题属装饰性输出：任何失败都静默回退为截断首条消息，不向 UI 报错。
 */
class ConversationTitleGenerator(
    private val agentRepository: AgentRepository,
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val modelRepository: ModelRepository,
    private val providerRepository: ProviderRepository,
    private val apiRepository: ApiRepository,
    private val externalScope: CoroutineScope
) {

    private val mutex = Mutex()

    /**
     * Fire-and-forget 入口：在 [externalScope] 上执行（聊天页退出不中断），
     * 内部用 [Mutex] 串行化，避免同一会话的多次 Done 事件并发写标题。
     */
    fun launchGenerateIfNeeded(
        conversationId: String,
        fallbackProvider: Provider?,
        fallbackModel: ChatModel?
    ) {
        externalScope.launch {
            mutex.withLock {
                runCatching {
                    generateIfNeeded(conversationId, fallbackProvider, fallbackModel)
                }.onFailure {
                    // 失败回退截断标题已在 generateIfNeeded 内处理；这里的异常
                    // 只可能是回退写库本身失败，静默放弃即可。
                }
            }
        }
    }

    private suspend fun generateIfNeeded(
        conversationId: String,
        fallbackProvider: Provider?,
        fallbackModel: ChatModel?
    ) {
        val conversation = conversationRepository.getById(conversationId).first() ?: return
        if (!isUntitledConversation(conversation.title)) return

        val messages = messageRepository.getByConversationId(conversationId).first()
            .filter { it.status == MessageStatus.SENT }
        val firstUser = messages.firstOrNull { it.role == MessageRole.USER } ?: return
        val firstAssistant = messages
            .firstOrNull { it.role == MessageRole.ASSISTANT && it.content.isNotBlank() } ?: return

        val titleAgent = agentRepository
            .getById(Agent.BUILTIN_TITLE_AGENT_ID).first() ?: return
        val (provider, model) = resolveTitleAgentModel(titleAgent)
            ?: run {
                val fallbackModelId = fallbackModel ?: return
                val fallbackProviderId = fallbackProvider ?: return
                fallbackProviderId to fallbackModelId
            }

        val transcript = buildString {
            append("User: ").appendLine(firstUser.content.take(TRANSCRIPT_MAX_CHARS))
            append("Assistant: ").appendLine(firstAssistant.content.take(TRANSCRIPT_MAX_CHARS))
        }

        val generated = runCatching {
            apiRepository.createChatCompletion(
                provider = provider,
                modelId = model.modelId,
                messages = listOf(
                    Message(
                        id = randomUuid(),
                        conversationId = "",
                        role = MessageRole.USER,
                        content = transcript,
                        timestamp = System.currentTimeMillis(),
                        status = MessageStatus.SENT
                    )
                ),
                systemPrompt = titleAgent.systemPrompt,
                temperature = titleAgent.temperature,
                topP = titleAgent.topP,
                maxTokens = titleAgent.maxTokens,
                reasoningEffort = null,
                reasoningFormat = null
            ).content
        }.getOrNull()?.let { sanitizeGeneratedTitle(it) }

        val title = generated?.takeIf { it.isNotBlank() }
            ?: sanitizeGeneratedTitle(firstUser.content)
        if (title.isBlank()) return

        // 写回前重读：用户可能刚手动改名，摘要流程也可能刚写回会话行，
        // 全实体更新必须基于最新快照（与 ReasoningDetected 分支同惯例）。
        val latest = conversationRepository.getById(conversationId).first() ?: return
        if (!isUntitledConversation(latest.title)) return
        conversationRepository.update(latest.copy(title = title))
    }

    /** 内置标题智能体自配模型（含 Provider）解析；未配置或解析失败返回 null。 */
    private suspend fun resolveTitleAgentModel(agent: Agent): Pair<Provider, ChatModel>? {
        val modelId = agent.defaultModelId ?: return null
        val model = modelRepository.getById(modelId).first() ?: return null
        val provider = providerRepository.getById(model.providerId).first() ?: return null
        return provider to model
    }

    companion object {
        private const val TRANSCRIPT_MAX_CHARS = 2000

        /**
         * 判断会话是否仍是「未命名」初始标题。与旧截断标题策略共用判定：
         * 空标题、「新对话」、「New Chat」都视为未命名。
         */
        fun isUntitledConversation(title: String): Boolean {
            return title.isBlank() || title == "新对话" || title == "New Chat"
        }

        /**
         * 清理模型返回的标题：剥离 think 块、去除包裹引号与首尾空白、
         * 压掉换行后截断到 [maxLength] 字符。
         */
        fun sanitizeGeneratedTitle(raw: String, maxLength: Int = 30): String {
            val stripped = stripThinkBlock(raw)
            val unwrapped = stripped.trim()
                .trim('"', '“', '”', '「', '」', '『', '』', '\'', '《', '》')
                .trim()
            val singleLine = unwrapped.lines().firstOrNull { it.isNotBlank() } ?: ""
            return if (singleLine.length <= maxLength) {
                singleLine
            } else {
                singleLine.take(maxLength) + "..."
            }
        }
    }
}
