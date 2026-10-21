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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import cc.ptoe.messenger.domain.model.ChatModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 模型行的多选表现：选中只改整行底色，行内控件与排版不动。
 *
 * 回归一：元数据标签曾与名称同排，窄屏上名称被挤成省略号。
 * 回归二：多选曾靠插入复选框、隐藏开关/删除按钮表达，长按后整行“跳版”；
 * 现在只改底色，选中状态同时通过 semantics 暴露给无障碍与测试。
 */
@OptIn(ExperimentalTestApi::class)
class ModelListItemTest {

    private fun model(id: String) = ChatModel(
        id = id,
        providerId = "p1",
        modelId = "$id-very-long-model-id",
        displayName = "$id-very-long-model-id",
        isEnabled = true,
        contextWindow = 1_000_000,
        inputRate = 0.33,
        outputRate = 0.99,
        cacheReadRate = 4.35,
        createdAt = 1
    )

    @Test
    fun metadataBadgesSitBelowTheModelName() = runComposeUiTest {
        val model = model("plain")
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

    @Test
    fun enteringMultiSelectModeKeepsTheRowLayout() = runComposeUiTest {
        val model = model("selected")
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    ModelListItem(
                        model = model,
                        isMultiSelectMode = true,
                        isSelected = true,
                        onToggleEnabled = {},
                        onDeleteClick = {},
                        onLongClick = {},
                        onClick = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // 多选态下名称与开关仍在原位：不插入复选框、不隐藏右侧控件。
        onNodeWithText(model.displayName, useUnmergedTree = true).assertIsDisplayed()
        onNode(isToggleable(), useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * 选中态与普通态在同一张截图里对比：行高一致、只有底色不同，
     * 且选中底色就是主题的 secondaryContainer。
     */
    @Test
    fun selectionOnlyChangesTheRowBackground() = runComposeUiTest {
        val plain = model("plain")
        val selected = model("selected")
        var expectedColor: Color? = null
        setContent {
            MaterialTheme {
                expectedColor = MaterialTheme.colorScheme.secondaryContainer
                Column(modifier = Modifier.width(320.dp)) {
                    ModelListItem(
                        model = plain,
                        isMultiSelectMode = false,
                        isSelected = false,
                        onToggleEnabled = {},
                        onDeleteClick = {},
                        onLongClick = {},
                        onClick = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                    ModelListItem(
                        model = selected,
                        isMultiSelectMode = true,
                        isSelected = true,
                        onToggleEnabled = {},
                        onDeleteClick = {},
                        onLongClick = {},
                        onClick = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        val plainRow = onNodeWithText(plain.displayName, useUnmergedTree = false).getUnclippedBoundsInRoot()
        val selectedRow = onNodeWithText(selected.displayName, useUnmergedTree = false).getUnclippedBoundsInRoot()
        assertEquals(
            plainRow.height,
            selectedRow.height,
            "selection must not change the row height (plain=$plainRow, selected=$selectedRow)",
        )

        val pixels = onRoot().captureToImage().toPixelMap()
        // 行顶内边距处没有子控件，取那里的像素即整行底色。
        val plainPixel = pixels[sampleX(plainRow), sampleY(plainRow)]
        val selectedPixel = pixels[sampleX(selectedRow), sampleY(selectedRow)]
        assertEquals(
            expectedColor,
            selectedPixel,
            "a selected row must be painted with secondaryContainer (actual=$selectedPixel)"
        )
        assertTrue(
            plainPixel != selectedPixel,
            "selection must change the row background (plain=$plainPixel, selected=$selectedPixel)"
        )
    }

    private fun ComposeUiTest.sampleX(row: DpRect): Int =
        with(density) { (row.right - 12.dp).roundToPx() }

    private fun ComposeUiTest.sampleY(row: DpRect): Int =
        with(density) { (row.top + 6.dp).roundToPx() }

    @Test
    fun selectionIsExposedToAccessibility() = runComposeUiTest {
        val model = model("unselected")
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    ModelListItem(
                        model = model,
                        isMultiSelectMode = true,
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
        onNodeWithText(model.displayName, useUnmergedTree = false).assertIsNotSelected()
    }

    @Test
    fun selectedRowIsMarkedSelectedForAccessibility() = runComposeUiTest {
        val model = model("selected")
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(320.dp)) {
                    ModelListItem(
                        model = model,
                        isMultiSelectMode = true,
                        isSelected = true,
                        onToggleEnabled = {},
                        onDeleteClick = {},
                        onLongClick = {},
                        onClick = {},
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        onNodeWithText(model.displayName, useUnmergedTree = false).assertIsSelected()
    }
}
