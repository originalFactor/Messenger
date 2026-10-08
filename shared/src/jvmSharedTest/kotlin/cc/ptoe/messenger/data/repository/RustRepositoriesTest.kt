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
import cc.ptoe.messenger.core.StoredAgentDto
import cc.ptoe.messenger.core.StoredConversationDto
import cc.ptoe.messenger.core.StoredMessageDto
import cc.ptoe.messenger.core.StoredModelDto
import cc.ptoe.messenger.core.StoredProviderDto
import cc.ptoe.messenger.core.StoredProjectDto
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.domain.model.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RustRepositoriesTest {

    private class FakeCoreBridge : CoreBridge {
        val providers = mutableMapOf<String, StoredProviderDto>()
        val models = mutableMapOf<String, StoredModelDto>()
        val agents = mutableMapOf<String, StoredAgentDto>()
        val conversations = mutableMapOf<String, StoredConversationDto>()
        val messages = mutableMapOf<String, StoredMessageDto>()
        val kv = mutableMapOf<String, String>()
        val listeners = mutableListOf<(String, List<String>) -> Unit>()

        override fun subscribe(listener: (kind: String, ids: List<String>) -> Unit) {
            listeners.add(listener)
        }

        private fun notify(kind: String, id: String) {
            listeners.forEach { it(kind, listOf(id)) }
        }

        // Provider
        override fun listProvidersJson(): String = NetworkClient.json.encodeToString(providers.values.toList())
        override fun getProviderJson(id: String): String? = providers[id]?.let { NetworkClient.json.encodeToString(it) }
        override fun upsertProviderJson(json: String) {
            val dto = NetworkClient.json.decodeFromString<StoredProviderDto>(json)
            providers[dto.id] = dto
            notify("Provider", dto.id)
        }
        override fun deleteProvider(id: String) {
            providers.remove(id)
            notify("Provider", id)
        }

        // Model
        override fun listModelsJson(): String = NetworkClient.json.encodeToString(models.values.toList())
        override fun listModelsByProviderJson(providerId: String): String =
            NetworkClient.json.encodeToString(models.values.filter { it.providerId == providerId })
        override fun getModelJson(id: String): String? = models[id]?.let { NetworkClient.json.encodeToString(it) }
        override fun upsertModelJson(json: String) {
            val dto = NetworkClient.json.decodeFromString<StoredModelDto>(json)
            models[dto.id] = dto
            notify("Model", dto.id)
        }
        override fun setModelEnabled(id: String, enabled: Boolean) {
            models[id]?.let {
                models[id] = it.copy(isEnabled = enabled)
                notify("Model", id)
            }
        }
        override fun deleteModel(id: String) {
            models.remove(id)
            notify("Model", id)
        }

        // Agent
        override fun listAgentsJson(): String = NetworkClient.json.encodeToString(agents.values.toList())
        override fun getAgentJson(id: String): String? = agents[id]?.let { NetworkClient.json.encodeToString(it) }
        override fun getDefaultAgentJson(): String? = agents.values.firstOrNull { it.isDefault }?.let { NetworkClient.json.encodeToString(it) }
        override fun getTitleAgentJson(): String? = agents.values.firstOrNull { it.role == "title" }?.let { NetworkClient.json.encodeToString(it) }
        override fun upsertAgentJson(json: String) {
            val dto = NetworkClient.json.decodeFromString<StoredAgentDto>(json)
            agents[dto.id] = dto
            notify("Agent", dto.id)
        }
        override fun deleteAgent(id: String) {
            agents.remove(id)
            notify("Agent", id)
        }

        // Project
        val projects = mutableMapOf<String, StoredProjectDto>()
        override fun listProjectsJson(): String = NetworkClient.json.encodeToString(projects.values.toList())
        override fun getProjectJson(id: String): String? = projects[id]?.let { NetworkClient.json.encodeToString(it) }
        override fun upsertProjectJson(json: String) {
            val dto = NetworkClient.json.decodeFromString<StoredProjectDto>(json)
            projects[dto.id] = dto
            notify("Project", dto.id)
        }
        override fun deleteProject(id: String) {
            // Matches the store's ON DELETE SET NULL: the conversations stay.
            conversations.values.filter { it.projectId == id }.forEach { conversations[it.id] = it.copy(projectId = null) }
            projects.remove(id)
            notify("Project", id)
        }
        override fun listConversationsByProjectJson(projectId: String): String =
            NetworkClient.json.encodeToString(conversations.values.filter { it.projectId == projectId })

        // Conversation
        override fun listConversationsJson(): String = NetworkClient.json.encodeToString(conversations.values.toList())
        override fun listConversationsByAgentJson(agentId: String): String =
            NetworkClient.json.encodeToString(conversations.values.filter { it.agentId == agentId })
        override fun getConversationJson(id: String): String? = conversations[id]?.let { NetworkClient.json.encodeToString(it) }
        override fun upsertConversationJson(json: String) {
            val dto = NetworkClient.json.decodeFromString<StoredConversationDto>(json)
            conversations[dto.id] = dto
            notify("Conversation", dto.id)
        }
        override fun updateConversationLastMessage(id: String, lastMessage: String?, updatedAt: Long) {
            conversations[id]?.let {
                conversations[id] = it.copy(lastMessage = lastMessage, updatedAt = updatedAt)
                notify("Conversation", id)
            }
        }
        override fun deleteConversation(id: String) {
            conversations.remove(id)
            notify("Conversation", id)
        }

        // Message
        override fun listMessagesByConversationJson(conversationId: String): String =
            NetworkClient.json.encodeToString(messages.values.filter { it.conversationId == conversationId })
        override fun getMessageJson(id: String): String? = messages[id]?.let { NetworkClient.json.encodeToString(it) }
        override fun upsertMessageJson(json: String) {
            val dto = NetworkClient.json.decodeFromString<StoredMessageDto>(json)
            messages[dto.id] = dto
            notify("Message", dto.id)
        }
        override fun deleteMessage(id: String) {
            messages.remove(id)
            notify("Message", id)
        }
        override fun deleteMessagesByConversation(conversationId: String) {
            messages.entries.removeAll { it.value.conversationId == conversationId }
            notify("Message", conversationId)
        }

        // KV
        override fun kvGet(key: String): String? = kv[key]
        override fun kvSet(key: String, value: String) {
            kv[key] = value
            notify("Kv", key)
        }
        override fun kvDelete(key: String) {
            kv.remove(key)
            notify("Kv", key)
        }

        // Stubs for Cloud/Turn
        override suspend fun cloudLogin(email: String, password: String): String = "{}"
        override suspend fun cloudRegister(email: String, password: String): String = "{}"
        override suspend fun cloudLogout() {}
        override suspend fun cloudRefreshUser(): String = "{}"
        override fun cloudCurrentUserJson(): String? = null
        override fun cloudSetServerUrl(url: String) {}
        override fun cloudGetServerUrl(): String = "https://example.com"
        override fun cloudHasLocalData(): Boolean = false
        override fun cloudMarkChange(kind: String, id: String, deleted: Boolean) {}
        override suspend fun cloudSync(replaceLocal: Boolean): String = "{}"
        override suspend fun cloudPushPending(): String = "{}"
        override suspend fun cloudPreviewCard(code: String): String = "{}"
        override suspend fun cloudRedeemCard(code: String): String = "{}"
        override suspend fun cloudSyncBuiltinModels(force: Boolean): Long = 0
        override suspend fun cloudListMarketAgents(query: String, cursor: String?): String = "{}"
        override suspend fun cloudGetMarketAgent(id: String): String = "{}"
        override suspend fun cloudPublishMarketAgent(agentId: String): String = "{}"
        override suspend fun cloudImportMarketAgent(marketId: String): String = "{}"
        override suspend fun cloudCacheAvatar(scope: String, accountId: String, id: String, url: String, version: String?, destDir: String): String = ""
        // Cloud account/market/avatar surface added for the web facade; the
        // repository tests never exercise it.
        override suspend fun cloudPushSnapshot(): Long = 0L
        override suspend fun cloudSyncWith(
            since: Long?,
            replaceLocal: Boolean,
            expectedServerVersion: Long?
        ): String = "{}"
        override suspend fun cloudReplaceCloudWithLocal(): String = "{}"
        override suspend fun cloudChangePassword(currentPassword: String, newPassword: String) {}
        override suspend fun cloudDeleteAccount(currentPassword: String) {}
        override suspend fun cloudEnsureBuiltinTitleAgent() {}
        override suspend fun cloudUploadUserAvatar(bytes: ByteArray, filename: String, mime: String): String = ""
        override suspend fun cloudDeleteUserAvatar(): String = ""
        override suspend fun cloudUploadAgentAvatar(
            agentId: String,
            bytes: ByteArray,
            filename: String,
            mime: String
        ): String = ""
        override suspend fun cloudDeleteAgentAvatar(agentId: String): String = ""
        override suspend fun cloudPushMarketAgentUpdate(agentId: String): String = "{}"
        override suspend fun cloudRemoveMarketAgent(agentId: String) {}
        override suspend fun cloudCheckMarketAgentUpdate(agentId: String): String = "{}"
        override suspend fun cloudApplyMarketAgentUpdate(agentId: String, marketJson: String): String = "{}"
        override suspend fun cloudImportMarketAgentWithAvatar(marketId: String): String = "{}"
        override suspend fun cloudClearMarketLinksAndBuiltinProvider() {}
        override fun clearLocalDataForReinit() {}

        override fun cancelTurn() {}
        override suspend fun runTurn(config: TurnConfigBridge, toolExecutor: (String, String) -> Pair<String, Boolean>, onEventJson: (String) -> Unit) {}
    }

    @Test
    fun testProviderRepositoryCrud() = runBlocking {
        val bridge = FakeCoreBridge()
        val repo = RustProviderRepository(bridge)

        val provider = Provider(
            id = "p1",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "sk-test",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        repo.insert(provider)

        val list = repo.getAll().first()
        assertEquals(1, list.size)
        assertEquals("OpenAI", list[0].name)

        val fetched = repo.getById("p1").first()
        assertNotNull(fetched)
        assertEquals("sk-test", fetched.apiKey)

        repo.update(provider.copy(name = "OpenAI Updated"))
        assertEquals("OpenAI Updated", repo.getById("p1").first()?.name)

        repo.delete("p1")
        assertTrue(repo.getAll().first().isEmpty())
    }

    @Test
    fun testModelRepositoryCrud() = runBlocking {
        val bridge = FakeCoreBridge()
        val repo = RustModelRepository(bridge)

        val model = ChatModel(
            id = "m1",
            providerId = "p1",
            modelId = "gpt-4o",
            displayName = "GPT-4o",
            isEnabled = true,
            createdAt = 1000L
        )
        repo.insert(model)

        val all = repo.getAll().first()
        assertEquals(1, all.size)
        assertEquals("GPT-4o", all[0].displayName)

        val byProvider = repo.getByProviderId("p1").first()
        assertEquals(1, byProvider.size)

        repo.setEnabled("m1", false)
        assertTrue(repo.getEnabledByProviderId("p1").first().isEmpty())
        assertEquals(false, repo.getById("m1").first()?.isEnabled)

        repo.delete("m1")
        assertTrue(repo.getAll().first().isEmpty())
    }

    @Test
    fun testAgentRepositoryCrud() = runBlocking {
        val bridge = FakeCoreBridge()
        val repo = RustAgentRepository(bridge)

        val agent = Agent(
            id = "a1",
            name = "Default Assistant",
            systemPrompt = "You are helpful.",
            defaultModelId = "m1",
            temperature = 0.7f,
            topP = 1.0f,
            maxTokens = null,
            isDefault = true,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        repo.insert(agent)

        val defaultAgent = repo.getDefaultAgent().first()
        assertNotNull(defaultAgent)
        assertEquals("Default Assistant", defaultAgent.name)

        val cloned = repo.clone("a1")
        assertNotNull(cloned)
        assertTrue(cloned.name.contains("Copy"))
        assertEquals(false, cloned.isDefault)

        assertEquals(2, repo.getAll().first().size)
    }

    @Test
    fun testConversationAndMessageRepositoryCrud() = runBlocking {
        val bridge = FakeCoreBridge()
        val convRepo = RustConversationRepository(bridge)
        val msgRepo = RustMessageRepository(bridge)

        val conversation = Conversation(
            id = "c1",
            title = "My Chat",
            providerId = "p1",
            agentId = "a1",
            createdAt = 1000L,
            updatedAt = 1000L,
            lastMessage = null
        )
        convRepo.insert(conversation)
        assertEquals(1, convRepo.getAll().first().size)

        val message = Message(
            id = "msg1",
            conversationId = "c1",
            role = MessageRole.USER,
            content = "Hello Rust Core",
            timestamp = 1050L,
            status = MessageStatus.SENT
        )
        msgRepo.insert(message)
        convRepo.updateLastMessage("c1", "Hello Rust Core", 1050L)

        val messages = msgRepo.getByConversationId("c1").first()
        assertEquals(1, messages.size)
        assertEquals("Hello Rust Core", messages[0].content)

        val conv = convRepo.getById("c1").first()
        assertNotNull(conv)
        assertEquals("Hello Rust Core", conv.lastMessage)

        convRepo.delete("c1")
        assertTrue(convRepo.getAll().first().isEmpty())
        assertTrue(msgRepo.getByConversationId("c1").first().isEmpty())
    }

    @Test
    fun testCurrentAgentRepository() = runBlocking {
        val bridge = FakeCoreBridge()
        val agentRepo = RustAgentRepository(bridge)
        val currentRepo = RustCurrentAgentRepository(bridge, agentRepo)

        val agent = Agent(
            id = "a1",
            name = "Default",
            systemPrompt = "sys",
            defaultModelId = null,
            temperature = 0.7f,
            topP = 1.0f,
            maxTokens = null,
            isDefault = true,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        agentRepo.insert(agent)

        // Without current agent set, falls back to default agent
        assertEquals("Default", currentRepo.currentAgent.first()?.name)

        currentRepo.setCurrentAgentId("a1")
        assertEquals("a1", currentRepo.currentAgentId.first())

        currentRepo.setCurrentAgentId(null)
        assertNull(currentRepo.currentAgentId.first())
    }

    /**
     * A project groups conversations, and deleting it leaves them behind as
     * plain conversations — that is what makes "no project ⇒ no workspace
     * tools" reachable without losing chat history.
     */
    @Test
    fun testProjectRepositoryGroupsConversationsAndDeleteUnassigns() = runBlocking {
        val bridge = FakeCoreBridge()
        val projectRepo = RustProjectRepository(bridge)
        val conversationRepo = RustConversationRepository(bridge)

        projectRepo.insert(Project("proj1", "Messenger", "/w/messenger", 1, 1))
        val conversation = Conversation(
            id = "c1",
            title = "Refactor",
            providerId = "p1",
            agentId = "a1",
            projectId = "proj1",
            createdAt = 1,
            updatedAt = 1,
            lastMessage = null
        )
        conversationRepo.insert(conversation)

        assertEquals("/w/messenger", projectRepo.getById("proj1").first()?.workspace)
        assertEquals(listOf("c1"), conversationRepo.getByProjectId("proj1").first().map { it.id })

        projectRepo.delete("proj1")

        assertTrue(projectRepo.getAll().first().isEmpty())
        val orphan = conversationRepo.getById("c1").first()
        assertNotNull(orphan)
        assertNull(orphan!!.projectId, "the conversation survives as a plain chat")
    }

    /** The workspace folder name is derived from the project name. */
    @Test
    fun testProjectWorkspaceFolderNameIsNormalized() {
        assertEquals("messenger", Project("p", "Messenger", "", 1, 1).workspaceFolderName)
        assertEquals("messenger-ui", Project("p", "  Messenger   UI ", "", 1, 1).workspaceFolderName)
        assertEquals("a-b", Project("p", "a//b", "", 1, 1).workspaceFolderName)
        assertEquals("project", Project("p", "///", "", 1, 1).workspaceFolderName)
    }
}
