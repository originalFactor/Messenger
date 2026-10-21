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

package cc.ptoe.messenger.presentation.viewmodel

import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.ModelModality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 保存/删除的信号必须能稳定到达界面：编辑页据此立即返回模型列表。
 *
 * 回归：保存后 store 会回推一次新值，若它把 isSaved 清掉，界面就可能
 * 观察不到「已保存」而停在编辑页，Save 按钮看起来没有反应。
 */
class ModelDetailViewModelTest {

    private val model = ChatModel(
        id = "m1",
        providerId = "p1",
        modelId = "deepseek-v4-flash",
        displayName = "deepseek-v4-flash",
        isEnabled = true,
        contextWindow = 128_000,
        inputModalities = setOf(ModelModality.TEXT),
        outputModalities = setOf(ModelModality.TEXT),
        createdAt = 1
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun saveWritesTheFormAndSignalsSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeModelRepository(model)
            val viewModel = ModelDetailViewModel(repository, model.id)
            advanceUntilIdle()
            viewModel.onContextWindowChange("200000")
            viewModel.onEnabledChange(false)

            viewModel.save()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isSaved, "saving must signal success")
            val stored = repository.models.value.single()
            assertEquals(200_000L, stored.contextWindow)
            assertEquals(false, stored.isEnabled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deleteRemovesTheModelAndSignalsSuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeModelRepository(model)
            val viewModel = ModelDetailViewModel(repository, model.id)
            advanceUntilIdle()

            viewModel.delete()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isDeleted, "deleting must signal success")
            assertTrue(repository.models.value.isEmpty(), "the model must be deleted from the store")
        } finally {
            Dispatchers.resetMain()
        }
    }
}
