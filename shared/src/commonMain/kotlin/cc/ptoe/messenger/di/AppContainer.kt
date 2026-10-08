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

package cc.ptoe.messenger.di

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.data.cloud.BUILTIN_PROVIDER_ID
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.repository.ApiRepositoryImpl
import cc.ptoe.messenger.data.repository.CurrentAgentRepositoryImpl
import cc.ptoe.messenger.data.repository.createModelsDevRepository
import cc.ptoe.messenger.data.repository.RustAgentRepository
import cc.ptoe.messenger.data.repository.RustConversationRepository
import cc.ptoe.messenger.data.repository.RustCurrentAgentRepository
import cc.ptoe.messenger.data.repository.RustMessageRepository
import cc.ptoe.messenger.data.repository.RustModelRepository
import cc.ptoe.messenger.data.repository.RustProviderRepository
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.ioDispatcher
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ApiRepository
import cc.ptoe.messenger.domain.repository.CloudFacade
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.CurrentAgentRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ModelsDevRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import cc.ptoe.messenger.domain.usecase.ConversationTitleGenerator
import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.domain.tool.createBuiltinChatTools
import cc.ptoe.messenger.domain.mcp.McpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path

/**
 * Platform storage roots supplied by each app entry point.
 * - Android: `context.filesDir` / `context.cacheDir`
 * - Desktop: `~/.messenger/files` / `~/.messenger/cache`
 */
class AppDirs(
    val filesDir: Path,
    val cacheDir: Path,
)

/**
 * Manual service locator shared by every platform entry point
 * (Android `MessengerApplication`, Desktop `main`, the web bundle).
 * Room and the on-disk DataStore are reached through the per-target
 * [createLocalStores]/[createRoomRepositories] factories, so this class
 * itself compiles for every target including wasmJs.
 */
