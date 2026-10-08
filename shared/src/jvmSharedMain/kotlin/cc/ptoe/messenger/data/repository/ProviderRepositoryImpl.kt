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

import cc.ptoe.messenger.data.local.dao.ProviderDao
import cc.ptoe.messenger.data.local.entity.ProviderEntity
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProviderRepositoryImpl(
    private val providerDao: ProviderDao,
    private val onChanged: (String, Boolean) -> Unit = { _, _ -> }
) : ProviderRepository {

    override fun getAll(): Flow<List<Provider>> {
        return providerDao.getAll().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getById(id: String): Flow<Provider?> {
        return providerDao.getById(id).map { entity ->
            entity?.toDomain()
        }
    }

    override suspend fun insert(provider: Provider) {
        // TODO: 加密 API Key 后再保存到数据库
        providerDao.insert(provider.toEntity())
        onChanged(provider.id, false)
    }

    override suspend fun update(provider: Provider) {
        // TODO: 加密 API Key 后再保存到数据库
        providerDao.update(provider.toEntity())
        onChanged(provider.id, false)
    }

    override suspend fun delete(id: String) {
        providerDao.delete(id)
        onChanged(id, true)
    }

    private fun ProviderEntity.toDomain(): Provider {
        return Provider(
            id = id,
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }

    private fun Provider.toEntity(): ProviderEntity {
        return ProviderEntity(
            id = id,
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}
