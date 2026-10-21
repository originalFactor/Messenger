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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_manual_add
import cc.ptoe.messenger.generated.resources.action_sync_models
import cc.ptoe.messenger.generated.resources.providers_model_list
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.compose.resources.getString

/**
 * 多选态不能改变列表的几何布局。
 *
 * 回归：标题行（Model list + 同步/添加按钮）曾整块在多选态移除，
 * 长按进入多选时列表整体上移，画面突变；现在标题行常驻，只禁用按钮。
 */
@OptIn(ExperimentalTestApi::class)
class ProviderModelListTest {

    private fun model(id: String) = ChatModel(
        id = id,
        providerId = "p1",
        modelId = id,
        displayName = id,
        isEnabled = true,
        contextWindow = 128_000,
        inputRate = 0.1,
        outputRate = 0.2,
        createdAt = 1
    )

    private val models = listOf(model("alpha"), model("beta"))

    @Test
    fun headerStaysInPlaceWhenEnteringMultiSelect() = runComposeUiTest {
        var multiSelect by mutableStateOf(false)
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(360.dp).height(400.dp)) {
                    ProviderModelList(
                        models = models,
                        isMultiSelectMode = multiSelect,
                        selectedModelIds = if (multiSelect) setOf(models.first().id) else emptySet(),
                        onSyncClick = {},
                        onAddClick = {},
                        onToggleEnabled = { _, _ -> },
                        onDeleteClick = {},
                        onLongClick = {},
                        onClick = {},
                        modifier = Modifier.width(360.dp)
                    )
                }
            }
        }

        val listTitle = getString(Res.string.providers_model_list)
        val title = onNodeWithText(listTitle, useUnmergedTree = true)
        val firstRow = onNodeWithText("alpha", useUnmergedTree = true)
        title.assertIsDisplayed()
        val titleBefore = title.getUnclippedBoundsInRoot()
        val rowBefore = firstRow.getUnclippedBoundsInRoot()

        multiSelect = true
        waitForIdle()

        title.assertIsDisplayed()
        val titleAfter = title.getUnclippedBoundsInRoot()
        val rowAfter = onNodeWithText("alpha", useUnmergedTree = true).getUnclippedBoundsInRoot()

        assertEquals(titleBefore.top, titleAfter.top, "the model-list header must not move")
        assertEquals(rowBefore.top, rowAfter.top, "model rows must not shift when selection starts")
    }

    @Test
    fun syncAndAddAreDisabledDuringMultiSelect() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(360.dp).height(400.dp)) {
                    ProviderModelList(
                        models = models,
                        isMultiSelectMode = true,
                        selectedModelIds = setOf(models.first().id),
                        onSyncClick = {},
                        onAddClick = {},
                        onToggleEnabled = { _, _ -> },
                        onDeleteClick = {},
                        onLongClick = {},
                        onClick = {},
                        modifier = Modifier.width(360.dp)
                    )
                }
            }
        }

        // 标题与按钮都在，但按钮在多选态下不可点。
        onNodeWithText(getString(Res.string.providers_model_list), useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription(getString(Res.string.action_sync_models)).assertIsNotEnabled()
        onNodeWithContentDescription(getString(Res.string.action_manual_add)).assertIsNotEnabled()
    }
}
