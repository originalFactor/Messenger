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

package cc.ptoe.messenger.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import kotlin.reflect.KClass
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.model.normalizeWorkspaceFolderName
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 项目创建/编辑表单的状态。 */
data class ProjectEditorState(
    val id: String? = null,
    val name: String = "",
    val workspace: String = "",
    /** 用户未指定文件夹时，界面展示这个自动创建的目录名。 */
    val autoWorkspaceFolder: String = "",
    val isSaving: Boolean = false
) {
    /** 未指定文件夹时按名称自动创建（"Messenger UI" → "messenger-ui"）。 */
    val resolvedWorkspace: String get() = workspace.ifBlank { autoWorkspaceFolder }
    val canSave: Boolean get() = name.isNotBlank() && !isSaving
}

/**
 * 项目列表 + 创建/编辑的 ViewModel，聊天列表页与项目二级页共用。
 *
 * 列表按每个项目的最近会话时间排序（没有会话时退回项目自身的 updatedAt），
 * 所以最活跃的项目总是排在前面。
 */
class ProjectsViewModel(
    private val projectRepository: ProjectRepository,
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    val projects: StateFlow<List<Project>> = projectRepository.getAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** 每个项目的会话数（项目行的副标题）。 */
    val conversationCounts: StateFlow<Map<String, Int>> = conversationRepository.getAll()
        .let { flow -> flow.map { all -> all.mapNotNull { it.projectId }.groupingBy { it }.eachCount() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    fun startCreate(): ProjectEditorState = ProjectEditorState()

    fun startEdit(project: Project): ProjectEditorState = ProjectEditorState(
        id = project.id,
        name = project.name,
        workspace = project.workspace,
        autoWorkspaceFolder = project.workspaceFolderName
    )

    fun nameChanged(state: ProjectEditorState, name: String): ProjectEditorState =
        state.copy(name = name, autoWorkspaceFolder = autoFolderFor(name))

    fun workspaceChanged(state: ProjectEditorState, workspace: String): ProjectEditorState =
        state.copy(workspace = workspace)

    fun save(state: ProjectEditorState, onSaved: (String) -> Unit = {}) {
        if (!state.canSave) return
        viewModelScope.launch {
            val now = currentTimeMillis()
            val project = Project(
                id = state.id ?: randomUuid(),
                name = state.name.trim(),
                workspace = state.resolvedWorkspace,
                createdAt = now,
                updatedAt = now
            )
            if (state.id == null) projectRepository.insert(project) else projectRepository.update(project)
            onSaved(project.id)
        }
    }

    fun delete(projectId: String) {
        viewModelScope.launch { projectRepository.delete(projectId) }
    }

    fun getProject(id: String): StateFlow<Project?> = projectRepository.getById(id)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    companion object {
        /** Auto-created workspace directory name for [name]. */
        fun autoFolderFor(name: String): String = normalizeWorkspaceFolderName(name)

        fun provideFactory(
            projectRepository: ProjectRepository,
            conversationRepository: ConversationRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
                return ProjectsViewModel(projectRepository, conversationRepository) as T
            }
        }
    }
}

/** The normalized workspace folder name for a project name (top-level helper
 * for the editor UI, which shows it before the project exists). */
private fun normalizeWorkspaceFolderName(name: String): String =
    cc.ptoe.messenger.domain.model.normalizeWorkspaceFolderName(name)