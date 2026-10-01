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

package cc.ptoe.messenger.presentation.platform

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 手势提交后补完退出动画的时长（与导航 pop 过渡一致，见 PageTransitions.kt）。 */
private const val COMMIT_DURATION_MS = 240

/**
 * Android actual：用 [PredictiveBackHandler] 接住预测性返回手势，让系统
 * 知道应用内返回正在被处理，并驱动 [content] 跟手「划出 + 渐淡」动画
 * （整页向手势起始边划出、透明度随进度渐隐）。手势提交时从当前进度补完
 * 退出动画再回调 [onBack]（内容停在不可见状态，宿主的退场过渡在底下播放，
 * 不会闪烁）；手势取消时立即弹回原状。
 */
@Composable
actual fun BackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    // -1 = 左边缘起手，+1 = 右边缘起手，0 = 无手势进行中
    var swipeEdge by remember { mutableIntStateOf(0) }
    val backProgress = remember { Animatable(0f) }
    var contentWidthPx by remember { mutableIntStateOf(0) }
    // onBack 之后内容留在宿主退场过渡里（已不可见），期间不再接新的返回手势
    var closed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PredictiveBackHandler(enabled = enabled && !closed) { events ->
        try {
            events.collect { event ->
                swipeEdge = when (event.swipeEdge) {
                    BackEventCompat.EDGE_LEFT -> -1
                    else -> 1
                }
                backProgress.snapTo(event.progress)
            }
            backProgress.animateTo(1f, tween(COMMIT_DURATION_MS))
            closed = true
            onBack()
        } catch (e: CancellationException) {
            // 手势取消：立即弹回原状（成功路径不重置——内容已不可见，等宿主移除）
            scope.launch { backProgress.snapTo(0f) }
            swipeEdge = 0
            throw e
        }
    }

    val progress = backProgress.value.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .onSizeChanged { contentWidthPx = it.width }
            .graphicsLayer {
                alpha = 1f - progress
                translationX = swipeEdge * progress * contentWidthPx
            }
    ) {
        content()
    }
}
