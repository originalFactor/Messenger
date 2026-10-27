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

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.input.TextFieldValue
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.chat_add_image
import cc.ptoe.messenger.generated.resources.chat_mode_readonly
import cc.ptoe.messenger.generated.resources.chat_mode_writable
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.compose.resources.getString

/**
 * 「+」面板的只读/可写入口。
 *
 * 回归：ChatScreen 曾用 `createBuiltinChatTools(null)`（即"该会话没有项目
 * 工作区"）判断平台是否支持工具，结果任何平台都得到空列表，切换项在
 * Desktop/Android 上完全不出现。这里钉住 ChatInputBar 的契约：只有平台
 * 明确报告"无工具"（`agentWritable == null`）时才隐藏入口。
 */
@OptIn(ExperimentalTestApi::class)
class ChatInputBarModeEntryTest {

    @Test
    fun theModeEntryShowsTheCurrentModeAndTogglesIt() = runComposeUiTest {
        var reported: Boolean? = null
        setContent {
            MaterialTheme {
                ChatInputBar(
                    text = TextFieldValue(""),
                    onTextChange = {},
                    onSendClick = {},
                    onStopClick = {},
                    isGenerating = false,
                    agentWritable = false,
                    onAgentModeChange = { reported = it }
                )
            }
        }

        onNodeWithContentDescription(getString(Res.string.chat_add_image)).performClick()
        onNodeWithText(getString(Res.string.chat_mode_readonly))
            .assertIsDisplayed()
            .performClick()

        assertEquals(true, reported, "tapping read-only must ask for writable mode")
    }

    @Test
    fun aPlatformWithoutToolsHidesTheModeEntry() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ChatInputBar(
                    text = TextFieldValue(""),
                    onTextChange = {},
                    onSendClick = {},
                    onStopClick = {},
                    isGenerating = false,
                    // null = 平台没有内置工具（Web / Android 未安装伴随 runtime）
                    agentWritable = null,
                    onAgentModeChange = {}
                )
            }
        }

        onNodeWithContentDescription(getString(Res.string.chat_add_image)).performClick()
        // 面板本身展开（图片入口在），只是没有模式入口
        onNodeWithText(getString(Res.string.chat_add_image)).assertIsDisplayed()
        onNodeWithText(getString(Res.string.chat_mode_readonly)).assertDoesNotExist()
        onNodeWithText(getString(Res.string.chat_mode_writable)).assertDoesNotExist()
    }
}
