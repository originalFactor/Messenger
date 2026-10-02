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

package cc.ptoe.messenger.presentation.viewmodel

import cc.ptoe.messenger.presentation.platform.PickedImage
import cc.ptoe.messenger.presentation.platform.copyTextToClipboard
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import kotlin.reflect.KClass
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.remote.dto.UsageDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageImage
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ApiRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.domain.tool.TerminalTool
import cc.ptoe.messenger.domain.usecase.ConversationTitleGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.chat_picture
import cc.ptoe.messenger.generated.resources.context_summarize_prompt
import cc.ptoe.messenger.generated.resources.error_agent_not_found_chat
import cc.ptoe.messenger.generated.resources.error_api_no_valid_response
import cc.ptoe.messenger.generated.resources.error_configure_model_first
import cc.ptoe.messenger.generated.resources.error_context_summarize_failed
import cc.ptoe.messenger.generated.resources.error_no_available_model
import cc.ptoe.messenger.generated.resources.error_read_image_failed
import cc.ptoe.messenger.generated.resources.error_tool_rounds_exceeded
import cc.ptoe.messenger.generated.resources.error_unknown
import cc.ptoe.messenger.generated.resources.tool_interrupted_result
import cc.ptoe.messenger.generated.resources.tool_unknown_tool
import org.jetbrains.compose.resources.getString
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.presentation.utils.stripThinkBlock

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val agentRepository: AgentRepository,
    private val apiRepository: ApiRepository,
    private val modelRepository: ModelRepository,
    private val providerRepository: ProviderRepository,
    private val chatImageStore: ChatImageStore,
    private val conversationTitleGenerator: ConversationTitleGenerator,
    /** 平台内置工具注册表；为空（如 Android）时即使 Agent 开启开关也不发 tools。 */
    private val builtinTools: List<ChatTool> = emptyList()
) : ViewModel() {

    private val _conversationId = MutableStateFlow<String?>(null)

    val conversation: StateFlow<Conversation?> = _conversationId
        .flatMapLatest { id ->
            if (id != null) {
                conversationRepository.getById(id)
            } else {
                flowOf(null)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val agent: StateFlow<Agent?> = conversation
        .flatMapLatest { conv ->
            if (conv != null) {
                agentRepository.getById(conv.agentId)
            } else {
                flowOf(null)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val messages: StateFlow<List<Message>> = _conversationId
        .flatMapLatest { id ->
            if (id != null) {
                messageRepository.getByConversationId(id)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _needsModelSetup = MutableStateFlow(false)
    val needsModelSetup: StateFlow<Boolean> = _needsModelSetup.asStateFlow()

    /**
     * Images the user has picked but not yet sent. They are rendered
     * as a preview strip in the input bar and cleared on send /
     * conversation switch.
     */
    private val _pendingImages = MutableStateFlow<List<MessageImage>>(emptyList())
    val pendingImages: StateFlow<List<MessageImage>> = _pendingImages.asStateFlow()

    /**
     * True while [attachImages] is reading a content:// URI into the
     * chat image cache. The input bar disables the picker button to
     * avoid double-taps during the bitmap decode.
     */
    private val _isAttachingImage = MutableStateFlow(false)
    val isAttachingImage: StateFlow<Boolean> = _isAttachingImage.asStateFlow()

    /**
     * 正在流式的 AI 消息当前已收到的全部文本（token 即时绘制，无打字机动画）。
     * 由 [generateResponse] / [retrySend] 的 SSE Content 事件逐 token 更新；
     * 流式结束/停止后置 null，气泡退回渲染已持久化的 [Message.content]。
     */
    private val _streamingContent = MutableStateFlow<String?>(null)
    val streamingContent: StateFlow<String?> = _streamingContent.asStateFlow()

    /**
     * The id of the AI message that is currently being streamed. The chat bubble
     * whose message id matches this value binds to [streamingContent] for live
     * rendering; other bubbles render their [Message.content] statically.
     */
    private val _streamingMessageId = MutableStateFlow<String?>(null)
    val streamingMessageId: StateFlow<String?> = _streamingMessageId.asStateFlow()

    private var currentGenerationJob: Job? = null

    private val _enabledModels = MutableStateFlow<List<ChatModel>>(emptyList())
    private val enabledModels: StateFlow<List<ChatModel>> = _enabledModels.asStateFlow()

    /**
     * Agent 模式（取代原「手动/自动执行」开关）：false=只读（终端保持沙箱
     * 策略，写入类工具不声明给模型，工具在沙箱内自动执行，默认），true=可写
     * （终端解除只读策略、写入类工具可用，需确认的工具逐次弹框确认）。
     * 会话级状态（Conversation.writable），由聊天输入栏 "+" 功能面板切换。
     */
    val agentWritable: StateFlow<Boolean> = conversation
        .map { it?.writable ?: false }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    /** Platform has registered at least one built-in tool. */
    val toolsAvailable: Boolean get() = builtinTools.isNotEmpty()

    /** 切换本会话的 Agent 只读/可写模式（写回 Conversation，不改动 updatedAt）。 */
    fun setAgentWritable(writable: Boolean) {
        viewModelScope.launch {
            val conv = conversation.value ?: return@launch
            conversationRepository.update(conv.copy(writable = writable))
        }
    }

    init {
        viewModelScope.launch {
            modelRepository.getAll().collect { allModels ->
                _enabledModels.value = allModels.filter { it.isEnabled }
            }
        }
    }

    /**
     * 三层参数合并：对话 override > Agent（含跟随默认Agent） > 默认Agent
     * 1. 先根据 Agent 的 followDefault* 标记合并默认 Agent 的值
     * 2. 再用对话的 override 值覆盖对应的字段（systemPrompt 不允许 override）
     */
    private suspend fun resolveEffectiveAgent(
        agent: Agent,
        conversation: Conversation? = null
    ): Agent {
        val agentWithDefault = if (agent.isDefault) {
            agent
        } else {
            val default = agentRepository.getDefaultAgent().first() ?: return agent
            agent.copy(
                systemPrompt = if (agent.followDefaultSystemPrompt) default.systemPrompt else agent.systemPrompt,
                defaultModelId = if (agent.followDefaultModel) default.defaultModelId else agent.defaultModelId,
                temperature = if (agent.followDefaultTemperature) default.temperature else agent.temperature,
                topP = if (agent.followDefaultTopP) default.topP else agent.topP,
                maxTokens = if (agent.followDefaultMaxTokens) default.maxTokens else agent.maxTokens,
                reasoningEffort = if (agent.followDefaultReasoningEffort) default.reasoningEffort else agent.reasoningEffort,
                toolsEnabled = if (agent.toolsFollowDefault) default.toolsEnabled else agent.toolsEnabled,
                toolsConfig = if (agent.toolsFollowDefault) default.toolsConfig else agent.toolsConfig
            )
        }

        if (conversation == null) return agentWithDefault

        return agentWithDefault.copy(
            defaultModelId = conversation.overrideModelId ?: agentWithDefault.defaultModelId,
            temperature = conversation.overrideTemperature ?: agentWithDefault.temperature,
            topP = conversation.overrideTopP ?: agentWithDefault.topP,
            maxTokens = conversation.overrideMaxTokens ?: agentWithDefault.maxTokens,
            reasoningEffort = conversation.overrideReasoningEffort ?: agentWithDefault.reasoningEffort,
            toolsEnabled = conversation.overrideToolsEnabled ?: agentWithDefault.toolsEnabled,
            toolsConfig = conversation.overrideToolsConfig ?: agentWithDefault.toolsConfig
        )
    }

    fun loadConversation(conversationId: String) {
        // Switching conversations implicitly discards any unsent
        // images: the pending preview is bound to the input bar of a
        // single chat, not a global draft.
        _pendingImages.value = emptyList()
        _conversationId.value = conversationId
    }

    // ------------------------------------------------------------------
    // Context window / auto summarization
    //
    // 模型声明了 context window（内置云 AI 服务商自动同步元数据）时，
    // 发送前估算上下文占用；达到 80% 就把较早的历史折叠成一份摘要
    // （保留最近 SUMMARY_KEEP_COUNT 条原文），摘要以 system 消息随请求
    // 发送，聊天记录本身不动。内置云代理每回合回报精确 usage，覆盖估算。
    // ------------------------------------------------------------------

    /**
     * 粗略 token 估算：CJK 字符约 1 token/字，其余约 4 字符/token，
     * 另加每条约 4 token 的消息开销。只用于阈值判断；内置云代理会回报
     * 精确 usage 并在每回合后覆盖该估算。
     */
    private fun estimateTokens(text: String): Long {
        if (text.isEmpty()) return 0L
        var cjk = 0L
        var other = 0L
        for (c in text) {
            if (c.code > 0x2E7F) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    private fun estimateTokens(message: Message): Long {
        val text = message.parts.filterIsInstance<ContentPart.Text>()
            .joinToString("\n") { it.text }
            .ifBlank { message.content }
        // 一张中等分辨率图按 ~800 tokens 估算
        val images = message.parts.count { it is ContentPart.Image }
        return estimateTokens(text) + images * 800L + 4L
    }

    /**
     * 估算「下次请求将携带的完整上下文」的 token 数。优先以最近一次精确
     * 用量记账（[Conversation.contextTokens]）为基数，叠加其后新增消息的
     * 估算值；没有记账时从系统提示词 + 折叠摘要 + 全量历史估算。
     */
    private suspend fun estimateContextTokens(
        conversation: Conversation,
        systemPrompt: String?
    ): Long {
        val messages = messageRepository.getByConversationId(conversation.id)
            .first()
            .filter { it.status == MessageStatus.SENT }
        return if (conversation.contextTokens > 0L) {
            conversation.contextTokens +
                messages.filter { it.timestamp > conversation.contextTokensAt }
                    .sumOf { estimateTokens(it) }
        } else {
            estimateTokens(systemPrompt.orEmpty()) +
                estimateTokens(conversation.contextSummary.orEmpty()) +
                messages.filter { it.timestamp >= conversation.contextSummaryUntil }
                    .sumOf { estimateTokens(it) }
        }
    }

    /**
     * 发送前的 80% 上下文自动摘要。把 timestamp 早于保留窗口的历史
     * （连同上一份摘要）交给模型压缩成新摘要并写回会话；返回值是写回后
     * 的最新会话（调用方据此组装请求上下文）。失败时提示但不阻塞发送。
     */
    private suspend fun maybeSummarizeContext(
        conversation: Conversation,
        agent: Agent,
        model: ChatModel,
        provider: Provider
    ): Conversation {
        if (model.contextWindow <= 0L) return conversation
        val threshold = model.contextWindow * 80L / 100L
        if (estimateContextTokens(conversation, agent.systemPrompt) < threshold) return conversation

        val all = messageRepository.getByConversationId(conversation.id)
            .first()
            .filter { it.status == MessageStatus.SENT }
        if (all.isEmpty()) return conversation
        val keepCount = minOf(SUMMARY_KEEP_COUNT, all.size)
        val firstKept = all[all.size - keepCount]
        val toSummarize = all.filter { it.timestamp < firstKept.timestamp }
        if (toSummarize.isEmpty()) return conversation

        val transcript = buildString {
            val previousSummary = conversation.contextSummary
            if (!previousSummary.isNullOrBlank() && conversation.contextSummaryUntil > 0L) {
                appendLine("[Earlier summary]")
                appendLine(previousSummary)
                appendLine()
                appendLine("[Conversation since then]")
            }
            toSummarize.forEach { message ->
                appendLine("${roleLabel(message)}: ${messageBrief(message)}")
            }
        }

        return try {
            val result = apiRepository.createChatCompletion(
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
                systemPrompt = getString(Res.string.context_summarize_prompt),
                temperature = 0.3f,
                topP = 1.0f,
                maxTokens = null,
                reasoningEffort = null,
                reasoningFormat = null
            )
            val summary = stripThinkBlock(result.content).trim()
            if (summary.isEmpty()) return conversation
            val updated = conversation.copy(
                contextSummary = summary,
                contextSummaryUntil = firstKept.timestamp,
                contextTokens = estimateTokens(agent.systemPrompt.orEmpty()) +
                    estimateTokens(summary) +
                    all.filter { it.timestamp >= firstKept.timestamp }
                        .sumOf { estimateTokens(it) },
                contextTokensAt = System.currentTimeMillis()
            )
            conversationRepository.update(updated)
            updated
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(
                getString(
                    Res.string.error_context_summarize_failed,
                    e.message ?: getString(Res.string.error_unknown)
                )
            )
            conversation
        }
    }

    /**
     * 组装发送给 API 的上下文消息：折叠摘要（若有，置于最前作为 system
     * 消息）+ 截止时间戳之后的原文消息，末尾保留 HISTORY_MAX_MESSAGES 条
     * 兜底截断（模型未声明 context window 时它仍然生效）。
     */
    private fun buildApiContextMessages(
        conversation: Conversation,
        sentMessages: List<Message>
    ): List<Message> {
        val recent = sentMessages.filter { it.timestamp >= conversation.contextSummaryUntil }
        val summary = conversation.contextSummary
            ?.takeIf { it.isNotBlank() && conversation.contextSummaryUntil > 0L }
            ?: return recent.takeLast(HISTORY_MAX_MESSAGES).trimOrphanToolMessages()
        val summaryMessage = Message(
            id = SUMMARY_MESSAGE_ID,
            conversationId = conversation.id,
            role = MessageRole.SYSTEM,
            content = "[Summary of earlier conversation]\n$summary",
            timestamp = 0L,
            status = MessageStatus.SENT
        )
        return (listOf(summaryMessage) + recent)
            .takeLast(HISTORY_MAX_MESSAGES)
            .trimOrphanToolMessages()
    }

    /**
     * 兜底截断可能把工具配对从中间切开：开头的 role=TOOL 消息若已失去其
     * assistant tool_calls 配对（被截掉），必须丢弃 — OpenAI 会拒绝缺少
     * 前置 tool_calls 的 tool 消息。
     */
    private fun List<Message>.trimOrphanToolMessages(): List<Message> =
        dropWhile { it.role == MessageRole.TOOL }

    /** 回合结束后写回上下文用量：优先精确 usage，否则退回估算。 */
    private suspend fun updateContextTokens(
        conversationId: String,
        sentContextEstimate: Long,
        responseText: String,
        usage: UsageDto?
    ) {
        val conv = conversationRepository.getById(conversationId).first() ?: return
        val tokens = usage
            ?.let { it.promptTokens.toLong() + it.completionTokens.toLong() }
            ?.takeIf { it > 0L }
            ?: (sentContextEstimate + estimateTokens(responseText))
        conversationRepository.update(
            conv.copy(contextTokens = tokens, contextTokensAt = System.currentTimeMillis())
        )
    }

    private fun roleLabel(message: Message): String = when (message.role) {
        MessageRole.USER -> "User"
        MessageRole.ASSISTANT -> "Assistant"
        else -> "System"
    }

    /** 折叠进摘要的单条消息文本：多模态消息以 [image ×N] 占位。 */
    private fun messageBrief(message: Message): String {
        val text = message.parts.filterIsInstance<ContentPart.Text>()
            .joinToString("\n") { it.text }
            .ifBlank { message.content }
        val imageCount = message.parts.count { it is ContentPart.Image }
        return if (imageCount > 0) {
            (if (text.isBlank()) "" else "$text\n") + "[image ×$imageCount]"
        } else {
            text
        }
    }

    /**
     * Imports a picked image into a [MessageImage] and queues it as a
     * pending attachment. Errors are surfaced through the existing
     * snackbar so the user knows why the picker didn't take.
     */
    fun attachImage(picked: PickedImage) {
        if (_isAttachingImage.value) return
        _isAttachingImage.value = true
        viewModelScope.launch {
            try {
                val image = chatImageStore.importImage(picked.bytes, picked.extension)
                _pendingImages.value = _pendingImages.value + image
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                setError(getString(Res.string.error_read_image_failed, e.message ?: getString(Res.string.error_unknown)))
            } finally {
                _isAttachingImage.value = false
            }
        }
    }

    fun removePendingImage(image: MessageImage) {
        _pendingImages.value = _pendingImages.value.filterNot { it.localPath == image.localPath }
        // The image was already cached to disk; the next time the same
        // bitmap is needed it will be re-decoded from the source URI.
        // We only delete the cached copy on actual message delete.
    }

    fun clearPendingImages() {
        _pendingImages.value = emptyList()
    }

    /**
     * Sends a chat message. If [pendingImages] is non-empty the
     * resulting message becomes multimodal: the text is the last
     * text part and the queued images are emitted as image parts in
     * the order the user picked them.
     *
     * Mirrors the original text-only contract: refuses to fire while
     * another generation is in flight, prompts for a model when
     * [Agent.defaultModelId] is unset, and updates the conversation's
     * last-message preview from the text payload. The conversation
     * title itself is generated by [ConversationTitleGenerator] after
     * the first assistant response completes.
     */
    fun sendMessage(text: String) {
        val convId = _conversationId.value ?: return
        if (text.isBlank() && _pendingImages.value.isEmpty()) return
        if (_isGenerating.value) return

        viewModelScope.launch {
            val conv = conversation.value ?: return@launch
            val rawAgent = agent.value ?: run {
                setError(getString(Res.string.error_agent_not_found_chat))
                return@launch
            }
            val currentAgent = resolveEffectiveAgent(rawAgent, conv)

            if (currentAgent.defaultModelId == null) {
                _needsModelSetup.value = true
                return@launch
            }

            val images = _pendingImages.value
            val parts = buildMultimodalParts(text, images)
            val userMessageId = randomUuid()
            val now = System.currentTimeMillis()

            val userMessage = Message(
                id = userMessageId,
                conversationId = convId,
                role = MessageRole.USER,
                content = text,
                parts = parts,
                timestamp = now,
                status = MessageStatus.SENDING
            )
            messageRepository.insert(userMessage)

            updateConversationLastMessage(convId, text.ifBlank { getString(Res.string.chat_picture) }, now)

            messageRepository.update(userMessage.copy(status = MessageStatus.SENT))
            // Drop the local preview now that the message is persisted
            // — the bubble's image parts take over visually.
            _pendingImages.value = emptyList()

            generateResponse(convId, currentAgent)
        }
    }

    private fun buildMultimodalParts(text: String, images: List<MessageImage>): List<ContentPart> {
        if (images.isEmpty()) {
            return listOf(ContentPart.Text(text))
        }
        val parts = mutableListOf<ContentPart>()
        if (text.isNotBlank()) {
            parts += ContentPart.Text(text)
        }
        parts.addAll(images.map { ContentPart.Image(it) })
        return parts
    }

    private suspend fun generateResponse(
        conversationId: String,
        agent: Agent
    ) {
        val conv = conversation.value ?: return
        val result = getActiveModelAndProvider(conv, agent)
        if (result == null) {
            setError(getString(Res.string.error_configure_model_first))
            return
        }

        val (provider, model) = result

        // 80% 上下文自动摘要：可能写回会话的摘要状态，返回最新会话快照。
        val effectiveConv = maybeSummarizeContext(conv, agent, model, provider)

        val aiMessageId = randomUuid()
        val aiMessage = Message(
            id = aiMessageId,
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = "",
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING
        )
        messageRepository.insert(aiMessage)

        // Bind the live streamed content to this message before tokens arrive.
        _streamingMessageId.value = aiMessageId
        _isGenerating.value = true

        launchChatTurn(
            conversationId = conversationId,
            agent = agent,
            conversation = effectiveConv,
            provider = provider,
            model = model,
            targetMessage = aiMessage,
            initialDetectedFormat = effectiveConv.reasoningFormat
        )
    }

    /**
     * 单次发送的代理循环（generateResponse / retrySend 共用核心）：
     * 流式一轮 → 模型请求工具调用（[ChatStreamEvent.Done.toolCalls] 非空）时
     * 逐个确认并执行工具、把结果以 role=TOOL 行写回历史 → 重建上下文进入下一轮，
     * 直到模型给出最终文本（写入 [targetMessage] 行）或出错/超出轮数上限。
     *
     * 工具轮的 assistant 消息（轮内文本 + ToolCall parts）以独立行落库，
     * [targetMessage] 始终承载最终文本；因此工具配对在历史中保持完整，
     * retrySend 的目标行排除只作用于第一轮。
     */
    private fun launchChatTurn(
        conversationId: String,
        agent: Agent,
        conversation: Conversation,
        provider: Provider,
        model: ChatModel,
        targetMessage: Message,
        initialDetectedFormat: String?,
        excludeTargetFromHistory: Boolean = false
    ) {
        currentGenerationJob = viewModelScope.launch {
            var detectedFormat = initialDetectedFormat
            var hasFinished = false
            var currentContent = ""
            // 插入行的时间戳游标：保证工具轮 assistant 行 / TOOL 结果行 / 最终文本
            // 严格递增，避免同毫秒插入时消息列表顺序漂移。声明在 try 外，收尾分支
            // （含异常路径）也能把占位行时间戳推进到工具行之后。
            var timestampCursor = targetMessage.timestamp
            suspend fun nextTimestamp(): Long {
                timestampCursor = maxOf(timestampCursor + 1L, System.currentTimeMillis())
                return timestampCursor
            }
            // 占位行（最终文本落点）的时间戳停留在最初插入时刻，早于工具轮行；
            // 有工具轮落行时必须顺次推进，否则按时间排序会把最终答案排到卡片之前。
            suspend fun bumpedFinalTimestamp(): Long? =
                if (timestampCursor > targetMessage.timestamp) nextTimestamp() else null
            try {
                // 请求是否携带 tools：生效工具总开关打开且平台注册了工具即发送，
                // 并按 Agent 的每工具开关过滤（配置缺失的键视为开启）。
                //（不按 supportsToolCalling 门控 — 元数据缺失时该值为 false，
                //  会让功能看似失效；不支持的服务商会给出可见错误。）
                // Agent 模式：只读时排除写入类工具（edit/create）且终端保持
                // 只读策略；可写时终端解除策略、写入类工具恢复声明。
                val toolsForRequest = if (agent.toolsEnabled && builtinTools.isNotEmpty()) {
                    val enabledTools = builtinTools.filter { (agent.toolsConfig[it.name]) ?: true }
                    // 发送时的会话快照（launchChatTurn 的 conversation 参数来自
                    // 发送时的新鲜 DB 读取）。agentWritable StateFlow 由 UI 订阅
                    // 驱动（WhileSubscribed 5 秒无订阅即重置），App 切后台后其
                    // .value 会退回 false，不能作为回合内的模式决策依据。
                    if (conversation.writable) {
                        enabledTools.map { tool ->
                            if (tool is TerminalTool) TerminalTool(enforceReadOnly = false) else tool
                        }
                    } else {
                        enabledTools.filter { !it.writeAccess }
                    }
                } else {
                    null
                }
                // 仅允许执行已声明（未停用）的工具，模型误调时回错误结果自纠
                val toolsByName = toolsForRequest?.associateBy { it.name } ?: emptyMap()
                var excludeId: String? = if (excludeTargetFromHistory) targetMessage.id else null

                var round = 0
                var turnComplete = false
                while (!turnComplete) {
                    round++
                    hasFinished = false
                    // 本轮是否为工具调用轮（Done 事件写回；一轮流会因 finish_reason
                    // 块与 [DONE] 各发一次 Done，须防重复处理）
                    var toolRoundCalls: List<ToolCallData>? = null
                    val sentMessages = messageRepository.getByConversationId(conversationId)
                        .first()
                        .filter { it.status == MessageStatus.SENT && it.id != excludeId }
                    val historyMessages = buildApiContextMessages(conversation, sentMessages)
                    // 本次请求上下文的估算基数（usage 缺失时用于用量记账）
                    val sentContextEstimate = if (conversation.contextTokens > 0L) {
                        conversation.contextTokens +
                            sentMessages.filter { it.timestamp > conversation.contextTokensAt }
                                .sumOf { estimateTokens(it) }
                    } else {
                        estimateTokens(agent.systemPrompt.orEmpty()) +
                            historyMessages.sumOf { estimateTokens(it) }
                    }
                    currentContent = ""
                    _streamingMessageId.value = targetMessage.id
                    apiRepository.streamChatCompletion(
                        provider = provider,
                        modelId = model.modelId,
                        messages = historyMessages,
                        systemPrompt = agent.systemPrompt,
                        temperature = agent.temperature,
                        topP = agent.topP,
                        maxTokens = agent.maxTokens,
                        reasoningEffort = agent.reasoningEffort,
                        reasoningFormat = detectedFormat,
                        tools = toolsForRequest
                    ).collect { event ->
                        when (event) {
                            is ChatStreamEvent.ReasoningDetected -> {
                                if (detectedFormat == null) {
                                    // reasoning_content=完整思维链 / reasoning_summary=GPT 等加密思维链仅回传摘要
                                    detectedFormat = event.format
                                    // 重新读取，避免覆盖并发写回的其他会话字段
                                    conversationRepository.getById(conversationId).first()?.let {
                                        conversationRepository.update(it.copy(reasoningFormat = event.format))
                                    }
                                }
                            }
                            is ChatStreamEvent.Content -> {
                                currentContent += event.text
                                _streamingContent.value = currentContent
                            }
                            is ChatStreamEvent.Done -> {
                                if (hasFinished) return@collect
                                hasFinished = true
                                if (detectedFormat == null && currentContent.contains("<think")) {
                                    detectedFormat = "think_tag"
                                    conversationRepository.getById(conversationId).first()?.let {
                                        conversationRepository.update(it.copy(reasoningFormat = "think_tag"))
                                    }
                                }
                                updateContextTokens(conversationId, sentContextEstimate, currentContent, event.usage)
                                if (event.toolCalls.isNotEmpty() && toolRoundCalls == null) {
                                    // 工具轮：轮内文本 + 工具调用以独立 assistant 行落库
                                    val turnMessage = Message(
                                        id = randomUuid(),
                                        conversationId = conversationId,
                                        role = MessageRole.ASSISTANT,
                                        content = currentContent,
                                        parts = event.toolCalls.map {
                                            ContentPart.ToolCall(
                                                callId = it.callId,
                                                name = it.name,
                                                arguments = it.arguments
                                            )
                                        },
                                        timestamp = nextTimestamp(),
                                        status = MessageStatus.SENT
                                    )
                                    messageRepository.insert(turnMessage)
                                    if (currentContent.isNotBlank()) {
                                        updateConversationLastMessage(conversationId, currentContent, turnMessage.timestamp)
                                    }
                                    toolRoundCalls = event.toolCalls
                                    // 该轮文本已由独立行承载；占位行回到空态等待下一轮
                                    currentContent = ""
                                    _streamingContent.value = null
                                    // 工具调用已进入历史，后续轮次不再排除目标行
                                    excludeId = null
                                } else if (event.toolCalls.isEmpty() && toolRoundCalls == null) {
                                    saveStreamResult(
                                        targetMessage,
                                        currentContent,
                                        conversationId,
                                        null,
                                        bumpedFinalTimestamp()
                                    )
                                    if (currentContent.isNotBlank()) {
                                        awaitMessagePersisted(targetMessage.id, currentContent)
                                        // 最终文本轮完成 → 标题生成（工具轮不触发）
                                        conversationTitleGenerator.launchGenerateIfNeeded(
                                            conversationId, provider, model
                                        ) { message -> setError(message) }
                                    }
                                    _streamingContent.value = null
                                    _streamingMessageId.value = null
                                    _isGenerating.value = false
                                    turnComplete = true
                                }
                            }
                            is ChatStreamEvent.Error -> {
                                hasFinished = true
                                saveStreamResult(targetMessage, currentContent, conversationId, event.message)
                                setError(event.message)
                                if (currentContent.isNotBlank()) {
                                    awaitMessagePersisted(targetMessage.id, currentContent)
                                }
                                _streamingContent.value = null
                                _streamingMessageId.value = null
                                _isGenerating.value = false
                            }
                        }
                    }
                    if (!hasFinished) {
                        val errorMsg = getString(Res.string.error_api_no_valid_response)
                        saveStreamResult(
                            targetMessage,
                            currentContent,
                            conversationId,
                            errorMsg,
                            bumpedFinalTimestamp()
                        )
                        setError(errorMsg)
                        if (currentContent.isNotBlank()) {
                            awaitMessagePersisted(targetMessage.id, currentContent)
                        }
                        _streamingContent.value = null
                        _streamingMessageId.value = null
                        _isGenerating.value = false
                        return@launch
                    }
                    val calls = toolRoundCalls ?: return@launch
                    if (round >= MAX_TOOL_ROUNDS) {
                        // 防失控：轮数上限后不再执行工具，按错误收尾
                        val errorMsg = getString(Res.string.error_tool_rounds_exceeded)
                        saveStreamResult(
                            targetMessage,
                            "",
                            conversationId,
                            errorMsg,
                            bumpedFinalTimestamp()
                        )
                        setError(errorMsg)
                        _streamingContent.value = null
                        _streamingMessageId.value = null
                        _isGenerating.value = false
                        return@launch
                    }
                    executeToolCalls(
                        conversationId = conversationId,
                        calls = calls,
                        toolsByName = toolsByName,
                        startAfterTimestamp = timestampCursor
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errorMsg = e.message ?: getString(Res.string.error_unknown)
                saveStreamResult(
                    targetMessage,
                    currentContent,
                    conversationId,
                    errorMsg,
                    bumpedFinalTimestamp()
                )
                setError(errorMsg)
                if (currentContent.isNotBlank()) {
                    awaitMessagePersisted(targetMessage.id, currentContent)
                }
                _streamingContent.value = null
                _streamingMessageId.value = null
                _isGenerating.value = false
            }
        }
    }

    /**
     * 依次执行一轮工具调用：工具在沙箱内自动执行（不弹确认框，确认机制
     * 已整体移除）。未知工具/参数错误同样以结果文本回传，让模型自纠。
     */
    private suspend fun executeToolCalls(
        conversationId: String,
        calls: List<ToolCallData>,
        toolsByName: Map<String, ChatTool>,
        startAfterTimestamp: Long
    ) {
        var cursor = startAfterTimestamp
        suspend fun nextTimestamp(): Long {
            cursor = maxOf(cursor + 1L, System.currentTimeMillis())
            return cursor
        }
        for (call in calls) {
            val tool = toolsByName[call.name]
            val row = Message(
                id = randomUuid(),
                conversationId = conversationId,
                role = MessageRole.TOOL,
                content = "",
                parts = listOf(ContentPart.ToolResult(callId = call.callId, name = call.name, output = "")),
                timestamp = nextTimestamp(),
                status = MessageStatus.SENDING
            )
            // 先落库「运行中」行：finishToolMessage 只做 update（Room 对
            // 不存在的行是 no-op），不先 insert 结果行就永远不会写进历史。
            messageRepository.insert(row)
            when {
                tool == null -> finishToolMessage(
                    row,
                    getString(Res.string.tool_unknown_tool, call.name),
                    isError = true
                )
                else -> {
                    val result = try {
                        tool.execute(call.arguments)
                    } catch (e: CancellationException) {
                        // 进程已被执行器的 finally 终止；先落中断结果再传播取消，
                        // 保证 TOOL 行不会停留在「运行中」。
                        withContext(NonCancellable) {
                            finishToolMessage(row, getString(Res.string.tool_interrupted_result), isError = true)
                        }
                        throw e
                    }
                    finishToolMessage(row, result.output, isError = result.isError)
                }
            }
        }
    }

    /** 把「运行中」TOOL 行更新为最终结果（结果同时写入 content 与 ToolResult part）。 */
    private suspend fun finishToolMessage(row: Message, output: String, isError: Boolean) {
        val part = row.parts.firstOrNull() as? ContentPart.ToolResult
        messageRepository.update(
            row.copy(
                content = output,
                parts = listOf(
                    ContentPart.ToolResult(
                        callId = part?.callId ?: "",
                        name = part?.name ?: "",
                        output = output,
                        isError = isError
                    )
                ),
                status = MessageStatus.SENT
            )
        )
    }

    /**
     * Waits for [messages] to reflect the final persisted [content] for [messageId]
     * before the host unbinds [streamingMessageId]. Without this, the bubble switches
     * from the live [streamingContent] (which holds the streamed content in memory)
     * to the static path while [Message.content] is still the stale empty value from
     * the initial insert — the static path's `remember(message.id, message.content)`
     * then seeds an empty state, causing a one-frame empty render (visible flicker)
     * before the Room Flow re-emits with the persisted content. Bounded by a timeout
     * so a missing emission (e.g. [saveStreamResult] no-op on blank content) does
     * not block the stream completion forever.
     */
    private suspend fun awaitMessagePersisted(
        messageId: String,
        content: String,
        timeoutMs: Long = 1000L
    ) {
        withTimeoutOrNull(timeoutMs) {
            messages.first { list ->
                list.any { it.id == messageId && it.content == content }
            }
        }
    }

    fun stopGeneration() {
        currentGenerationJob?.cancel()
        currentGenerationJob = null
        _isGenerating.value = false

        viewModelScope.launch {
            // 停止即时流式绘制：清空 streamingContent，气泡回退渲染持久化内容。
            _streamingContent.value = null
            _streamingMessageId.value = null

            // Read from DB (not messages.value) so we observe the very latest
            // content written during streaming, even if the StateFlow hasn't
            // propagated yet. Any SENDING assistant message is promoted to SENT
            // so the partial content is kept and enters future AI context; a
            // SENDING tool row (cancelled mid-run) is finalized with an
            // interrupted marker so the result card never stays "running".
            val convId = _conversationId.value
            if (convId != null) {
                val pending = messageRepository.getByConversationId(convId).first()
                    .filter {
                        it.status == MessageStatus.SENDING &&
                            (it.role == MessageRole.ASSISTANT || it.role == MessageRole.TOOL)
                    }
                pending.forEach { msg ->
                    if (msg.role == MessageRole.TOOL) {
                        val part = msg.parts.firstOrNull() as? ContentPart.ToolResult
                        val interrupted = getString(Res.string.tool_interrupted_result)
                        messageRepository.update(
                            msg.copy(
                                content = interrupted,
                                parts = listOf(
                                    ContentPart.ToolResult(
                                        callId = part?.callId ?: "",
                                        name = part?.name ?: "",
                                        output = interrupted,
                                        isError = true
                                    )
                                ),
                                status = MessageStatus.SENT
                            )
                        )
                    } else {
                        // 占位行时间戳推进到当前时刻：停止时可能已有工具轮落行
                        // （时间戳晚于占位行），不推进会把保留的部分文本排到卡片前。
                        messageRepository.update(
                            msg.copy(status = MessageStatus.SENT, timestamp = System.currentTimeMillis())
                        )
                    }
                }
            }
        }
    }

    fun retrySend(messageId: String) {
        viewModelScope.launch {
            val message = messages.value.find { it.id == messageId } ?: return@launch
            if (message.status != MessageStatus.ERROR) return@launch
            if (_isGenerating.value) return@launch

            val rawAgent = agent.value ?: return@launch
            val conv = conversation.value ?: return@launch
            val currentAgent = resolveEffectiveAgent(rawAgent, conv)

            if (currentAgent.defaultModelId == null) {
                _needsModelSetup.value = true
                return@launch
            }

            messageRepository.update(
                message.copy(
                    status = MessageStatus.SENDING,
                    errorMessage = null,
                    content = ""
                )
            )
            val result = getActiveModelAndProvider(conv, currentAgent)
            if (result == null) {
                setError(getString(Res.string.error_configure_model_first))
                messageRepository.update(
                    message.copy(
                        status = MessageStatus.ERROR,
                        errorMessage = getString(Res.string.error_no_available_model)
                    )
                )
                return@launch
            }

            val (provider, model) = result

            // Bind the live streamed content to this message before tokens arrive.
            _streamingMessageId.value = messageId
            _isGenerating.value = true

            // 重试路径复用原 ERROR 行承载最终文本；该行第一轮仍排除在历史外，
            // 一旦产生工具调用（写入独立行）即恢复纳入。
            launchChatTurn(
                conversationId = message.conversationId,
                agent = currentAgent,
                conversation = conv,
                provider = provider,
                model = model,
                targetMessage = message.copy(status = MessageStatus.SENDING, errorMessage = null, content = ""),
                initialDetectedFormat = conv.reasoningFormat,
                excludeTargetFromHistory = true
            )
        }
    }

    fun regenerateMessage(messageId: String) {
        val convId = _conversationId.value ?: return
        if (_isGenerating.value) return

        viewModelScope.launch {
            val message = messages.value.find { it.id == messageId } ?: return@launch
            if (message.role != MessageRole.ASSISTANT) return@launch

            val rawAgent = agent.value ?: return@launch
            val conv = conversation.value ?: return@launch
            val currentAgent = resolveEffectiveAgent(rawAgent, conv)

            if (currentAgent.defaultModelId == null) {
                _needsModelSetup.value = true
                return@launch
            }

            messageRepository.delete(messageId)

            generateResponse(convId, currentAgent)
        }
    }

    fun copyMessage(text: String) {
        copyTextToClipboard(text)
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            // Capture the image paths before the row is gone so we can
            // reap the cached bitmaps from disk.
            val target = messages.value.find { it.id == messageId }
            messageRepository.delete(messageId)
            target?.parts?.forEach { part ->
                if (part is ContentPart.Image) {
                    chatImageStore.deleteIfExists(part.image.localPath)
                }
            }

            val convId = _conversationId.value ?: return@launch
            val remainingMessages = messageRepository.getByConversationId(convId).first()
            val lastMessage = remainingMessages.lastOrNull()
            updateConversationLastMessage(
                convId,
                lastMessage?.content ?: "",
                lastMessage?.timestamp ?: System.currentTimeMillis()
            )
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun dismissModelSetupPrompt() {
        _needsModelSetup.value = false
    }

    private fun setError(message: String) {
        _errorMessage.value = message
    }

    private suspend fun getActiveModelAndProvider(
        conversation: Conversation,
        agent: Agent
    ): Pair<Provider, ChatModel>? {
        val cachedEnabledModels = enabledModels.first()

        if (cachedEnabledModels.isEmpty()) return null

        val defaultModel = agent.defaultModelId?.let { modelId ->
            cachedEnabledModels.find { it.id == modelId }
        }

        val model = defaultModel ?: run {
            if (conversation.providerId.isNotBlank()) {
                cachedEnabledModels.find { it.providerId == conversation.providerId }
            } else {
                null
            }
        } ?: cachedEnabledModels.first()

        val provider = providerRepository.getById(model.providerId).first() ?: return null

        return provider to model
    }

    private suspend fun updateConversationLastMessage(
        conversationId: String,
        lastMessage: String,
        timestamp: Long
    ) {
        conversationRepository.updateLastMessage(conversationId, lastMessage, timestamp)
    }

    private suspend fun saveStreamResult(
        aiMessage: Message,
        content: String,
        conversationId: String,
        errorMessage: String?,
        timestamp: Long? = null
    ) {
        if (content.isNotBlank()) {
            messageRepository.update(
                aiMessage.copy(
                    content = content,
                    status = MessageStatus.SENT,
                    errorMessage = null,
                    timestamp = timestamp ?: aiMessage.timestamp
                )
            )
            updateConversationLastMessage(conversationId, content, System.currentTimeMillis())
        } else if (errorMessage != null) {
            messageRepository.update(
                aiMessage.copy(
                    content = content,
                    status = MessageStatus.ERROR,
                    errorMessage = errorMessage,
                    timestamp = timestamp ?: aiMessage.timestamp
                )
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentGenerationJob?.cancel()
        currentGenerationJob = null
    }

    companion object {
        /** 兜底历史截断（模型未声明 context window 时仍然生效）。 */
        private const val HISTORY_MAX_MESSAGES = 20
        /** 自动摘要时保留原文的近期消息条数。 */
        private const val SUMMARY_KEEP_COUNT = 10
        /** 注入请求的折叠摘要 system 消息的占位 id。 */
        private const val SUMMARY_MESSAGE_ID = "context-summary"
        /** 单次发送的代理循环工具调用轮数上限（防失控）。 */
        private const val MAX_TOOL_ROUNDS = 10

        fun provideFactory(
            messageRepository: MessageRepository,
            conversationRepository: ConversationRepository,
            agentRepository: AgentRepository,
            apiRepository: ApiRepository,
            modelRepository: ModelRepository,
            providerRepository: ProviderRepository,
            chatImageStore: ChatImageStore,
            conversationTitleGenerator: ConversationTitleGenerator,
            builtinTools: List<ChatTool> = emptyList()
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return ChatViewModel(
                    messageRepository,
                    conversationRepository,
                    agentRepository,
                    apiRepository,
                    modelRepository,
                    providerRepository,
                    chatImageStore,
                    conversationTitleGenerator,
                    builtinTools
                ) as T
            }
        }
    }
}
