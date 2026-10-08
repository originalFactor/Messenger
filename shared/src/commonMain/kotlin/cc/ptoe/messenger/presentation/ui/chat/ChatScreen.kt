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

package cc.ptoe.messenger.presentation.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.presentation.platform.rememberImagePicker
import cc.ptoe.messenger.presentation.platform.showPlatformToast
import cc.ptoe.messenger.presentation.ui.components.AgentAvatar
import cc.ptoe.messenger.presentation.ui.components.onContextMenu
import cc.ptoe.messenger.presentation.ui.components.rememberContextMenuState
import cc.ptoe.messenger.presentation.utils.DateTimeUtils
import cc.ptoe.messenger.presentation.utils.stripThinkBlock
import cc.ptoe.messenger.presentation.utils.WindowSizeClass
import cc.ptoe.messenger.presentation.utils.windowSizeClassFor
import cc.ptoe.messenger.presentation.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_back
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.action_go_settings
import cc.ptoe.messenger.generated.resources.chat_copied_toast
import cc.ptoe.messenger.generated.resources.chat_empty_hint
import cc.ptoe.messenger.generated.resources.chat_no_model_message
import cc.ptoe.messenger.generated.resources.chat_no_model_title
import cc.ptoe.messenger.generated.resources.chat_start_hint
import cc.ptoe.messenger.generated.resources.chat_title_default
import cc.ptoe.messenger.generated.resources.conversation_settings_title
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import cc.ptoe.messenger.di.AppContainerHolder

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    showBackButton: Boolean = true,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.provideFactory(
            messageRepository = AppContainerHolder.instance.messageRepository,
            conversationRepository = AppContainerHolder.instance.conversationRepository,
            agentRepository = AppContainerHolder.instance.agentRepository,
            apiRepository = AppContainerHolder.instance.apiRepository,
            modelRepository = AppContainerHolder.instance.modelRepository,
            providerRepository = AppContainerHolder.instance.providerRepository,
            chatImageStore = AppContainerHolder.instance.chatImageStore,
            conversationTitleGenerator = AppContainerHolder.instance.conversationTitleGenerator,
            builtinTools = { workspace -> AppContainerHolder.instance.availableTools(workspace) },
            projectRepository = AppContainerHolder.instance.projectRepository
        )
    )
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val agent by viewModel.agent.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val needsModelSetup by viewModel.needsModelSetup.collectAsStateWithLifecycle()
    val streamingMessageId by viewModel.streamingMessageId.collectAsStateWithLifecycle()
    val streamingContent by viewModel.streamingContent.collectAsStateWithLifecycle()
    val streamingDiff by viewModel.streamingDiff.collectAsStateWithLifecycle()
    val pendingImages by viewModel.pendingImages.collectAsStateWithLifecycle()
    val isAttachingImage by viewModel.isAttachingImage.collectAsStateWithLifecycle()
    val agentWritable by viewModel.agentWritable.collectAsStateWithLifecycle()
    val userAvatar by AppContainerHolder.instance.appPreferences.userAvatar.collectAsStateWithLifecycle(initialValue = null)

    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    var selectedMessageId by remember { mutableStateOf<String?>(null) }
    var showActionMenu by remember { mutableStateOf(false) }
    var selectedMessageRole by remember { mutableStateOf(MessageRole.USER) }
    val copiedToastText = stringResource(Res.string.chat_copied_toast)

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // 图片选择（Android 系统相册 / Desktop 文件对话框）。
    // 用户取消时不会触发回调，无需空值保护。
    val pickImageLauncher = rememberImagePicker { picked ->
        viewModel.attachImage(picked)
    }

    // 是否已完成初次滚动到底部（切换会话时重置）
    var hasInitiallyScrolled by remember(conversationId) { mutableStateOf(false) }
    // 上一次列表项数量，用于区分"新增消息"与"流式内容更新"
    var previousItemCount by remember(conversationId) { mutableIntStateOf(0) }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { msg ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(msg)
            }
            viewModel.clearError()
        }
    }

    // 预构建列表项（消息 + 日期分隔符），保持时间正序；
    // 配合 LazyColumn 的 reverseLayout，展示时反转使最新消息位于底部（index 0）
    val chatItems = remember(messages) { buildChatItems(messages) }

    // 进入聊天页时默认滑动到底部（reverseLayout 下 index 0 即底部）
    LaunchedEffect(chatItems.size) {
        if (!hasInitiallyScrolled && chatItems.isNotEmpty()) {
            hasInitiallyScrolled = true
            listState.scrollToItem(0)
        }
    }

    // 自动保持底部：消息更新或生成状态变化时，若用户未主动上滑则跟随到底部
    // reverseLayout 下 firstVisibleItemIndex 越小越靠近底部；0 表示完全在底部
    LaunchedEffect(chatItems.size, isGenerating) {
        if (!hasInitiallyScrolled || chatItems.isEmpty()) return@LaunchedEffect
        if (listState.firstVisibleItemIndex <= 3) {
            if (chatItems.size != previousItemCount) {
                listState.animateScrollToItem(0)
            } else if (isGenerating) {
                listState.scrollToItem(0)
            }
        }
        previousItemCount = chatItems.size
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sizeClass = windowSizeClassFor(maxWidth)
        val enableContextMenu = sizeClass != WindowSizeClass.Compact

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgentAvatar(
                            avatar = agent?.avatar,
                            size = 36.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = conversation?.title ?: stringResource(Res.string.chat_title_default),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                                maxLines = 1
                            )
                            Text(
                                text = agent?.name ?: "",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.action_back)
                            )
                        }
                    }
                },
                actions = {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = {
                            PlainTooltip { Text(stringResource(Res.string.conversation_settings_title)) }
                        },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(Res.string.conversation_settings_title)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Google Messages 风格：底部输入栏与顶栏同色，无分隔线
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                ChatInputBar(
                    text = inputText,
                    onTextChange = { inputText = it },
                    onSendClick = {
                        if (inputText.text.isNotBlank() || pendingImages.isNotEmpty()) {
                            viewModel.sendMessage(inputText.text)
                            inputText = TextFieldValue("")
                        }
                    },
                    onStopClick = {
                        viewModel.stopGeneration()
                    },
                    isGenerating = isGenerating,
                    pendingImages = pendingImages,
                    isAttachingImage = isAttachingImage,
                    onAddClick = {
                        pickImageLauncher.launch()
                    },
                    onRemoveImage = { viewModel.removePendingImage(it) },
                    // Agent 只读/可写模式切换按平台是否注册工具决定展示
                    agentWritable = if (viewModel.toolsAvailable) {
                        agentWritable
                    } else {
                        null
                    },
                    onAgentModeChange = { viewModel.setAgentWritable(it) }
                )
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        }
    ) { innerPadding ->
        // Google Messages 风格：聊天区使用纯净的 surface 背景
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            if (messages.isEmpty()) {
                EmptyChatState(
                    avatar = agent?.avatar,
                    agentName = agent?.name,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                ChatMessageList(
                    chatItems = chatItems,
                    isGenerating = isGenerating,
                    streamingContent = streamingContent,
                    streamingMessageId = streamingMessageId,
                    streamingDiff = streamingDiff,
                    agent = agent,
                    userAvatar = userAvatar,
                    enableContextMenu = enableContextMenu,
                    copiedToastText = copiedToastText,
                    onRetryClick = { messageId -> viewModel.retrySend(messageId) },
                    onMessageLongClick = { message ->
                        selectedMessageId = message.id
                        selectedMessageRole = message.role
                        showActionMenu = true
                    },
                    onCopyClick = { text -> viewModel.copyMessage(text) },
                    onRegenerateClick = { messageId -> viewModel.regenerateMessage(messageId) },
                    onDeleteClick = { messageId -> viewModel.deleteMessage(messageId) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    if (!enableContextMenu) {
        MessageActionMenu(
            isVisible = showActionMenu,
            onDismiss = {
                showActionMenu = false
                selectedMessageId = null
            },
            messageRole = selectedMessageRole,
            onCopyClick = {
                val currentMessages = messages
                val chatItems = buildChatItems(currentMessages)
                val toolGroup = chatItems.filterIsInstance<ChatListItem.ToolGroupItem>()
                    .firstOrNull { tg ->
                        tg.finalMessage?.id == selectedMessageId ||
                        tg.rounds.any { it.id == selectedMessageId } ||
                        tg.toolMessages.any { it.id == selectedMessageId }
                    }
                val copyText = if (toolGroup != null) {
                    val finalContent = toolGroup.finalMessage?.content?.takeIf { it.isNotBlank() }
                    if (finalContent != null) {
                        stripThinkBlock(finalContent)
                    } else {
                        val roundTexts = toolGroup.rounds.map { stripThinkBlock(it.content) }.filter { it.isNotBlank() }
                        roundTexts.joinToString("\n\n")
                    }
                } else {
                    val msg = currentMessages.find { it.id == selectedMessageId }
                    if (msg != null) stripThinkBlock(msg.content) else ""
                }
                if (copyText.isNotBlank()) {
                    viewModel.copyMessage(copyText)
                    showPlatformToast(copiedToastText)
                }
            },
            onRegenerateClick = {
                selectedMessageId?.let {
                    viewModel.regenerateMessage(it)
                }
            },
            onDeleteClick = {
                selectedMessageId?.let {
                    viewModel.deleteMessage(it)
                }
            }
        )
    }

    if (needsModelSetup) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissModelSetupPrompt() },
            title = { Text(stringResource(Res.string.chat_no_model_title)) },
            text = { Text(stringResource(Res.string.chat_no_model_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissModelSetupPrompt()
                    onSettingsClick()
                }) {
                    Text(stringResource(Res.string.action_go_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissModelSetupPrompt() }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            }
        )
    }
    }
}

/**
 * 聊天列表项：日期分隔符、消息或代理回合
 */
internal sealed class ChatListItem {
    data class DateSeparator(
        val id: String,
        val timestamp: Long
    ) : ChatListItem()

    data class MessageItem(
        val message: Message,
        val isLastInGroup: Boolean
    ) : ChatListItem()

    /**
     * 一个完整的代理回合：工具轮 assistant 行（含 ToolCall parts）+ TOOL 结果行 +
     * 承载最终文本的占位行，合并为一条消息渲染（卡片在流内、正文同属一个气泡）。
     * [rounds] 为空时是孤儿 TOOL 行的兜底展示。[finalMessage] 为 ERROR 行时不并入
     * （错误气泡独立渲染，保留重试入口）。
     */
    data class ToolGroupItem(
        val rounds: List<Message>,
        val toolMessages: List<Message>,
        val finalMessage: Message?,
        val isLastInGroup: Boolean
    ) : ChatListItem()
}

/** 是否为代理回合的工具轮行（含 ToolCall parts 的 assistant 行）。 */
private fun Message?.isToolRound(): Boolean =
    this != null && role == MessageRole.ASSISTANT && parts.any { it is ContentPart.ToolCall }

/**
 * 从 [startIndex] 起收编一个完整代理回合：连续的 工具轮行 → TOOL 结果行 →
 * （首个非 ERROR 的）最终文本行。ERROR 占位行不并入，独立渲染错误气泡。
 * [presetFinal] 非空时（回合占位行排在工具轮行之前的形态）直接作为回合的
 * 最终文本行。返回收编结果与越过回合末尾的下标。
 */
private fun collapseAgentTurn(
    messages: List<Message>,
    startIndex: Int,
    presetFinal: Message?
): Pair<ChatListItem.ToolGroupItem, Int> {
    val rounds = mutableListOf<Message>()
    val toolMessages = mutableListOf<Message>()
    var finalMessage: Message? = presetFinal
    var cursor = startIndex
    while (cursor < messages.size) {
        val current = messages[cursor]
        when {
            current.isToolRound() -> {
                rounds.add(current)
                cursor++
            }
            current.role == MessageRole.TOOL -> {
                toolMessages.add(current)
                cursor++
            }
            finalMessage == null &&
                current.role == MessageRole.ASSISTANT &&
                rounds.isNotEmpty() &&
                current.status != MessageStatus.ERROR -> {
                finalMessage = current
                cursor++
            }
            else -> break
        }
    }
    val isLastInGroup = cursor >= messages.size ||
        messages[cursor].role != MessageRole.ASSISTANT
    return ChatListItem.ToolGroupItem(
        rounds = rounds,
        toolMessages = toolMessages,
        finalMessage = finalMessage,
        isLastInGroup = isLastInGroup
    ) to cursor
}

/**
 * 构建聊天列表项：在每天首条消息前插入日期分隔符（Google Messages 风格），
 * 同时计算每条消息是否为同发送者组内的最后一条（用于气泡尾巴样式）。
 * assistant 工具轮消息、其 TOOL 结果行与最终文本行收编为一个
 * [ChatListItem.ToolGroupItem]（整个回合一条消息）。最终文本行通常排在
 * 工具轮行之后；回合进行中（以及取消的回合）Rust 循环在回合开始就落库
 * 承载最终文本的占位行，时间戳早于工具轮行——该形态同样收编为同一回合，
 * 否则流式期间会拆成两个气泡且不跟随滚动。孤儿 TOOL 行（历史异常）
 * 以工具组兜底渲染而不是静默丢弃。
 */
internal fun buildChatItems(messages: List<Message>): List<ChatListItem> {
    if (messages.isEmpty()) return emptyList()

    val items = mutableListOf<ChatListItem>()
    var lastDay: Long? = null
    var index = 0

    while (index < messages.size) {
        val message = messages[index]
        // 日期分隔符
        if (lastDay == null || !DateTimeUtils.isSameDay(lastDay, message.timestamp)) {
            items.add(ChatListItem.DateSeparator(id = message.id, timestamp = message.timestamp))
            lastDay = message.timestamp
        }
        when {
            message.isToolRound() -> {
                val (item, next) = collapseAgentTurn(messages, index, presetFinal = null)
                items.add(item)
                index = next
            }
            message.role == MessageRole.ASSISTANT && messages.getOrNull(index + 1).isToolRound() -> {
                // 回合占位行在工具轮行之前（回合进行中 / 取消的回合）：
                // 占位行承载最终文本，与随后的工具轮行收编为同一回合。
                val (item, next) = collapseAgentTurn(messages, index + 1, presetFinal = message)
                items.add(item)
                index = next
            }
            message.role == MessageRole.TOOL -> {
                items.add(
                    ChatListItem.ToolGroupItem(
                        rounds = emptyList(),
                        toolMessages = listOf(message),
                        finalMessage = null,
                        isLastInGroup = false
                    )
                )
                index++
            }
            else -> {
                // 是否为组内最后一条：下一条不存在或角色不同
                val isLastInGroup = index == messages.lastIndex ||
                    messages[index + 1].role != message.role
                items.add(ChatListItem.MessageItem(message = message, isLastInGroup = isLastInGroup))
                index++
            }
        }
    }

    return items
}

@Composable
internal fun DateSeparator(timestamp: Long, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = DateTimeUtils.formatDateSeparator(timestamp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun EmptyChatState(
    avatar: String?,
    agentName: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AgentAvatar(avatar = avatar, size = 72.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = agentName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.chat_start_hint),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.chat_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
