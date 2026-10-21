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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.providers_model_capabilities
import cc.ptoe.messenger.generated.resources.providers_model_context_window_label
import cc.ptoe.messenger.generated.resources.providers_model_enabled_label
import cc.ptoe.messenger.presentation.viewmodel.ModelDetailUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.getString

/**
 * 模型编辑页不再有分区标题，字段之间保持紧凑。
 *
 * 回归：上下文窗口输入框上方曾重复一遍「上下文窗口（tokens）」分区标题，
 * 能力区上方还有「模型能力」标题，加上 16dp 的行距，整页非常松散。
 */
@OptIn(ExperimentalTestApi::class)
class ModelDetailFormTest {

    private val model = ChatModel(
        id = "m1",
        providerId = "p1",
        modelId = "deepseek-v4-flash",
        displayName = "deepseek-v4-flash",
        isEnabled = true,
        contextWindow = 1_000_000,
        createdAt = 1
    )

    @Test
    fun sectionHeadersAreGone() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(411.dp)) {
                    ModelDetailForm(
                        state = ModelDetailUiState(
                            model = model,
                            isLoading = false,
                            contextWindowText = "1000000"
                        ),
                        onContextWindowChange = {},
                        onEnabledChange = {},
                        onToggleInputModality = {},
                        onToggleOutputModality = {},
                        onToolCallingChange = {},
                        onThinkingChange = {},
                        onJsonOutputChange = {},
                        onTemperatureChange = {},
                        onDeleteClick = {}
                    )
                }
            }
        }

        // 「模型能力」分区标题已移除。
        assertEquals(
            0,
            onAllNodesWithText(getString(Res.string.providers_model_capabilities), useUnmergedTree = true)
                .fetchSemanticsNodes().size,
            "the capabilities section header must be gone"
        )
        // 「上下文窗口」只作为输入框自身的 label 出现一次，不再有额外的分区标题。
        assertEquals(
            1,
            onAllNodesWithText(
                getString(Res.string.providers_model_context_window_label),
                useUnmergedTree = true
            ).fetchSemanticsNodes().size,
            "the context-window label must appear once (the text field's own label)"
        )
    }

    @Test
    fun fieldsAreVerticallyCompact() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(411.dp)) {
                    ModelDetailForm(
                        state = ModelDetailUiState(
                            model = model,
                            isLoading = false,
                            contextWindowText = "1000000"
                        ),
                        onContextWindowChange = {},
                        onEnabledChange = {},
                        onToggleInputModality = {},
                        onToggleOutputModality = {},
                        onToolCallingChange = {},
                        onThinkingChange = {},
                        onJsonOutputChange = {},
                        onTemperatureChange = {},
                        onDeleteClick = {}
                    )
                }
            }
        }

        val enabled = onNodeWithText(
            getString(Res.string.providers_model_enabled_label),
            useUnmergedTree = true
        ).getUnclippedBoundsInRoot()
        val contextField = onNodeWithText(
            getString(Res.string.providers_model_context_window_label),
            useUnmergedTree = true
        ).getUnclippedBoundsInRoot()

        // 启用行与上下文窗口输入框之间只隔一个 12dp 的行距（label 落在输入框内部，
        // 所以量到的间距还包含 label 在框内的偏移）。旧布局此处是
        // 16dp 行距 + 带 8dp 内边距的分区标题 + 额外 8dp Spacer，约 76dp。
        val gap = contextField.top - enabled.bottom
        assertTrue(
            gap <= 40.dp,
            "gap between the enabled row and the context field must stay compact (gap=$gap)"
        )
    }
}
