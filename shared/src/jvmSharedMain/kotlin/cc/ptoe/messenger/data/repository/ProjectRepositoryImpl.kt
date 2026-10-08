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

import cc.ptoe.messenger.data.local.dao.ProjectDao
import cc.ptoe.messenger.data.local.entity.ProjectEntity
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProjectRepositoryImpl(
    private val projectDao: ProjectDao,
    private val onChanged: (String, Boolean) -> Unit = { _, _ -> }
) : ProjectRepository {

    override fun getAll(): Flow<List<Project>> =
        projectDao.getAll().map { entities -> entities.map { it.toDomain() } }

    override fun getById(id: String): Flow<Project?> =
        projectDao.getById(id).map { entity -> entity?.toDomain() }

    override suspend fun insert(project: Project) {
        projectDao.upsert(project.toEntity())
        onChanged(project.id, false)
    }

    override suspend fun update(project: Project) {
        projectDao.upsert(project.toEntity())
        onChanged(project.id, false)
    }

    override suspend fun delete(id: String) {
        // projectId 的 ON DELETE SET NULL 让会话保留为普通会话。
        projectDao.delete(id)
        onChanged(id, true)
    }

    private fun ProjectEntity.toDomain() = Project(
        id = id,
        name = name,
        workspace = workspace,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun Project.toEntity() = ProjectEntity(
        id = id,
        name = name,
        workspace = workspace,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}