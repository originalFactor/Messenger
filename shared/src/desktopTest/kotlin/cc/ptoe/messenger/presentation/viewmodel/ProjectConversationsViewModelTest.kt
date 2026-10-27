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

package cc.ptoe.messenger.presentation.viewmodel

import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.CurrentAgentRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ProjectRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Tap a project but stay in No project" regression: the detail pane reuses
 * one `ProjectConversationsViewModel` (the default ViewModel key is only the
 * class name, so a Compose `key(...)` around the call site does not create a
 * new instance). The folder list and the projectId written by "new chat" must
 * therefore follow [ProjectConversationsViewModel.loadFolder], not the
 * constructor arguments.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectConversationsViewModelTest {

    private val agent = Agent(
        id = "agent1",
        name = "Agent",
        systemPrompt = "",
        defaultModelId = "model1",
        temperature = 1f,
        topP = 1f,
        maxTokens = null,
        createdAt = 1,
        updatedAt = 1
    )

    private val model = ChatModel(
        id = "model1",
        providerId = "provider1",
        modelId = "gpt",
        displayName = "gpt",
        isEnabled = true,
        createdAt = 1
    )

    private val plainChat = Conversation(
        id = "plain",
        title = "Plain chat",
        providerId = "provider1",
        agentId = agent.id,
        createdAt = 1,
        updatedAt = 1,
        lastMessage = null
    )

    private val projectChat = Conversation(
        id = "project-chat",
        title = "Project chat",
        providerId = "provider1",
        agentId = agent.id,
        projectId = "p1",
        createdAt = 2,
        updatedAt = 2,
        lastMessage = null
    )

    private class FakeProjects(private val projects: List<Project>) : ProjectRepository {
        override fun getAll(): Flow<List<Project>> = MutableStateFlow(projects)
        override fun getById(id: String): Flow<Project?> =
            MutableStateFlow(projects.firstOrNull { it.id == id })
        override suspend fun insert(project: Project) = Unit
        override suspend fun update(project: Project) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private class FakeConversations(initial: List<Conversation>) : ConversationRepository {
        val rows = MutableStateFlow(initial)
        override fun getAll(): Flow<List<Conversation>> = rows
        override fun getByAgentId(agentId: String): Flow<List<Conversation>> =
            rows.map { all -> all.filter { it.agentId == agentId } }
        override fun getByProjectId(projectId: String): Flow<List<Conversation>> =
            rows.map { all -> all.filter { it.projectId == projectId } }
        override fun getById(id: String): Flow<Conversation?> =
            rows.map { all -> all.firstOrNull { it.id == id } }
        override suspend fun insert(conversation: Conversation) {
            rows.value = rows.value + conversation
        }
        override suspend fun update(conversation: Conversation) = Unit
        override suspend fun updateLastMessage(id: String, lastMessage: String, updatedAt: Long) = Unit
        override suspend fun delete(id: String) {
            rows.value = rows.value.filterNot { it.id == id }
        }
        override suspend fun sweepEmptyConversations(excludeIds: List<String>): List<String> = emptyList()
    }

    private inner class FakeAgents : AgentRepository {
        override fun getAll(): Flow<List<Agent>> = MutableStateFlow(listOf(agent))
        override fun getById(id: String): Flow<Agent?> =
            MutableStateFlow(agent.takeIf { it.id == id })
        override fun getDefaultAgent(): Flow<Agent?> = MutableStateFlow(agent)
        override suspend fun insert(agent: Agent) = Unit
        override suspend fun update(agent: Agent) = Unit
        override suspend fun clone(id: String): Agent? = null
        override suspend fun delete(id: String) = Unit
    }

    private inner class FakeCurrentAgent : CurrentAgentRepository {
        override val currentAgentId: Flow<String?> = MutableStateFlow(agent.id)
        override val currentAgent: Flow<Agent?> = MutableStateFlow(agent)
        override suspend fun setCurrentAgentId(agentId: String?) = Unit
    }

    /** The folder page never lists messages; every query is empty. */
    private object NoMessages : MessageRepository {
        override fun getByConversationId(conversationId: String): Flow<List<Message>> =
            MutableStateFlow(emptyList())
        override suspend fun getByConversationIds(conversationIds: List<String>): List<Message> = emptyList()
        override suspend fun insert(message: Message) = Unit
        override suspend fun insertAll(messages: List<Message>) = Unit
        override suspend fun update(message: Message) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun deleteByConversationId(conversationId: String) = Unit
        override suspend fun getConversationIdById(id: String): String? = null
    }

    private fun viewModel(
        projectId: String?,
        conversations: FakeConversations
    ) = ProjectConversationsViewModel(
        initialProjectId = projectId,
        initialAgentId = null,
        projectRepository = FakeProjects(listOf(Project("p1", "Messenger", "/w", 1, 1))),
        conversationRepository = conversations,
        agentRepository = FakeAgents(),
        messageRepository = NoMessages,
        modelRepository = FakeModelRepository(model),
        currentAgentRepository = FakeCurrentAgent()
    )

    /**
     * "Tap a project but the right pane keeps the No-project folder" regression.
     * The pane reuses one ViewModel (the default key is only the class name), so
     * the folder list must follow [ProjectConversationsViewModel.loadFolder].
     */
    @Test
    fun openingAProjectListsItsChatsInsteadOfTheUnprojectedOnes() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val conversations = FakeConversations(listOf(plainChat, projectChat))
            val viewModel = viewModel(projectId = null, conversations = conversations)

            // The right pane starts on the "No project" folder …
            assertEquals(listOf("plain"), viewModel.conversations.first().map { it.id })

            // … then the user taps the project row in the left pane.
            viewModel.loadFolder("p1", agentId = null)

            assertEquals(listOf("project-chat"), viewModel.conversations.first { it.isNotEmpty() }.map { it.id })
            assertEquals("Messenger", viewModel.project.first { it != null }?.name)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * A chat created on the project page must belong to that project — even
     * when the ViewModel was first created for the "No project" folder.
     */
    @Test
    fun newChatInsideAProjectIsCreatedInThatProject() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val conversations = FakeConversations(listOf(plainChat))
            val viewModel = viewModel(projectId = null, conversations = conversations)

            viewModel.loadFolder("p1", agentId = null)

            val created = CompletableDeferred<String>()
            viewModel.createNewConversation { created.complete(it) }
            val createdId = withTimeout(5_000) { created.await() }

            val row = conversations.rows.value.first { it.id == createdId }
            assertEquals("p1", row.projectId, "a chat created inside a project must belong to it")
        } finally {
            Dispatchers.resetMain()
        }
    }
}
