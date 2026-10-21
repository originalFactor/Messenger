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

package cc.ptoe.messenger.presentation.ui.providers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.ChatModel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 模型行的元数据标签（上下文窗口、倍率）必须独占一行、排在模型名称下方。
 *
 * 回归：标签曾与名称挤在同一行，窄屏上「1M」+ 三段倍率把名称压成省略号，
 * 用户几乎看不到模型名。标签换行后名称重新拿到整行宽度。
 */
@OptIn(ExperimentalTestApi::class)
class ModelListItemTest {

    private val model = ChatModel(
        id = "m1",
        providerId = "p1",
        modelId = "kimi-k3-very-long-model-id",
        displayName = "kimi-k3-very-long-model-id",
        isEnabled = true,
        contextWindow = 1_000_000,
        inputRate = 0.33,
        outputRate = 0.99,
        cacheReadRate = 4.35,
        createdAt = 1
    )

    @Test
    fun metadataBadgesSitBelowTheModelName() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    ModelListItem(
                        model = model,
                        isMultiSelectMode = false,
                        isSelected = false,
                        onToggleEnabled = {},
                        onDeleteClick = {},
                        onLongClick = {},
                        onClick = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 「1M」与倍率数字与语言无关，避免依赖测试机的资源语言。
        val name = onNodeWithText(model.displayName, useUnmergedTree = true)
        val contextBadge = onNodeWithText("1M", useUnmergedTree = true)
        val rateBadge = onNode(hasText("×0.33", substring = true), useUnmergedTree = true)
        val switch = onNode(isToggleable(), useUnmergedTree = true)
        name.assertIsDisplayed()
        contextBadge.assertIsDisplayed()
        rateBadge.assertIsDisplayed()

        val nameBounds = name.getUnclippedBoundsInRoot()
        val contextBounds = contextBadge.getUnclippedBoundsInRoot()
        val rateBounds = rateBadge.getUnclippedBoundsInRoot()
        val switchBounds = switch.getUnclippedBoundsInRoot()
        assertTrue(
            contextBounds.top >= nameBounds.bottom,
            "context badge must start on a new line below the name (name=$nameBounds, badge=$contextBounds)"
        )
        assertTrue(
            rateBounds.top >= nameBounds.bottom,
            "rate badge must start on a new line below the name (name=$nameBounds, badge=$rateBounds)"
        )
        assertTrue(
            rateBounds.right <= switchBounds.left,
            "badges must stay clear of the switch (badge=$rateBounds, switch=$switchBounds)"
        )
    }
}
