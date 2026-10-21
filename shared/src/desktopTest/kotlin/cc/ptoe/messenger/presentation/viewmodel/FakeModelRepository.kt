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
import cc.ptoe.messenger.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** 内存版 ModelRepository：编辑页的保存/删除流程测试用。 */
internal class FakeModelRepository(initial: ChatModel) : ModelRepository {
    val models = MutableStateFlow(listOf(initial))

    override fun getAll(): Flow<List<ChatModel>> = models
    override fun getByProviderId(providerId: String): Flow<List<ChatModel>> = models
    override fun getEnabledByProviderId(providerId: String): Flow<List<ChatModel>> =
        MutableStateFlow(models.value.filter { it.isEnabled })

    // 必须跟随 models 变化，才能复现「保存后 store 回推新值」的真实时序。
    override fun getById(id: String): Flow<ChatModel?> =
        models.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun insert(model: ChatModel) = insertAll(listOf(model))

    override suspend fun insertAll(models: List<ChatModel>) {
        val incoming = models.associateBy { it.id }
        this.models.update { current -> current.filterNot { it.id in incoming.keys } + models }
    }

    override suspend fun update(model: ChatModel) = insertAll(listOf(model))

    override suspend fun delete(id: String) {
        models.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun deleteBatch(ids: List<String>) {
        ids.forEach { delete(it) }
    }

    override suspend fun setEnabled(id: String, isEnabled: Boolean) {
        models.update { current -> current.map { if (it.id == id) it.copy(isEnabled = isEnabled) else it } }
    }

    override suspend fun setEnabledBatch(ids: List<String>, isEnabled: Boolean) {
        ids.forEach { setEnabled(it, isEnabled) }
    }
}
