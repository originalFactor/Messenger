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

package cc.ptoe.messenger.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.Update
import cc.ptoe.messenger.data.local.entity.ProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers")
    fun getAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM providers WHERE id = :id")
    fun getById(id: String): Flow<ProviderEntity?>

    @Upsert
    suspend fun insert(provider: ProviderEntity)

    @Update
    suspend fun update(provider: ProviderEntity)

    @Query("DELETE FROM providers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM providers")
    suspend fun getAllEntities(): List<ProviderEntity>

    @Query("DELETE FROM providers")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM providers")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM providers WHERE id != :excludedId")
    suspend fun countExcluding(excludedId: String): Int
}
