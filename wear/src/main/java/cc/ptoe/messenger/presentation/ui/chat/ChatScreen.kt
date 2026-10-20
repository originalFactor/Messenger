/*
 * Copyright 2026 originalFactor
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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import cc.ptoe.messenger.data.WearAgent
import cc.ptoe.messenger.data.WearConversation
import cc.ptoe.messenger.presentation.ui.components.BannerMessage
import cc.ptoe.messenger.presentation.ui.components.MessageBubble
import cc.ptoe.messenger.presentation.ui.components.verticalRotaryScroll
import cc.ptoe.messenger.presentation.viewmodel.WearChatUiState
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 聊天页。HorizontalPager 两页：
 * - page 0: 消息列表
 * - page 1: 输入框
 *
 * 左滑/右滑由原生 HorizontalPager 手势处理，底部 HorizontalPageIndicator 指示当前页。
 * 系统返回键回到列表。
 */
@Composable
fun ChatScreen(
    uiState: WearChatUiState,
    onBack: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> ChatContent(uiState = uiState)
                1 -> ComposeContent(
                    draft = uiState.draft,
                    enabled = uiState.selectedAgent?.isReady == true && !uiState.isSending,
                    onDraftChange = onDraftChange,
                    onSend = {
                        onSend()
                        scope.launch { pagerState.animateScrollToPage(0) }
                    }
                )
            }
        }

        HorizontalPageIndicator(
            pagerState = pagerState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun ChatContent(
    uiState: WearChatUiState,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val selectedAgent = uiState.selectedAgent
    val selectedConversation = uiState.selectedConversation

    // reverseLayout: index 0 = 底部（最新消息）；向上滚动 index 增大 = 更早的历史
    val conversationId = selectedConversation?.id
    var hasInitiallyScrolled by remember(conversationId) { mutableStateOf(false) }
    var previousItemCount by remember(conversationId) { mutableIntStateOf(0) }

    // 滚动节流：避免每个字符变化都触发滚动
    var lastScrollTime by remember { mutableLongStateOf(0L) }
    val scrollThrottleMs = 100L  // 最小 100ms 间隔

    // 进入会话时滚动到底部（reverseLayout 下 index 0）
    LaunchedEffect(conversationId, uiState.messages.isNotEmpty()) {
        if (!hasInitiallyScrolled && uiState.messages.isNotEmpty()) {
            hasInitiallyScrolled = true
            listState.scrollToItem(0)
        }
    }

    // 流式跟踪：用户停留在底部附近时跟随最新消息；上滑查看历史时不打扰
    // 节流：最小 100ms 间隔，避免频繁滚动调用
    LaunchedEffect(uiState.messages, uiState.isSending) {
        if (!hasInitiallyScrolled || uiState.messages.isEmpty()) return@LaunchedEffect
        if (listState.firstVisibleItemIndex <= 2) {
            val now = System.currentTimeMillis()
            if (now - lastScrollTime >= scrollThrottleMs) {
                lastScrollTime = now
                if (uiState.messages.size != previousItemCount) {
                    listState.animateScrollToItem(0)
                } else {
                    listState.scrollToItem(0)
                }
            }
        }
        previousItemCount = uiState.messages.size
    }

    // 为底部 HorizontalPageIndicator 预留的高度，同时用于底部弦距补偿
    val pageIndicatorReservedHeight = 20.dp

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = pageIndicatorReservedHeight)
        ) {
            if (uiState.bannerMessage != null) {
                BannerMessage(uiState.bannerMessage)
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (selectedAgent?.isReady == false) {
                BannerMessage("Set a model for this agent on your phone before chatting.")
                Spacer(modifier = Modifier.height(6.dp))
            }

            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // 圆表盘：让消息行（宽度 = 屏幕宽 - 28dp 水平 padding）的左右边
                // 在圆形内完全可见所需的最小留白。
                // 公式：inset = r - sqrt(r² - (W/2)²)，r 为屏幕半径，W 为行宽。
                val density = LocalDensity.current
                val horizontalPaddingPx = with(density) { 14.dp.toPx() }
                val contentWidthPx = with(density) { maxWidth.toPx() }
                val screenWidthPx = contentWidthPx + 2f * horizontalPaddingPx
                val radiusPx = screenWidthPx / 2f
                val halfWidthPx = contentWidthPx / 2f
                val chordInsetPx = if (halfWidthPx < radiusPx) {
                    radiusPx - sqrt(radiusPx * radiusPx - halfWidthPx * halfWidthPx)
                } else {
                    0f
                }
                val chordInsetDp = with(density) { chordInsetPx.toDp() }
                // 底部已被 HorizontalPageIndicator 占用一部分，扣除已预留的高度
                val bottomInsetDp = (chordInsetDp - pageIndicatorReservedHeight).coerceAtLeast(0.dp)

                if (uiState.messages.isEmpty()) {
                    EmptyConversation(
                        conversation = selectedConversation,
                        agent = selectedAgent
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(
                            top = chordInsetDp,
                            bottom = bottomInsetDp
                        ),
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalRotaryScroll(listState)
                    ) {
                        items(uiState.messages.asReversed(), key = { it.id }) { message ->
                            MessageBubble(message = message)
                        }
                    }
                }
            }
        }

        ScrollIndicator(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun ComposeContent(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = enabled,
            singleLine = false,
            maxLines = 5,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (draft.isBlank()) {
                        Text(
                            text = "Message",
                            style = MaterialTheme.typography.bodyMedium,
                            color = placeholderColor
                        )
                    }
                    innerTextField()
                }
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = onSend,
                enabled = enabled && draft.isNotBlank(),
                modifier = Modifier
                    .width(72.dp)
                    .defaultMinSize(minHeight = 40.dp)
            ) {
                Text(
                    text = if (!enabled) "..." else "Send",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun EmptyConversation(
    conversation: WearConversation?,
    agent: WearAgent?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = conversation?.title?.ifBlank { null }
                ?: agent?.let { "Chat with ${it.name}" }
                ?: "Chat",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Swipe left to reply",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
