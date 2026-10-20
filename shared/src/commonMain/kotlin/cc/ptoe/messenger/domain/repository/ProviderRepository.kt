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

package cc.ptoe.messenger.domain.repository

import cc.ptoe.messenger.domain.model.Provider
import kotlinx.coroutines.flow.Flow

interface ProviderRepository {
    fun getAll(): Flow<List<Provider>>
    fun getById(id: String): Flow<Provider?>
    suspend fun insert(provider: Provider)
    suspend fun update(provider: Provider)
    suspend fun delete(id: String)
}
