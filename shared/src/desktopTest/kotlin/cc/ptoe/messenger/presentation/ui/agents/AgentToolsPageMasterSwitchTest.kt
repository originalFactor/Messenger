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

package cc.ptoe.messenger.presentation.ui.agents

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.assertIsNotEnabled
import cc.ptoe.messenger.presentation.viewmodel.AgentEditUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 工具总开关必须能在关闭状态下打开。
 *
 * 回归：总开关曾以 `state.toolsEnabled` 作为自身的 enabled 条件，导致工具关闭时
 * 开关不可点击（自锁），Desktop/Android 上点击均无反应、无日志。
 * 每工具开关以总开关为条件是正确的层叠，但总开关本身不能依赖它自己的值。
 */
@OptIn(ExperimentalTestApi::class)
class AgentToolsPageMasterSwitchTest {

    @Test
    fun masterSwitchIsEnabledAndClickableWhenToolsAreOff() = runComposeUiTest {
        val changes = mutableListOf<Boolean>()
        setContent {
            MaterialTheme {
                AgentToolsPage(
                    state = AgentEditUiState(isDefault = true, toolsEnabled = false),
                    tools = emptyList(),
                    onBack = {},
                    onToolsEnabledChange = { changes += it },
                    onToolsFollowDefaultChange = {},
                    onToolEnabledChange = { _, _ -> }
                )
            }
        }

        val masterSwitch = onAllNodes(isToggleable()).onFirst()
        masterSwitch.assertIsEnabled()
        masterSwitch.performClick()

        assertEquals(listOf(true), changes, "master switch must emit enabled=true when toggled on")
    }

    @Test
    fun masterSwitchIsReadOnlyWhileFollowingDefaultAgent() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AgentToolsPage(
                    state = AgentEditUiState(
                        isDefault = false,
                        toolsEnabled = false,
                        toolsFollowDefault = true,
                        defaultAgent = null
                    ),
                    tools = emptyList(),
                    onBack = {},
                    onToolsEnabledChange = {},
                    onToolsFollowDefaultChange = {},
                    onToolEnabledChange = { _, _ -> }
                )
            }
        }

        // 接管蒙层下总开关只读，避免误改被跟随的配置。
        onAllNodes(isToggleable()).onFirst().assertIsNotEnabled()
        assertTrue(onAllNodes(isToggleable()).fetchSemanticsNodes().isNotEmpty())
    }
}