class AppContainer(
    val appDirs: AppDirs,
    val chatImageStore: ChatImageStore,
) {

    val localDataMutex = Mutex()

    /** Per-platform stores: Room+DataStore on Android/Desktop, Rust+OPFS on web. */
    val stores: LocalStores = createLocalStores(appDirs)

    val appPreferences = stores.appPreferences
    val themePreferences = stores.themePreferences

    val cloud: CloudFacade = stores.cloud

    /** Platform built-in tools: desktop and Android both register the terminal tool. */
    // Re-evaluated on access: Android's tool set depends on whether the
    // companion runtime app is installed (may change during a session).
    val builtinTools: List<ChatTool> get() = createBuiltinChatTools()

    val mcpManager = McpManager(appPreferences)

    /**
     * Returns active tools: built-in tools + active MCP tools.
     * 每工具的启用与否由各 Agent 的 toolsConfig 决定（见 [Agent.effectiveToolEnabled]）。
     */
    val availableTools: List<ChatTool> get() = builtinTools + mcpManager.activeTools.value

    val coreBridge: CoreBridge? = CoreBridgeRegistry.bridge

    private fun providerChanged(id: String, deleted: Boolean) {
        // 内置云 AI 服务商不参与云同步（各设备本地自建）。
        if (id != BUILTIN_PROVIDER_ID) cloud.requestLocalChange("provider", id, deleted)
    }

    private fun modelChanged(providerId: String) {
        if (providerId != BUILTIN_PROVIDER_ID) cloud.requestLocalChange("provider", providerId)
    }

    private fun agentChanged(previous: Agent?, current: Agent?) {
        cloud.requestAgentAvatarChange(previous, current)
        // 内置标题智能体不参与云同步（各设备本地自建）。
        val builtinInvolved = current?.id == Agent.BUILTIN_TITLE_AGENT_ID ||
            previous?.id == Agent.BUILTIN_TITLE_AGENT_ID
        if (!builtinInvolved) {
            current?.let { cloud.requestLocalChange("agent", it.id) }
                ?: previous?.let { cloud.requestLocalChange("agent", it.id, deleted = true) }
        }
    }

    private val hooks = LocalChangeHooks(
        onProviderChanged = ::providerChanged,
        onModelChanged = ::modelChanged,
        onAgentChanged = ::agentChanged,
        onConversationChanged = { id, deleted -> cloud.requestLocalChange("conversation", id, deleted) },
        onMessagesChanged = { conversationId -> cloud.requestLocalChange("conversation", conversationId) },
    )

    /** Non-null only on Android/Desktop (web always runs on the Rust core). */
    private val roomRepositories: RoomRepositories? =
        if (coreBridge == null) createRoomRepositories(stores, chatImageStore, hooks) else null

    val providerRepository: ProviderRepository = coreBridge?.let {
        RustProviderRepository(it) { id, deleted -> providerChanged(id, deleted) }
    } ?: roomRepositories!!.providers

    val modelRepository: ModelRepository = coreBridge?.let {
        RustModelRepository(it) { providerId, _ -> modelChanged(providerId) }
    } ?: roomRepositories!!.models

    val agentRepository: AgentRepository = coreBridge?.let {
        RustAgentRepository(
            coreBridge = it,
            onChanged = { previous, current -> agentChanged(previous, current) },
            avatarDirectory = appDirs.filesDir.resolve("agent_avatars")
        )
    } ?: roomRepositories!!.agents

    val conversationRepository: ConversationRepository = coreBridge?.let {
        RustConversationRepository(it) { id, deleted ->
            cloud.requestLocalChange("conversation", id, deleted)
        }
    } ?: roomRepositories!!.conversations

    val messageRepository: MessageRepository = coreBridge?.let {
        RustMessageRepository(it, chatImageStore) { conversationId ->
            cloud.requestLocalChange("conversation", conversationId)
        }
    } ?: roomRepositories!!.messages

    val modelsDevRepository: ModelsDevRepository = createModelsDevRepository(appDirs.filesDir)

    val apiRepository: ApiRepository = ApiRepositoryImpl(modelsDevRepository)

    val currentAgentRepository: CurrentAgentRepository = coreBridge?.let {
        RustCurrentAgentRepository(it, agentRepository)
    } ?: CurrentAgentRepositoryImpl(appPreferences, agentRepository)

    private val applicationScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** 首轮回复完成后的 LLM 会话标题生成（手机聊天流与 Wear 代处理流共用）。 */
    val conversationTitleGenerator: ConversationTitleGenerator = ConversationTitleGenerator(
        agentRepository = agentRepository,
        conversationRepository = conversationRepository,
        messageRepository = messageRepository,
        modelRepository = modelRepository,
        providerRepository = providerRepository,
        apiRepository = apiRepository,
        externalScope = applicationScope
    )

    /** Kick off initial cloud refresh / default-Agent seeding (was MessengerApplication). */
    fun initializeLocalAndCloudData() {
        applicationScope.launch {
            if (appPreferences.cloudSession.first() != null) {
                runCatching {
                    cloud.refreshUser()
                    cloud.sync()
                    cloud.requestLocalSync()
                }
            }
            ensureBuiltinTitleAgent()
            createDefaultAgentIfNeeded()
        }
    }

    suspend fun clearAllDataAndReinit() {
        localDataMutex.withLock {
            clearLocalData(appDirs, stores)
            createDefaultAgentIfNeededLocked()
        }
    }

    /**
     * 内置标题生成智能体的幂等种子（仅缺行时插入，不覆盖用户编辑）。
     * 委托给 [CloudFacade.ensureBuiltinTitleAgent]（fullSync 补种共用同一实现）。
     */
    suspend fun ensureBuiltinTitleAgent() {
        localDataMutex.withLock {
            cloud.ensureBuiltinTitleAgent()
        }
    }

    private suspend fun createDefaultAgentIfNeeded() {
        localDataMutex.withLock {
            createDefaultAgentIfNeededLocked()
        }
    }

    private suspend fun createDefaultAgentIfNeededLocked() {
        if (appPreferences.cloudSession.first() != null) return
        val agents = agentRepository.getAll().first()
        val existingDefault = agents.firstOrNull { it.isDefault }
        if (existingDefault == null) {
            // 没有默认 Agent，则创建一个
            val now = currentTimeMillis()
            val defaultAgent = Agent(
                id = randomUuid(),
                name = "默认 Agent",
                systemPrompt = "You are a helpful assistant.",
                defaultModelId = null,
                temperature = 0.7f,
                topP = 1.0f,
                maxTokens = null,
                isDefault = true,
                createdAt = now,
                updatedAt = now
            )
            agentRepository.insert(defaultAgent)
            // 仅在当前没有选中任何 Agent 时切到默认 Agent，避免覆盖用户选择
            val currentId = appPreferences.currentAgentId.first()
            if (currentId == null) {
                currentAgentRepository.setCurrentAgentId(defaultAgent.id)
            }
        }
        appPreferences.setDefaultAgentInitialized(true)
    }
}

/**
 * Global access point, replacing `MessengerApplication.instance`.
 * Initialized once by each platform entry point before any UI runs.
 */
object AppContainerHolder {
    lateinit var instance: AppContainer
        private set

    fun initialize(container: AppContainer) {
        instance = container
    }
}
