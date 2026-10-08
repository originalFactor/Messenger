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
import cc.ptoe.messenger.data.local.entity.ModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models")
    fun getAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId")
    fun getByProviderId(providerId: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE providerId = :providerId AND isEnabled = 1")
    fun getEnabledByProviderId(providerId: String): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE id = :id")
    fun getById(id: String): Flow<ModelEntity?>

    @Upsert
    suspend fun insert(model: ModelEntity)

    @Upsert
    suspend fun insertAll(models: List<ModelEntity>)

    @Query("DELETE FROM models WHERE providerId = :providerId")
    suspend fun deleteByProviderId(providerId: String)

    @Update
    suspend fun update(model: ModelEntity)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM models")
    suspend fun getAllEntities(): List<ModelEntity>

    @Query("DELETE FROM models")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM models")
    suspend fun count(): Int

    @Query("UPDATE models SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setEnabled(id: String, isEnabled: Boolean)

    @Query("DELETE FROM models WHERE id IN (:ids)")
    suspend fun deleteBatch(ids: List<String>)

    @Query("UPDATE models SET isEnabled = :isEnabled WHERE id IN (:ids)")
    suspend fun setEnabledBatch(ids: List<String>, isEnabled: Boolean)

    @Query("SELECT providerId FROM models WHERE id IN (:ids) LIMIT 1")
    suspend fun getFirstProviderIdByIds(ids: List<String>): String?
}
