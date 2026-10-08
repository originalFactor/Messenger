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

package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.StoredModelDto
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.ModelModality
import cc.ptoe.messenger.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

import cc.ptoe.messenger.data.util.currentTimeMillis

class RustModelRepository(
    private val coreBridge: CoreBridge,
    private val onChanged: (String, Boolean) -> Unit = { _, _ -> }
) : ModelRepository {

    private val version = MutableStateFlow(0L)

    init {
        coreBridge.subscribe { kind, _ ->
            if (kind == "Model" || kind == "Provider" || kind == "All" || kind == "Other") {
                version.value = currentTimeMillis()
            }
        }
    }

    override fun getAll(): Flow<List<ChatModel>> = version.map {
        val json = coreBridge.listModelsJson()
        parseModels(json)
    }

    override fun getByProviderId(providerId: String): Flow<List<ChatModel>> = version.map {
        val json = coreBridge.listModelsByProviderJson(providerId)
        parseModels(json)
    }

    override fun getEnabledByProviderId(providerId: String): Flow<List<ChatModel>> = version.map {
        val json = coreBridge.listModelsByProviderJson(providerId)
        parseModels(json).filter { it.isEnabled }
    }

    override fun getById(id: String): Flow<ChatModel?> = version.map {
        val json = coreBridge.getModelJson(id) ?: return@map null
        runCatching {
            NetworkClient.json.decodeFromString<StoredModelDto>(json).toDomain()
        }.getOrNull()
    }

    override suspend fun insert(model: ChatModel) {
        val dto = model.toDto()
        coreBridge.upsertModelJson(NetworkClient.json.encodeToString(dto))
        version.value = currentTimeMillis()
        onChanged(model.providerId, false)
    }

    override suspend fun insertAll(models: List<ChatModel>) {
        for (m in models) {
            insert(m)
        }
    }

    override suspend fun update(model: ChatModel) {
        insert(model)
    }

    override suspend fun delete(id: String) {
        coreBridge.deleteModel(id)
        version.value = currentTimeMillis()
    }

    override suspend fun deleteBatch(ids: List<String>) {
        for (id in ids) delete(id)
    }

    override suspend fun setEnabled(id: String, isEnabled: Boolean) {
        coreBridge.setModelEnabled(id, isEnabled)
        version.value = currentTimeMillis()
    }

    override suspend fun setEnabledBatch(ids: List<String>, isEnabled: Boolean) {
        for (id in ids) setEnabled(id, isEnabled)
    }

    private fun parseModels(json: String): List<ChatModel> {
        return runCatching {
            NetworkClient.json.decodeFromString<List<StoredModelDto>>(json).map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    private fun StoredModelDto.toDomain() = ChatModel(
        id = id,
        providerId = providerId,
        modelId = modelId,
        displayName = displayName,
        isEnabled = isEnabled,
        contextWindow = contextWindow,
        inputRate = inputRate,
        outputRate = outputRate,
        inputModalities = ModelModality.fromCsv(inputModalities),
        outputModalities = ModelModality.fromCsv(outputModalities),
        supportsToolCalling = supportsToolCalling,
        supportsThinking = supportsThinking,
        supportsJsonOutput = supportsJsonOutput,
        supportsTemperature = supportsTemperature,
        createdAt = createdAt
    )

    private fun ChatModel.toDto() = StoredModelDto(
        id = id,
        providerId = providerId,
        modelId = modelId,
        displayName = displayName,
        isEnabled = isEnabled,
        contextWindow = contextWindow,
        inputRate = inputRate,
        outputRate = outputRate,
        inputModalities = ModelModality.toCsv(inputModalities),
        outputModalities = ModelModality.toCsv(outputModalities),
        supportsToolCalling = supportsToolCalling,
        supportsThinking = supportsThinking,
        supportsJsonOutput = supportsJsonOutput,
        supportsTemperature = supportsTemperature,
        createdAt = createdAt
    )
}
