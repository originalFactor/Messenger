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

import androidx.compose.runtime.snapshotFlow
import cc.ptoe.messenger.presentation.platform.PickedImage
import cc.ptoe.messenger.presentation.platform.copyTextToClipboard
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import kotlin.reflect.KClass
import cc.ptoe.llmtypewriter.StreamingTypewriterState
import cc.ptoe.llmtypewriter.TypewriterPhase
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.remote.dto.UsageDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
import cc.ptoe.messenger.generated.resources.error_unknown
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
    private val chatImageStore: ChatImageStore
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
     * The shared typewriter state driving the currently-streaming AI message bubble.
     * Fed externally from SSE events in [generateResponse] / [retrySend]. Each
     * new stream calls [StreamingTypewriterState.reset] before tokens start flowing.
     */
    val typewriterState: StreamingTypewriterState = StreamingTypewriterState()

    /**
     * The id of the AI message that is currently being streamed. The chat bubble
     * whose message id matches this value binds to [typewriterState] for live
     * rendering; other bubbles render their [Message.content] statically.
     */
    private val _streamingMessageId = MutableStateFlow<String?>(null)
    val streamingMessageId: StateFlow<String?> = _streamingMessageId.asStateFlow()

    private var currentGenerationJob: Job? = null

    private val _enabledModels = MutableStateFlow<List<ChatModel>>(emptyList())
    private val enabledModels: StateFlow<List<ChatModel>> = _enabledModels.asStateFlow()

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
                reasoningEffort = if (agent.followDefaultReasoningEffort) default.reasoningEffort else agent.reasoningEffort
            )
        }

        if (conversation == null) return agentWithDefault

        return agentWithDefault.copy(
            defaultModelId = conversation.overrideModelId ?: agentWithDefault.defaultModelId,
            temperature = conversation.overrideTemperature ?: agentWithDefault.temperature,
            topP = conversation.overrideTopP ?: agentWithDefault.topP,
            maxTokens = conversation.overrideMaxTokens ?: agentWithDefault.maxTokens,
            reasoningEffort = conversation.overrideReasoningEffort ?: agentWithDefault.reasoningEffort
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
            ?: return recent.takeLast(HISTORY_MAX_MESSAGES)
        val summaryMessage = Message(
            id = SUMMARY_MESSAGE_ID,
            conversationId = conversation.id,
            role = MessageRole.SYSTEM,
            content = "[Summary of earlier conversation]\n$summary",
            timestamp = 0L,
            status = MessageStatus.SENT
        )
        return (listOf(summaryMessage) + recent).takeLast(HISTORY_MAX_MESSAGES)
    }

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
     * last-message preview / auto-title from the text payload.
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

            if (isNewConversation(conv.title) && text.isNotBlank()) {
                updateConversationTitle(convId, generateTitle(text))
            }

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

        // Bind the shared typewriter state to this message and reset the buffer
        // so the new stream starts from a clean slate.
        typewriterState.reset()
        _streamingMessageId.value = aiMessageId
        _isGenerating.value = true

        currentGenerationJob = viewModelScope.launch {
            var hasFinished = false
            var currentContent = ""
            var detectedFormat: String? = effectiveConv.reasoningFormat
            try {
                val sentMessages = messageRepository.getByConversationId(conversationId)
                    .first()
                    .filter { it.status == MessageStatus.SENT }
                val historyMessages = buildApiContextMessages(effectiveConv, sentMessages)
                // 本次请求上下文的估算基数（usage 缺失时用于用量记账）
                val sentContextEstimate = if (effectiveConv.contextTokens > 0L) {
                    effectiveConv.contextTokens +
                        sentMessages.filter { it.timestamp > effectiveConv.contextTokensAt }
                            .sumOf { estimateTokens(it) }
                } else {
                    estimateTokens(agent.systemPrompt.orEmpty()) +
                        historyMessages.sumOf { estimateTokens(it) }
                }
                apiRepository.streamChatCompletion(
                    provider = provider,
                    modelId = model.modelId,
                    messages = historyMessages,
                    systemPrompt = agent.systemPrompt,
                    temperature = agent.temperature,
                    topP = agent.topP,
                    maxTokens = agent.maxTokens,
                    reasoningEffort = agent.reasoningEffort,
                    reasoningFormat = detectedFormat
                ).collect { event ->
                        when (event) {
                            is ChatStreamEvent.ReasoningDetected -> {
                                if (detectedFormat == null) {
                                    detectedFormat = "reasoning_content"
                                    // 重新读取，避免覆盖 maybeSummarizeContext 写回的摘要状态
                                    conversationRepository.getById(conversationId).first()?.let {
                                        conversationRepository.update(it.copy(reasoningFormat = "reasoning_content"))
                                    }
                                }
                            }
                            is ChatStreamEvent.Content -> {
                                currentContent += event.text
                                typewriterState.appendToken(event.text)
                            }
                            is ChatStreamEvent.Done -> {
                                hasFinished = true
                                if (detectedFormat == null && currentContent.contains("<think")) {
                                    detectedFormat = "think_tag"
                                    conversationRepository.getById(conversationId).first()?.let {
                                        conversationRepository.update(it.copy(reasoningFormat = "think_tag"))
                                    }
                                }
                                updateContextTokens(conversationId, sentContextEstimate, currentContent, event.usage)
                                typewriterState.completeSource()
                                saveStreamResult(aiMessage, currentContent, conversationId, null)
                                awaitTypewriterDone()
                                if (currentContent.isNotBlank()) {
                                    awaitMessagePersisted(aiMessageId, currentContent)
                                }
                                _streamingMessageId.value = null
                                _isGenerating.value = false
                            }
                            is ChatStreamEvent.Error -> {
                                hasFinished = true
                                typewriterState.stop()
                                saveStreamResult(aiMessage, currentContent, conversationId, event.message)
                                setError(event.message)
                                if (currentContent.isNotBlank()) {
                                    awaitMessagePersisted(aiMessageId, currentContent)
                                }
                                _streamingMessageId.value = null
                                _isGenerating.value = false
                            }
                        }
                    }
                if (!hasFinished) {
                    typewriterState.stop()
                    val errorMsg = getString(Res.string.error_api_no_valid_response)
                    saveStreamResult(aiMessage, currentContent, conversationId, errorMsg)
                    setError(errorMsg)
                    if (currentContent.isNotBlank()) {
                        awaitMessagePersisted(aiMessageId, currentContent)
                    }
                    _streamingMessageId.value = null
                    _isGenerating.value = false
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (hasFinished) {
                    return@launch
                }
                typewriterState.stop()
                val errorMsg = e.message ?: getString(Res.string.error_unknown)
                saveStreamResult(aiMessage, currentContent, conversationId, errorMsg)
                setError(errorMsg)
                if (currentContent.isNotBlank()) {
                    awaitMessagePersisted(aiMessageId, currentContent)
                }
                _streamingMessageId.value = null
                _isGenerating.value = false
            }
        }
    }

    /**
     * Waits for the shared typewriter to finish revealing its buffer (phase == Done)
     * before the host unbinds [streamingMessageId] — otherwise the bubble jumps from
     * partially-revealed text to the full static content (visible flicker). Bounded by
     * a timeout in case the bubble is no longer composing (reveal loop cancelled) or
     * the buffer is too large to flush in a reasonable window.
     */
    private suspend fun awaitTypewriterDone(timeoutMs: Long = 3000L) {
        val flushed = withTimeoutOrNull(timeoutMs) {
            snapshotFlow { typewriterState.phase }
                .first { it == TypewriterPhase.Done || it == TypewriterPhase.Stopped }
        }
        if (flushed == null) {
            // Reveal loop isn't running (bubble disposed) or buffer too large —
            // force-flush so the subsequent static render matches the live view.
            typewriterState.skipToEnd()
        }
    }

    /**
     * Waits for [messages] to reflect the final persisted [content] for [messageId]
     * before the host unbinds [streamingMessageId]. Without this, the bubble switches
     * from the live typewriter (which holds the streamed content in memory) to the
     * static path while [Message.content] is still the stale empty value from the
     * initial insert — the static path's `remember(message.id, message.content)`
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
            // Flush any pending typewriter buffer so the partial content is
            // visible after the bubble switches to static rendering.
            typewriterState.skipToEnd()
            typewriterState.stop()
            _streamingMessageId.value = null

            // Read from DB (not messages.value) so we observe the very latest
            // content written during streaming, even if the StateFlow hasn't
            // propagated yet. Any SENDING assistant message is promoted to SENT
            // so the partial content is kept and enters future AI context.
            val convId = _conversationId.value
            if (convId != null) {
                val pending = messageRepository.getByConversationId(convId).first()
                    .filter { it.role == MessageRole.ASSISTANT && it.status == MessageStatus.SENDING }
                pending.forEach { msg ->
                    messageRepository.update(msg.copy(status = MessageStatus.SENT))
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

            typewriterState.reset()
            _streamingMessageId.value = messageId
            _isGenerating.value = true

            currentGenerationJob = launch {
                var hasFinished = false
                var currentContent = ""
                var detectedFormat: String? = conv.reasoningFormat
                try {
                    val sentMessages = messageRepository.getByConversationId(message.conversationId)
                        .first()
                        .filter { it.status == MessageStatus.SENT && it.id != messageId }
                    val historyMessages = buildApiContextMessages(conv, sentMessages)
                    val sentContextEstimate = if (conv.contextTokens > 0L) {
                        conv.contextTokens +
                            sentMessages.filter { it.timestamp > conv.contextTokensAt }
                                .sumOf { estimateTokens(it) }
                    } else {
                        estimateTokens(currentAgent.systemPrompt.orEmpty()) +
                            historyMessages.sumOf { estimateTokens(it) }
                    }
                    apiRepository.streamChatCompletion(
                        provider = provider,
                        modelId = model.modelId,
                        messages = historyMessages,
                        systemPrompt = currentAgent.systemPrompt,
                        temperature = currentAgent.temperature,
                        topP = currentAgent.topP,
                        maxTokens = currentAgent.maxTokens,
                        reasoningEffort = currentAgent.reasoningEffort,
                        reasoningFormat = detectedFormat
                    ).collect { event ->
                        when (event) {
                            is ChatStreamEvent.ReasoningDetected -> {
                                if (detectedFormat == null) {
                                    detectedFormat = "reasoning_content"
                                    // 重新读取，避免覆盖会话上的其他并发写回字段
                                    conversationRepository.getById(message.conversationId).first()?.let {
                                        conversationRepository.update(it.copy(reasoningFormat = "reasoning_content"))
                                    }
                                }
                            }
                            is ChatStreamEvent.Content -> {
                                currentContent += event.text
                                typewriterState.appendToken(event.text)
                            }
                            is ChatStreamEvent.Done -> {
                                hasFinished = true
                                if (detectedFormat == null && currentContent.contains("<think")) {
                                    detectedFormat = "think_tag"
                                    conversationRepository.getById(message.conversationId).first()?.let {
                                        conversationRepository.update(it.copy(reasoningFormat = "think_tag"))
                                    }
                                }
                                updateContextTokens(message.conversationId, sentContextEstimate, currentContent, event.usage)
                                typewriterState.completeSource()
                                saveStreamResult(message, currentContent, message.conversationId, null)
                                awaitTypewriterDone()
                                if (currentContent.isNotBlank()) {
                                    awaitMessagePersisted(messageId, currentContent)
                                }
                                _streamingMessageId.value = null
                                _isGenerating.value = false
                            }
                            is ChatStreamEvent.Error -> {
                                hasFinished = true
                                typewriterState.stop()
                                saveStreamResult(message, currentContent, message.conversationId, event.message)
                                setError(event.message)
                                if (currentContent.isNotBlank()) {
                                    awaitMessagePersisted(messageId, currentContent)
                                }
                                _streamingMessageId.value = null
                                _isGenerating.value = false
                            }
                        }
                    }
                    if (!hasFinished) {
                        typewriterState.stop()
                        val errorMsg = getString(Res.string.error_api_no_valid_response)
                        saveStreamResult(message, currentContent, message.conversationId, errorMsg)
                        setError(errorMsg)
                        if (currentContent.isNotBlank()) {
                            awaitMessagePersisted(messageId, currentContent)
                        }
                        _streamingMessageId.value = null
                        _isGenerating.value = false
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (hasFinished) {
                        return@launch
                    }
                    typewriterState.stop()
                    val errorMsg = e.message ?: getString(Res.string.error_unknown)
                    saveStreamResult(message, currentContent, message.conversationId, errorMsg)
                    setError(errorMsg)
                    if (currentContent.isNotBlank()) {
                        awaitMessagePersisted(messageId, currentContent)
                    }
                    _streamingMessageId.value = null
                    _isGenerating.value = false
                }
            }
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

    private suspend fun updateConversationTitle(conversationId: String, title: String) {
        val conv = conversationRepository.getById(conversationId).first() ?: return
        conversationRepository.update(conv.copy(title = title))
    }

    private suspend fun saveStreamResult(
        aiMessage: Message,
        content: String,
        conversationId: String,
        errorMessage: String?
    ) {
        if (content.isNotBlank()) {
            messageRepository.update(
                aiMessage.copy(
                    content = content,
                    status = MessageStatus.SENT,
                    errorMessage = null
                )
            )
            updateConversationLastMessage(conversationId, content, System.currentTimeMillis())
        } else if (errorMessage != null) {
            messageRepository.update(
                aiMessage.copy(
                    content = content,
                    status = MessageStatus.ERROR,
                    errorMessage = errorMessage
                )
            )
        }
    }

    private fun isNewConversation(title: String): Boolean {
        return title.isBlank() || title == "新对话" || title == "New Chat"
    }

    private fun generateTitle(firstMessage: String): String {
        val trimmed = firstMessage.trim()
        return if (trimmed.length <= 30) {
            trimmed
        } else {
            trimmed.take(30) + "..."
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

        fun provideFactory(
            messageRepository: MessageRepository,
            conversationRepository: ConversationRepository,
            agentRepository: AgentRepository,
            apiRepository: ApiRepository,
            modelRepository: ModelRepository,
            providerRepository: ProviderRepository,
            chatImageStore: ChatImageStore
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
                    chatImageStore
                ) as T
            }
        }
    }
}
