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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.providers_provider_settings
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.getString

/**
 * 模型列表页右上角必须有进入服务商设置的入口。
 */
@OptIn(ExperimentalTestApi::class)
class ProviderDetailTopBarTest {

    @Test
    fun settingsActionOpensTheProviderEditor() = runComposeUiTest {
        var opened = false
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(411.dp)) {
                    ProviderDetailTopBar(
                        title = "Messenger Cloud AI",
                        canEditProvider = true,
                        onBackClick = {},
                        onEditProviderClick = { opened = true }
                    )
                }
            }
        }

        onNodeWithContentDescription(getString(Res.string.providers_provider_settings))
            .assertIsDisplayed()
            .performClick()
        assertTrue(opened, "the settings action must open the provider editor")
    }

    @Test
    fun builtinProviderHasNoSettingsAction() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(411.dp)) {
                    ProviderDetailTopBar(
                        title = "Messenger Cloud AI",
                        canEditProvider = false,
                        onBackClick = {},
                        onEditProviderClick = {}
                    )
                }
            }
        }

        // 内置云服务商由登录流程配置，不提供编辑入口。
        onNodeWithContentDescription(getString(Res.string.providers_provider_settings)).assertDoesNotExist()
    }
}
