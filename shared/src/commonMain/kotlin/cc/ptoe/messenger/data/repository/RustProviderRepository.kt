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
import cc.ptoe.messenger.core.StoredProviderDto
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

import cc.ptoe.messenger.data.util.currentTimeMillis

class RustProviderRepository(
    private val coreBridge: CoreBridge,
    private val onChanged: (String, Boolean) -> Unit = { _, _ -> }
) : ProviderRepository {

    private val version = MutableStateFlow(0L)

    init {
        coreBridge.subscribe { kind, _ ->
            if (kind == "Provider" || kind == "All" || kind == "Other") {
                version.value = currentTimeMillis()
            }
        }
    }

    override fun getAll(): Flow<List<Provider>> = version.map {
        val json = coreBridge.listProvidersJson()
        val dtos = runCatching {
            NetworkClient.json.decodeFromString<List<StoredProviderDto>>(json)
        }.getOrDefault(emptyList())
        dtos.map { it.toDomain() }
    }

    override fun getById(id: String): Flow<Provider?> = version.map {
        val json = coreBridge.getProviderJson(id) ?: return@map null
        runCatching {
            NetworkClient.json.decodeFromString<StoredProviderDto>(json).toDomain()
        }.getOrNull()
    }

    override suspend fun insert(provider: Provider) {
        val dto = provider.toDto()
        coreBridge.upsertProviderJson(NetworkClient.json.encodeToString(dto))
        version.value = currentTimeMillis()
        onChanged(provider.id, false)
    }

    override suspend fun update(provider: Provider) {
        val dto = provider.toDto()
        coreBridge.upsertProviderJson(NetworkClient.json.encodeToString(dto))
        version.value = currentTimeMillis()
        onChanged(provider.id, false)
    }

    override suspend fun delete(id: String) {
        coreBridge.deleteProvider(id)
        version.value = currentTimeMillis()
        onChanged(id, true)
    }

    private fun StoredProviderDto.toDomain() = Provider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun Provider.toDto() = StoredProviderDto(
        id = id,
        name = name,
        baseUrl = baseUrl,
        apiKey = apiKey,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
