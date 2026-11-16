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

package cc.ptoe.messenger.di

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.data.cloud.BUILTIN_PROVIDER_ID
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.repository.RustAgentRepository
import cc.ptoe.messenger.data.repository.RustConversationRepository
import cc.ptoe.messenger.data.repository.RustProjectRepository

import cc.ptoe.messenger.data.repository.RustCurrentAgentRepository
import cc.ptoe.messenger.data.repository.RustMessageRepository
import cc.ptoe.messenger.data.repository.RustModelRepository
import cc.ptoe.messenger.data.repository.RustProviderRepository
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.ioDispatcher
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.CloudFacade
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.CurrentAgentRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.LearningRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import cc.ptoe.messenger.domain.repository.ProjectRepository
import cc.ptoe.messenger.domain.tool.ChatTool
import cc.ptoe.messenger.domain.tool.createBuiltinChatTools
import cc.ptoe.messenger.domain.mcp.McpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import cc.ptoe.messenger.presentation.platform.defaultWorkspaceRoot
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 *
 * Production currently uses the Rust-backed default. A future Kotlin host can
 * instead supply one complete [AppBackend] backed by a single database owner.
 */
class AppContainer(
    val appDirs: AppDirs,
    val chatImageStore: ChatImageStore,
    private val backend: AppBackend? = null,
) {

    val localDataMutex = Mutex()

    /** Per-platform stores: the cloud facade plus the preferences DataStore. */
    val stores: LocalStores = backend?.stores ?: createLocalStores(appDirs)

    val appPreferences = stores.appPreferences
    val themePreferences = stores.themePreferences

    val cloud: CloudFacade = stores.cloud
    /** Trusted UI uses this only when the complete backend owns its sandbox lifecycle. */
    val artifactUiHost = backend?.artifactUiHost

    init {
        // Some existing consumers still obtain the bridge through the registry. Refuse a
        // mixed backend until those consumers have moved to constructor injection as well.
        require(backend == null || CoreBridgeRegistry.bridge === backend.coreBridge) {
            "Install the backend bridge in CoreBridgeRegistry before constructing AppContainer"
        }
    }

    /**
     * 平台内置工具工厂。参数是所属项目的 workspace 绝对路径（无项目时
     * 传 null，此时不注册终端与工作区工具）。
     * Re-evaluated on access: Android's tool set depends on whether the
     * companion runtime app is installed (may change during a session).
     */
    val builtinTools: (String?) -> List<ChatTool> = { createBuiltinChatTools(it) }

    val mcpManager = McpManager(appPreferences)

    /**
     * Returns active tools: built-in tools for [workspaceRoot] + active MCP
     * tools. 每工具的启用与否由各 Agent 的 toolsConfig 决定
     * （见 [Agent.effectiveToolEnabled]）。
     */
    fun availableTools(workspaceRoot: String?): List<ChatTool> =
        builtinTools(workspaceRoot) + mcpManager.activeTools.value

    /**
     * The settings inventory (Core descriptors + built-in + active MCP), used by
     * Agent tool-config and conversation-override screens. Core descriptors
     * never enter availableTools or the platform execution catalog. These screens are about
     * WHICH tools can be toggled, not about one conversation's workspace, so
     * workspace-bound tools are listed independently of the current chat.
     */
    fun allTools(): List<ChatTool> =
        (coreToolCatalog.state.value.tools + createBuiltinChatTools(defaultWorkspaceRoot()) +
            mcpManager.activeTools.value).distinctBy { it.name }

    /**
     * The bridge belongs to the same backend as the repositories. Production
     * currently installs the Rust bridge before constructing this container.
     */
    val coreBridge: CoreBridge = backend?.coreBridge ?: requireNotNull(CoreBridgeRegistry.bridge) {
        "Install CoreBridgeRegistry.bridge before constructing AppContainer"
    }

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

    val coreToolCatalog = cc.ptoe.messenger.data.repository.RustCoreToolCatalog {
        coreBridge.learningRequest("GET", "/tools/core")
    }

    val learningRepository: LearningRepository = backend?.learning ?: cc.ptoe.messenger.data.repository.RustLearningRepository(coreBridge)
    val learningRevision = kotlinx.coroutines.flow.MutableStateFlow(0L).also { revision ->
        coreBridge.subscribe { kind, _ -> if (kind == "Other") revision.value += 1 }
    }

    val providerRepository: ProviderRepository = backend?.providers ?: RustProviderRepository(coreBridge) { id, deleted ->
        providerChanged(id, deleted)
    }

    val modelRepository: ModelRepository = backend?.models ?: RustModelRepository(coreBridge) { providerId, _ ->
        modelChanged(providerId)
    }

    val agentRepository: AgentRepository = backend?.agents ?: RustAgentRepository(
        coreBridge = coreBridge,
        onChanged = { previous, current -> agentChanged(previous, current) },
        avatarDirectory = appDirs.filesDir.resolve("agent_avatars")
    )

    val conversationRepository: ConversationRepository =
        backend?.conversations ?: RustConversationRepository(coreBridge) { id, deleted ->
            cloud.requestLocalChange("conversation", id, deleted)
        }

    val projectRepository: ProjectRepository = backend?.projects ?: RustProjectRepository(coreBridge) { id, deleted ->
        cloud.requestLocalChange("project", id, deleted)
    }

    val messageRepository: MessageRepository =
        backend?.messages ?: RustMessageRepository(coreBridge, chatImageStore) { conversationId ->
            cloud.requestLocalChange("conversation", conversationId)
        }

    val currentAgentRepository: CurrentAgentRepository =
        backend?.currentAgent ?: RustCurrentAgentRepository(coreBridge, agentRepository)

    private val applicationScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val shutdownMutex = Mutex()
    private var shutDown = false

    init {
        applicationScope.launch {
            // StateFlow's first value loads once at startup. Core's Other changes
            // include capability settings, so subsequent changes refresh readiness.
            learningRevision.collect { coreToolCatalog.refresh() }
        }
    }

    fun refreshCoreToolCatalog() {
        applicationScope.launch { coreToolCatalog.refresh() }
    }

    /** The host first closes UI entry points, then calls this before closing the Room owner. */
    suspend fun shutdown() = withContext(NonCancellable) {
        shutdownMutex.withLock {
            if (shutDown) return@withLock
            applicationScope.coroutineContext[Job]?.cancelAndJoin()
            mcpManager.shutdown()
            backend?.shutdown?.invoke()
            if (backend != null && CoreBridgeRegistry.bridge === backend.coreBridge) CoreBridgeRegistry.bridge = null
            shutDown = true
        }
    }

    /** Kick off initial cloud refresh / default-Agent seeding (was MessengerApplication). */
    fun initializeLocalAndCloudData() {
        applicationScope.launch {
            // 会话存在 Rust core 的 kv 里（cloud_session），DataStore 的
            // appPreferences.cloudSession 只在已删除的 Ktor 客户端里写过，
            // 拿它当门禁会让老会话永远不刷新用户快照（额度停在登录时的值）。
            if (cloud.user.first() != null) {
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
            if (backend == null) clearLocalData(appDirs, stores) else backend.clearLocalData()
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
        // 已登录时默认 Agent 由服务端在同一事务里种下，本地再插一条会被推上云，
        // 破坏「全局恰好一个 isDefault」的不变量。
        if (cloud.user.first() != null) return
        val agents = agentRepository.getAll().first()
        val existingDefault = agents.firstOrNull { it.isDefault }
        if (existingDefault == null) {
            // 没有默认 Agent，则创建一个
            val now = currentTimeMillis()
            val defaultAgent = Agent(
                id = randomUuid(),
                name = Agent.DEFAULT_AGENT_NAME,
                systemPrompt = Agent.DEFAULT_AGENT_PROMPT,
                description = Agent.DEFAULT_AGENT_DESCRIPTION,
                defaultModelId = null,
                temperature = 0.7f,
                topP = 1.0f,
                maxTokens = null,
                isDefault = true,
                toolsEnabled = true,
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
