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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.ApiFormat
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.providers_view_models
import cc.ptoe.messenger.presentation.viewmodel.ProviderWithModelCount
import kotlin.test.Test
import org.jetbrains.compose.resources.getString

/**
 * Provider 行不再用右箭头暗示「可点进去」：整行可点，右侧只留溢出菜单。
 *
 * 回归：行尾曾同时有溢出菜单和 `>` 箭头，两者视觉上重复，
 * 且箭头会让人误以为只有箭头可点。
 */
@OptIn(ExperimentalTestApi::class)
class ProviderListItemTest {

    private val provider = Provider(
        id = "p1",
        name = "Messenger Cloud AI",
        baseUrl = "http://192.168.1.50:3000/v1",
        apiKey = "sk-test",
        apiFormat = ApiFormat.CHAT,
        createdAt = 1,
        updatedAt = 1
    )

    @Test
    fun rowRendersWithoutATrailingChevron() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(411.dp)) {
                    ProviderListItem(
                        item = ProviderWithModelCount(provider, modelCount = 4),
                        onClick = {},
                        onEditClick = {},
                        onDeleteClick = {}
                    )
                }
            }
        }

        onNodeWithText(provider.name).assertIsDisplayed()
        // 箭头原本带 contentDescription = 「View models」；现在只剩溢出菜单。
        onNodeWithContentDescription(getString(Res.string.providers_view_models)).assertDoesNotExist()
    }
}
