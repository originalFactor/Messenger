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
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.repository.ProjectRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.domain.tool.TerminalTool
import cc.ptoe.messenger.domain.tool.createBuiltinChatTools
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.chat_picture
import cc.ptoe.messenger.generated.resources.chat_workspace_note
import cc.ptoe.messenger.generated.resources.context_summarize_prompt
import cc.ptoe.messenger.generated.resources.error_agent_not_found_chat
import cc.ptoe.messenger.generated.resources.error_api_no_valid_response
import cc.ptoe.messenger.generated.resources.error_configure_model_first
import cc.ptoe.messenger.generated.resources.error_context_summarize_failed
import cc.ptoe.messenger.generated.resources.error_no_available_model
import cc.ptoe.messenger.generated.resources.error_read_image_failed
import cc.ptoe.messenger.generated.resources.error_unknown
import cc.ptoe.messenger.generated.resources.tool_interrupted_result
import cc.ptoe.messenger.generated.resources.tool_unknown_tool
import org.jetbrains.compose.resources.getString
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.data.util.runBlockingCall
import cc.ptoe.messenger.presentation.ui.chat.buildChatItems
import cc.ptoe.messenger.presentation.ui.chat.ChatListItem

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.remote.NetworkClient
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val agentRepository: AgentRepository,
    private val modelRepository: ModelRepository,
    private val providerRepository: ProviderRepository,
    private val chatImageStore: ChatImageStore,
    /**
     * 平台内置工具工厂；参数是所属项目的 workspace 绝对路径（无项目时
     * 传 null，此时不声明工作区工具）。为空工厂（如 Android 未安装伴随
     * runtime）时即使 Agent 开启开关也不发 tools。
     */
    private val builtinTools: (String?) -> List<ChatTool> = { createBuiltinChatTools(it) },
    private val projectRepository: ProjectRepository? = null,
    private val coreBridge: CoreBridge = requireNotNull(CoreBridgeRegistry.bridge)
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

    private val _streamingDiff = MutableStateFlow<String?>(null)
    val streamingDiff: StateFlow<String?> = _streamingDiff.asStateFlow()

    private var currentDocSession: cc.ptoe.messenger.core.DocumentSessionBridge? = null

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

    /**
     * 本会话所属项目的 workspace；null 表示该会话不属于任何项目，因此
     * 不声明终端与工作区工具（MCP 工具不受项目约束）。
     */
    val project: StateFlow<Project?> = conversation
        .flatMapLatest { conv ->
            val projectId = conv?.projectId
            val projects = projectRepository
            if (projectId != null && projects != null) {
                projects.getById(projectId)
            } else {
                flowOf(null)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    /** 本会话可用的内置工具（工作区工具仅在属于项目时存在）。 */
    val builtinToolsForConversation: StateFlow<List<ChatTool>> = conversation
        .flatMapLatest { conv -> flowOf(builtinTools(resolveWorkspaceFor(conv))) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 平台是否具备内置工具能力（与本会话是否属于项目无关 —— 项目只决定
     * 工作区工具是否可用，聊天输入栏的 "+" 面板据此显示）。
     */
    val toolsAvailable: Boolean get() = builtinTools(null).isNotEmpty()

    /**
     * 回合内的工作目录决策读新鲜数据库行，而不是 [project] 这个
     * WhileSubscribed 的 StateFlow（无订阅 5 秒即重置）：workspace 决定
     * 工具能否执行，不能依赖一个会被回收的 UI 状态。
     */
    private suspend fun resolveWorkspaceFor(conv: Conversation?): String? {
        val projectId = conv?.projectId ?: return null
        val projects = projectRepository ?: return null
        return projects.getById(projectId).first()?.workspace
    }

    /** 切换本会话的 Agent 只读/可写模式（写回 Conversation，不改动 updatedAt）。 */
    fun setAgentWritable(writable: Boolean) {
        viewModelScope.launch {
            val id = _conversationId.value ?: return@launch
            // 读数据库行而不是 conversation.value：该 StateFlow 在无订阅 5 秒后
            // 会重置，且整行 copy 回写时旧值会覆盖并发写入的其他字段。
            val conv = conversationRepository.getById(id).first() ?: return@launch
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
            // 会话快照读数据库行：conversation 是 WhileSubscribed 流，无订阅 5 秒
            // 即重置，.value 的 writable/override 等业务决策字段不可信。
            val conv = conversationRepository.getById(convId).first() ?: return@launch
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
            val now = currentTimeMillis()

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
        // 会话快照读数据库行而不是 conversation.value：模式（writable）等业务
        // 决策不能依赖 WhileSubscribed 的 StateFlow（无订阅 5 秒即重置）。
        val conv = conversationRepository.getById(conversationId).first() ?: return
        val result = getActiveModelAndProvider(conv, agent)
        if (result == null) {
            setError(getString(Res.string.error_configure_model_first))
            return
        }

        val (provider, model) = result
        val bridge = coreBridge
        val titleHolder = agentRepository.getAll().first().firstOrNull { it.role == Agent.ROLE_TITLE }
        val workspace = resolveWorkspaceFor(conv)
        val availableTools = builtinTools(workspace)
        val enabledTools = if (agent.toolsEnabled && availableTools.isNotEmpty()) {
            availableTools.filter { (agent.toolsConfig[it.name]) ?: true }
        } else {
            emptyList()
        }
        // 可执行工具表：执行不做参数预筛（沙盒按实际操作约束），与 Rust 侧
        // 请求声明共用 per-tool 开关。写入类工具（edit/create）的声明由
        // Rust 按 writable 过滤，此处保留全量映射，模型误调未声明工具时
        // 由核心回路返回错误自纠。
        val toolsByName = enabledTools.associateBy { it.name }
        val config = TurnConfigBridge(
            conversationId = conversationId,
            modelId = model.modelId,
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            apiFormat = provider.apiFormat.requestFormat.wireName,
            systemPrompt = agent.systemPrompt,
            temperature = agent.temperature.toDouble(),
            topP = agent.topP.toDouble(),
            maxTokens = agent.maxTokens?.toLong(),
            reasoningEffort = agent.reasoningEffort,
            toolNames = enabledTools.map { it.name },
            writable = conv.writable,
            contextWindow = model.contextWindow,
            summarizePrompt = getString(Res.string.context_summarize_prompt),
            titleAgentId = titleHolder?.id,
            titleAgentSystemPrompt = titleHolder?.systemPrompt,
            titleAgentModelId = titleHolder?.defaultModelId,
            hasWorkspace = workspace != null,
            workspaceNote = workspace?.let {
                getString(Res.string.chat_workspace_note, it)
            }.orEmpty(),
        )

        _isGenerating.value = true
        _streamingContent.value = ""
        currentDocSession = bridge.createDocumentSession()
        _streamingDiff.value = null

        currentGenerationJob = viewModelScope.launch {
            try {
                bridge.runTurn(
                    config = config,
                    toolExecutor = { name, argumentsJson ->
                        val tool = toolsByName[name]
                        if (tool != null) {
                            // NEVER let an exception escape into the JNI
                            // upcall: a foreign callback exception turns
                            // into a Rust panic (spawn_blocking JoinError
                            // → "tool host cancelled"). Report it to the
                            // model as an error result for self-correction.
                            try {
                                val r = runBlockingCall { tool.execute(argumentsJson) }
                                r.output to r.isError
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                ("tool execution failed: ${e.message ?: e::class.simpleName}") to true
                            }
                        } else {
                            "Unknown tool: $name" to true
                        }
                    },
                    onEventJson = { eventJson ->
                        handleTurnEvent(eventJson)
                    }
                )
            } catch (e: CancellationException) {
                bridge.cancelTurn()
                throw e
            } catch (e: Exception) {
                setError(e.message ?: getString(Res.string.error_unknown))
            } finally {
                currentDocSession = null
                _streamingContent.value = null
                _streamingMessageId.value = null
                _streamingDiff.value = null
                _isGenerating.value = false
            }
        }
    }

    fun stopGeneration() {
        coreBridge.cancelTurn()
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
                            msg.copy(status = MessageStatus.SENT, timestamp = currentTimeMillis())
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
            // 会话快照读数据库行（同 sendMessage，.value 的业务字段不可信）
            val conv = conversationRepository.getById(message.conversationId).first() ?: return@launch
            val currentAgent = resolveEffectiveAgent(rawAgent, conv)

            if (currentAgent.defaultModelId == null) {
                _needsModelSetup.value = true
                return@launch
            }

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

            // 重试走与普通发送相同的核心回路：Rust 自己创建承载最终文本的
            // 占位行，因此失败行必须删掉（它的 errorMessage 不属于历史）。
            messageRepository.delete(messageId)
            generateResponse(message.conversationId, currentAgent)
        }
    }

    fun regenerateMessage(messageId: String) {
        val convId = _conversationId.value ?: return
        if (_isGenerating.value) return

        viewModelScope.launch {
            val currentMessages = messageRepository.getByConversationId(convId).first()
            val message = currentMessages.find { it.id == messageId } ?: return@launch
            if (message.role != MessageRole.ASSISTANT) return@launch

            val rawAgent = agent.value ?: return@launch
            // 会话快照读数据库行（同 sendMessage，.value 的业务字段不可信）
            val conv = conversationRepository.getById(convId).first() ?: return@launch
            val currentAgent = resolveEffectiveAgent(rawAgent, conv)

            if (currentAgent.defaultModelId == null) {
                _needsModelSetup.value = true
                return@launch
            }

            // 查找该消息所属回合的所有关联消息（包括工具轮行、TOOL 结果行、最终文本行）
            val chatItems = buildChatItems(currentMessages)
            val toolGroup = chatItems.filterIsInstance<ChatListItem.ToolGroupItem>()
                .firstOrNull { tg ->
                    tg.finalMessage?.id == messageId ||
                    tg.rounds.any { it.id == messageId } ||
                    tg.toolMessages.any { it.id == messageId }
                }
            val idsToDelete = if (toolGroup != null) {
                (toolGroup.rounds.map { it.id } + toolGroup.toolMessages.map { it.id } + listOfNotNull(toolGroup.finalMessage?.id)).distinct()
            } else {
                listOf(messageId)
            }

            for (id in idsToDelete) {
                messageRepository.delete(id)
            }

            generateResponse(convId, currentAgent)
        }
    }

    fun copyMessage(text: String) {
        copyTextToClipboard(text)
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            val convId = _conversationId.value ?: return@launch
            val currentMessages = messageRepository.getByConversationId(convId).first()
            val chatItems = buildChatItems(currentMessages)
            val toolGroup = chatItems.filterIsInstance<ChatListItem.ToolGroupItem>()
                .firstOrNull { tg ->
                    tg.finalMessage?.id == messageId ||
                    tg.rounds.any { it.id == messageId } ||
                    tg.toolMessages.any { it.id == messageId }
                }
            val targets = if (toolGroup != null) {
                (toolGroup.rounds + toolGroup.toolMessages + listOfNotNull(toolGroup.finalMessage)).distinctBy { it.id }
            } else {
                listOfNotNull(currentMessages.find { it.id == messageId })
            }

            for (target in targets) {
                messageRepository.delete(target.id)
                target.parts.forEach { part ->
                    if (part is ContentPart.Image) {
                        chatImageStore.deleteIfExists(part.image.localPath)
                    }
                }
            }

            val remainingMessages = messageRepository.getByConversationId(convId).first()
            val lastMessage = remainingMessages.lastOrNull()
            updateConversationLastMessage(
                convId,
                lastMessage?.content ?: "",
                lastMessage?.timestamp ?: currentTimeMillis()
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

    private fun handleTurnEvent(eventJson: String) {
        val root = try {
            NetworkClient.json.parseToJsonElement(eventJson).jsonObject
        } catch (_: Exception) {
            return
        }
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return
        when (type) {
            "StreamingStarted" -> {
                // The Rust loop creates the placeholder row itself; this is
                // the ONLY id that matches the row the renderer displays.
                val messageId = root["messageId"]?.jsonPrimitive?.contentOrNull ?: return
                _streamingMessageId.value = messageId
            }
            "TextDelta" -> {
                val text = root["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                _streamingContent.value = (_streamingContent.value ?: "") + text
                val diffBatch = currentDocSession?.feed(text)
                if (!diffBatch.isNullOrBlank() && diffBatch != "{\"diffs\":[]}") {
                    _streamingDiff.value = diffBatch
                }
            }
            "RoundPersisted" -> {
                _streamingContent.value = ""
                val finishBatch = currentDocSession?.finish()
                if (!finishBatch.isNullOrBlank() && finishBatch != "{\"diffs\":[]}") {
                    _streamingDiff.value = finishBatch
                }
                currentDocSession = coreBridge.createDocumentSession()
            }
            "Finished", "Cancelled" -> {
                val finishBatch = currentDocSession?.finish()
                if (!finishBatch.isNullOrBlank() && finishBatch != "{\"diffs\":[]}") {
                    _streamingDiff.value = finishBatch
                }
                currentDocSession = null
                _streamingContent.value = null
                _streamingMessageId.value = null
                _streamingDiff.value = null
                _isGenerating.value = false
            }
            "TitleFailed" -> {
                viewModelScope.launch {
                    val code = root["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    setError(code)
                }
            }
            "Error" -> {
                val msg = root["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (msg.isNotBlank()) {
                    setError(msg)
                }
                currentDocSession = null
                _streamingContent.value = null
                _streamingMessageId.value = null
                _streamingDiff.value = null
                _isGenerating.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentGenerationJob?.cancel()
        currentGenerationJob = null
        currentDocSession = null
    }

    companion object {
        fun provideFactory(
            messageRepository: MessageRepository,
            conversationRepository: ConversationRepository,
            agentRepository: AgentRepository,
            modelRepository: ModelRepository,
            providerRepository: ProviderRepository,
            chatImageStore: ChatImageStore,
            builtinTools: (String?) -> List<ChatTool> = { createBuiltinChatTools(it) },
            projectRepository: ProjectRepository? = null,
            coreBridge: CoreBridge = requireNotNull(CoreBridgeRegistry.bridge)
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return ChatViewModel(
                    messageRepository,
                    conversationRepository,
                    agentRepository,
                    modelRepository,
                    providerRepository,
                    chatImageStore,
                    builtinTools,
                    projectRepository,
                    coreBridge
                ) as T
            }
        }
    }
}
