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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/**
 * Android actual：用 [PredictiveBackHandler] 接住预测性返回手势，让系统
 * 知道应用内返回正在被处理，并按 Material predictive back 规范驱动
 * [content] 跟手动画（缩至 90%、圆角增大、向手势起始边移动 16dp）。
 * 手势取消时立即弹回原状。
 */
@Composable
actual fun BackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    // -1 = 左边缘起手，+1 = 右边缘起手，0 = 无手势进行中
    var swipeEdge by remember { mutableIntStateOf(0) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val shiftPx = with(density) { 16.dp.toPx() }

    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event ->
                swipeEdge = when (event.swipeEdge) {
                    BackEventCompat.EDGE_LEFT -> -1
                    else -> 1
                }
                backProgress = event.progress
            }
            onBack()
        } catch (e: CancellationException) {
            throw e
        } finally {
            backProgress = 0f
            swipeEdge = 0
        }
    }

    val progress = backProgress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape((28f * progress).dp))
            .graphicsLayer {
                scaleX = 1f - 0.1f * progress
                scaleY = 1f - 0.1f * progress
                translationX = swipeEdge * progress * shiftPx
                shadowElevation = 8f * progress * density.density
            }
    ) {
        content()
    }
}
