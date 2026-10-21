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

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.providers_model_delete_action
import cc.ptoe.messenger.generated.resources.providers_model_enabled_label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.getString

/**
 * 模型编辑页接管了原先在列表行里的两个操作：启用开关与删除入口。
 */
@OptIn(ExperimentalTestApi::class)
class ModelDetailControlsTest {

    @Test
    fun enabledSwitchReflectsAndReportsTheState() = runComposeUiTest {
        val changes = mutableListOf<Boolean>()
        setContent {
            MaterialTheme {
                ModelEnabledSwitchRow(
                    checked = true,
                    onCheckedChange = { changes += it },
                    modifier = Modifier.width(360.dp)
                )
            }
        }

        onNodeWithText(getString(Res.string.providers_model_enabled_label)).assertIsDisplayed()
        val toggle = onNode(isToggleable())
        toggle.assertIsOn()
        toggle.performClick()
        assertEquals(listOf(false), changes, "toggling the switch must report the new state")
    }

    @Test
    fun enabledSwitchStartsOffForADisabledModel() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ModelEnabledSwitchRow(
                    checked = false,
                    onCheckedChange = {},
                    modifier = Modifier.width(360.dp)
                )
            }
        }
        onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun deleteButtonInvokesTheConfirmationEntryPoint() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                ModelDeleteButton(
                    enabled = true,
                    onClick = { clicked = true },
                    modifier = Modifier.width(360.dp)
                )
            }
        }

        onNodeWithText(getString(Res.string.providers_model_delete_action)).assertIsDisplayed()
        onNodeWithText(getString(Res.string.providers_model_delete_action)).performClick()
        assertTrue(clicked, "the delete entry must open the confirmation dialog")
    }
}
