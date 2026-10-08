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
import cc.ptoe.messenger.core.StoredProjectDto
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class RustProjectRepository(
    private val coreBridge: CoreBridge,
    private val onChanged: (String, Boolean) -> Unit = { _, _ -> }
) : ProjectRepository {

    private val version = MutableStateFlow(0L)

    init {
        coreBridge.subscribe { kind, _ ->
            // A conversation moving between projects changes the project lists
            // too, so both kinds re-read.
            if (kind == "Project" || kind == "Conversation" || kind == "All" || kind == "Other") {
                version.value = currentTimeMillis()
            }
        }
    }

    override fun getAll(): Flow<List<Project>> = version.map {
        val json = coreBridge.listProjectsJson()
        runCatching {
            NetworkClient.json.decodeFromString<List<StoredProjectDto>>(json).map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    override fun getById(id: String): Flow<Project?> = version.map {
        val json = coreBridge.getProjectJson(id) ?: return@map null
        runCatching {
            NetworkClient.json.decodeFromString<StoredProjectDto>(json).toDomain()
        }.getOrNull()
    }

    override suspend fun insert(project: Project) {
        coreBridge.upsertProjectJson(NetworkClient.json.encodeToString(project.toDto()))
        version.value = currentTimeMillis()
        onChanged(project.id, false)
    }

    override suspend fun update(project: Project) {
        coreBridge.upsertProjectJson(NetworkClient.json.encodeToString(project.toDto()))
        version.value = currentTimeMillis()
        onChanged(project.id, false)
    }

    override suspend fun delete(id: String) {
        coreBridge.deleteProject(id)
        version.value = currentTimeMillis()
        onChanged(id, true)
    }

    private fun StoredProjectDto.toDomain() = Project(
        id = id,
        name = name,
        workspace = workspace,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun Project.toDto() = StoredProjectDto(
        id = id,
        name = name,
        workspace = workspace,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}