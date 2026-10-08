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
import cc.ptoe.messenger.data.local.entity.AgentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents")
    fun getAll(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :id")
    fun getById(id: String): Flow<AgentEntity?>

    @Query("SELECT * FROM agents WHERE isDefault = 1 LIMIT 1")
    fun getDefaultAgent(): Flow<AgentEntity?>

    @Upsert
    suspend fun insert(agent: AgentEntity)

    @Update
    suspend fun update(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM agents")
    suspend fun getAllEntities(): List<AgentEntity>

    @Query("DELETE FROM agents")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM agents")
    suspend fun count(): Int

    @Query("UPDATE agents SET marketAgentId = NULL, marketAgentVersion = NULL, marketAgentRole = NULL")
    suspend fun clearAllMarketLinks()
}
