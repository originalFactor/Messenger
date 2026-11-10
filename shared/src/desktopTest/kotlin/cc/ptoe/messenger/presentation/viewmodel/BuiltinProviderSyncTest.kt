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

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.cloud.BUILTIN_PROVIDER_ID
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.ProviderRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BuiltinProviderSyncTest {
    @Test
    fun builtinSyncUsesCloudCatalogAndRejectsLocalMutations() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val provider = Provider(BUILTIN_PROVIDER_ID, "Cloud", "https://cloud/v1", "test", createdAt = 1, updatedAt = 1)
            val providers = object : ProviderRepository {
                override fun getAll() = flowOf(listOf(provider))
                override fun getById(id: String) = flowOf(provider)
                override suspend fun insert(provider: Provider) = error("read only")
                override suspend fun update(provider: Provider) = error("read only")
                override suspend fun delete(id: String) = error("read only")
            }
            val model = ChatModel("m", provider.id, "cloud-model", "Cloud model", isEnabled = true, createdAt = 1)
            val models = FakeModelRepository(model)
            var syncs = 0
            var failSync = false
            val core = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, method, args ->
                check(method.name == "cloudSyncBuiltinModels") { "Unexpected ${method.name}; must not use discovery or models.dev" }
                assertEquals(true, args[0])
                syncs++
                if (failSync) error("Cloud unavailable")
                1L
            } as CoreBridge
            val viewModel = ProviderDetailViewModel(providers, models, core, provider.id)
            advanceUntilIdle()
            viewModel.syncModels()
            advanceUntilIdle()
            assertEquals(1, syncs)
            assertEquals(SyncStatus.SUCCESS, viewModel.uiState.value.syncStatus)
            assertTrue(viewModel.uiState.value.syncedModels.isEmpty(), "Cloud sync must not open the import picker")
            assertFalse(viewModel.addModelManually("extra", "Extra"))
            viewModel.toggleModelEnabled(model.id, false)
            viewModel.deleteModel(model.id)
            viewModel.enterMultiSelectMode(model.id)
            viewModel.saveSelectedModels(listOf("extra"))
            advanceUntilIdle()
            assertEquals(listOf(model), models.models.value)
            assertFalse(viewModel.uiState.value.isMultiSelectMode)
            failSync = true
            viewModel.syncModels()
            advanceUntilIdle()
            assertEquals(SyncStatus.ERROR, viewModel.uiState.value.syncStatus)
            assertEquals("Cloud unavailable", viewModel.uiState.value.syncError)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
