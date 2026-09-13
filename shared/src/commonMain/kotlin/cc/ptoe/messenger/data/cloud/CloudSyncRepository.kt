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

package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.MessengerDatabase
import cc.ptoe.messenger.data.local.entity.AgentEntity
import cc.ptoe.messenger.data.local.entity.ConversationEntity
import cc.ptoe.messenger.data.local.entity.MessageEntity
import cc.ptoe.messenger.data.local.entity.ModelEntity
import cc.ptoe.messenger.data.local.entity.ProviderEntity
import cc.ptoe.messenger.data.remote.api.OpenAiClient
import cc.ptoe.messenger.data.util.FileKit
import cc.ptoe.messenger.data.util.logE
import cc.ptoe.messenger.data.util.logI
import cc.ptoe.messenger.data.util.logW
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.model.Agent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.encodeUtf8
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.Duration.Companion.milliseconds

const val DEFAULT_CLOUD_SERVER_URL = "https://messenger.ptoe.cc"

/**
 * 内置云 AI 服务商的固定 ID。登录后由客户端以用户级 AI API Key 自动
 * 配置，指向云端的 OpenAI 兼容代理（{server}/v1）。它不参与云同步：
 * 每台设备在登录/同步时自行创建或刷新，推送与拉取两侧都按此 ID 过滤，
 * 避免删除/重建在设备间互相复活。
 */
const val BUILTIN_PROVIDER_ID = "builtin-messenger-cloud-ai"
const val BUILTIN_PROVIDER_NAME = "Messenger Cloud AI"

class CloudSyncRepository(
    private val appPreferences: AppPreferences,
    private val database: MessengerDatabase,
    private val filesDir: Path,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val localDataMutex: Mutex = Mutex()
) {
    @Volatile
    private var localChangesEnabled = false
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val avatarSyncMutex = Mutex()
    private var scheduledSync: Job? = null
    /** 内置云 AI 模型列表的上次成功同步时间（进程内节流，见 syncBuiltinProviderModels）。 */
    @Volatile
    private var lastBuiltinModelsSyncAt = 0L
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()
    private val apiClient = CloudApiClient(appPreferences)

    /** Cookie-aware Ktor client used by Coil to load authenticated avatars. */
    val avatarHttpClient: HttpClient get() = apiClient.download

    val user: Flow<CloudUser?> = appPreferences.cloudUser
        .map { value ->
            if (value.isNullOrBlank()) null
            else runCatching { json.decodeFromString(CloudUser.serializer(), value) }.getOrNull()
        }
        .distinctUntilChanged()

    val serverUrl: Flow<String> = appPreferences.cloudServerUrl
        .map { url -> url ?: DEFAULT_CLOUD_SERVER_URL }
        .distinctUntilChanged()

    suspend fun setServerUrl(url: String) {
        val normalized = normalizeServerUrl(url)
        val previous = serverUrl.first()
        if (previous != normalized) {
            localChangesEnabled = false
            localDataMutex.withLock {
                database.agentDao().clearAllMarketLinks()
                removeBuiltinProvider()
            }
            appPreferences.setCloudSession(null)
            appPreferences.setCloudSessionHost(null)
            appPreferences.setCloudUser(null)
            appPreferences.setCloudAiApiKey(null)
        }
        appPreferences.setCloudServerUrl(normalized.takeUnless { it == DEFAULT_CLOUD_SERVER_URL })
    }

    suspend fun login(email: String, password: String, serverUrl: String? = null): CloudLoginOutcome =
        withContext(Dispatchers.IO) {
            val baseUrl = prepareRequestServer(serverUrl)
            val response = request { apiClient.login(endpoint("api/auth/login", baseUrl), CredentialsRequest(email.trim(), password)) }
            completeAuthentication(response.user, baseUrl)
        }

    suspend fun register(email: String, password: String, serverUrl: String? = null): CloudLoginOutcome =
        withContext(Dispatchers.IO) {
            val baseUrl = prepareRequestServer(serverUrl)
            val response = request { apiClient.register(endpoint("api/auth/register", baseUrl), CredentialsRequest(email.trim(), password)) }
            completeAuthentication(response.user, baseUrl)
        }

    suspend fun refreshUser(): CloudUser {
        return refreshUser(updateLocalAvatar = true)
    }

    private suspend fun refreshUser(updateLocalAvatar: Boolean): CloudUser {
        checkSignedIn()
        val response = request { apiClient.me(endpoint("api/auth/me")) }
        val configuredServerUrl = serverUrl.first()
        logI(
            TAG,
            "Cloud account id=${response.user.id} email=${response.user.email} " +
                "serverSyncVersion=${response.user.syncVersion}"
        )
        saveUser(response.user)
        if (updateLocalAvatar) {
            val currentAvatar = appPreferences.userAvatar.first()
            val localAvatar = if (response.user.avatarUrl != null) {
                currentAvatar
                    ?.takeUnless(::isCloudCachedAvatar)
                    ?.takeIf(::isUsableLocalAvatar)
                    ?: runCatching {
                        cacheRemoteAvatar(
                            "user",
                            response.user.id,
                            response.user.id,
                            response.user.avatarUrl,
                            response.user.avatarVersion?.toString(),
                            configuredServerUrl
                        )
                    }.getOrNull()
                    ?: currentAvatar?.takeUnless(::isRemoteAvatar)?.takeIf(::isUsableLocalAvatar)
            } else {
                currentAvatar?.takeUnless(::isCloudCachedAvatar)?.takeIf(::isUsableLocalAvatar)
            }
            appPreferences.setUserAvatar(localAvatar)
            if (response.user.avatarUrl == null) {
                deleteCachedAvatars("user", response.user.id, response.user.id)
            }
        }
        return response.user
    }

    suspend fun logout() {
        runCatching { request { apiClient.logout(endpoint("api/auth/logout")) } }
        user.first()?.let { appPreferences.clearCloudSyncVersion(it.id) }
        localChangesEnabled = false
        localDataMutex.withLock {
            database.agentDao().clearAllMarketLinks()
            removeBuiltinProvider()
        }
        appPreferences.setCloudSession(null)
        appPreferences.setCloudSessionHost(null)
        appPreferences.setCloudUser(null)
        appPreferences.setCloudAiApiKey(null)
    }

    suspend fun changePassword(currentPassword: String, newPassword: String): Unit = withContext(Dispatchers.IO) {
        checkSignedIn()
        request {
            apiClient.changePassword(
                endpoint("api/auth/password"),
                PasswordChangeRequest(currentPassword, newPassword)
            )
        }
    }

    suspend fun deleteAccount(currentPassword: String): Unit = withContext(Dispatchers.IO) {
        checkSignedIn()
        val accountId = user.first()?.id
        request {
            apiClient.deleteAccount(
                endpoint("api/auth/account"),
                AccountDeleteRequest(currentPassword)
            )
        }
        cancelPendingLocalSync()
        localDataMutex.withLock {
            removeBuiltinProvider()
        }
        appPreferences.clearCloudAccount(accountId)
    }

    /** 兑换前查询卡密信息（套餐快照），不消费卡密。与 web 端 /api/console/cards/preview 一致。 */
    suspend fun previewRedeemCard(code: String): CloudCardPreview = withContext(Dispatchers.IO) {
        checkSignedIn()
        val response = request {
            apiClient.previewRedeemCard(endpoint("api/console/cards/preview"), RedeemCodeRequest(code.trim()))
        }
        response.card
    }

    /**
     * 确认兑换卡密。成功后拉取一次 /api/auth/me 刷新本地用户（额度汇总），
     * 使套餐卡片立即反映新到账的条目。
     */
    suspend fun redeemCard(code: String): CloudRedeemResponse = withContext(Dispatchers.IO) {
        checkSignedIn()
        val response = request {
            apiClient.redeemCard(endpoint("api/console/redeem"), RedeemCodeRequest(code.trim()))
        }
        runCatching { refreshUser(updateLocalAvatar = false) }
            .onFailure { logW(TAG, "Redeem quota refresh failed: ${it.message}") }
        response
    }

    suspend fun listMarketAgents(
        query: String,
        cursor: String? = null
    ): CloudMarketAgentListResponse = withContext(Dispatchers.IO) {
        checkSignedIn()
        request { apiClient.listMarketAgents(endpoint("api/market/agents"), query.trim(), cursor) }
    }

    suspend fun marketAgent(id: String): CloudMarketAgent = withContext(Dispatchers.IO) {
        checkSignedIn()
        request { apiClient.getMarketAgent(endpoint("api/market/agents/$id")) }.agent
    }

    suspend fun publishMarketAgent(agentId: String): CloudMarketAgent = withContext(Dispatchers.IO) {
        localDataMutex.withLock {
            checkSignedIn()
            val agent = requireAgentForMarket(agentId)
            check(!agent.isDefault) { "The default Agent cannot be published." }
            check(agent.marketAgentId == null) { "This Agent is already linked to the market." }
            val response = request {
                apiClient.createMarketAgent(endpoint("api/market/agents"), agent.toMarketRequest())
            }.agent
            val updated = try {
                updateMarketAvatar(response, agent.avatar)
            } catch (error: Throwable) {
                runCatching { request { apiClient.deleteMarketAgent(endpoint("api/market/agents/${response.id}")) } }
                throw error
            }
            persistMarketLink(agent.id, updated.id, updated.version, "publisher")
            updated
        }
    }

    suspend fun pushMarketAgentUpdate(agentId: String): CloudMarketAgent = withContext(Dispatchers.IO) {
        localDataMutex.withLock {
            checkSignedIn()
            val agent = requireAgentForMarket(agentId)
            val marketId = checkNotNull(agent.marketAgentId) { "Publish this Agent first." }
            check(agent.marketAgentRole == "publisher") { "Only the publisher can update this Agent." }
            val response = runCatching {
                request {
                    apiClient.updateMarketAgent(endpoint("api/market/agents/$marketId"), agent.toMarketRequest())
                }.agent
            }.getOrElse { error ->
                if (isMarketNotFound(error)) {
                    _syncError.value = null
                    persistMarketLink(agent.id, null, null, null)
                    throw Exception("Market entry no longer exists; local Agent marked as unpublished.", error)
                } else {
                    throw error
                }
            }
            val updated = updateMarketAvatar(response, agent.avatar)
            persistMarketLink(agent.id, updated.id, updated.version, "publisher")
            updated
        }
    }

    suspend fun removeMarketAgent(agentId: String) = withContext(Dispatchers.IO) {
        localDataMutex.withLock {
            checkSignedIn()
            val agent = requireAgentForMarket(agentId)
            val marketId = checkNotNull(agent.marketAgentId) { "This Agent is not published." }
            check(agent.marketAgentRole == "publisher") { "Only the publisher can remove this Agent." }
            runCatching { request { apiClient.deleteMarketAgent(endpoint("api/market/agents/$marketId")) } }
                .onFailure { error ->
                    if (!isMarketNotFound(error)) throw error
                    _syncError.value = null
                }
            persistMarketLink(agent.id, null, null, null)
        }
    }

    private fun isMarketNotFound(error: Throwable): Boolean =
        (error.cause as? ResponseException)?.response?.status?.value == 404

    suspend fun importMarketAgent(marketId: String): Agent = withContext(Dispatchers.IO) {
        localDataMutex.withLock {
            checkSignedIn()
            val market = marketAgent(marketId)
            val account = checkNotNull(user.first())
            val avatar = market.avatarUrl?.let { url ->
                cacheRemoteAvatar(
                    scope = "market-agent",
                    accountId = account.id,
                    id = market.id,
                    url = url,
                    version = market.avatarVersion?.toString(),
                    baseUrl = serverUrl.first()
                ).let(::copyMarketAvatarToAgentStorage)
            }
            val now = System.currentTimeMillis()
            val imported = AgentEntity(
                id = randomUuid(),
                name = market.name,
                avatar = avatar,
                systemPrompt = market.systemPrompt,
                defaultModelId = null,
                temperature = market.temperature.toFloat(),
                topP = market.topP.toFloat(),
                maxTokens = market.maxTokens,
                reasoningEffort = market.reasoningEffort,
                isDefault = false,
                followDefaultSystemPrompt = false,
                followDefaultModel = false,
                followDefaultTemperature = false,
                followDefaultTopP = false,
                followDefaultMaxTokens = false,
                followDefaultReasoningEffort = false,
                marketAgentId = market.id,
                marketAgentVersion = market.version,
                marketAgentRole = "importer",
                createdAt = now,
                updatedAt = now
            )
            database.agentDao().insert(imported)
            requestLocalChange("agent", imported.id)
            imported.toDomain()
        }
    }

    suspend fun checkMarketAgentUpdate(agentId: String): CloudMarketAgentUpdate = withContext(Dispatchers.IO) {
        checkSignedIn()
        val agent = requireAgentForMarket(agentId)
        val marketId = checkNotNull(agent.marketAgentId) { "This Agent is not linked to the market." }
        check(agent.marketAgentRole == "importer") { "Published Agents do not receive market updates." }
        val market = marketAgent(marketId)
        CloudMarketAgentUpdate(market, market.version > (agent.marketAgentVersion ?: 0L))
    }

    suspend fun applyMarketAgentUpdate(agentId: String, market: CloudMarketAgent): Agent = withContext(Dispatchers.IO) {
        localDataMutex.withLock {
            checkSignedIn()
            val agent = requireAgentForMarket(agentId)
            check(agent.marketAgentId == market.id && agent.marketAgentRole == "importer") {
                "This Agent is not linked to the selected market entry."
            }
            val account = checkNotNull(user.first())
            val avatar = market.avatarUrl?.let { url ->
                cacheRemoteAvatar(
                    scope = "market-agent",
                    accountId = account.id,
                    id = market.id,
                    url = url,
                    version = market.avatarVersion?.toString(),
                    baseUrl = serverUrl.first()
                ).let(::copyMarketAvatarToAgentStorage)
            }
            deleteLocalAvatarIfReplaced(agent.avatar, avatar)
            val updated = agent.copy(
                name = market.name,
                avatar = avatar,
                systemPrompt = market.systemPrompt,
                temperature = market.temperature.toFloat(),
                topP = market.topP.toFloat(),
                maxTokens = market.maxTokens,
                reasoningEffort = market.reasoningEffort,
                followDefaultSystemPrompt = false,
                followDefaultModel = false,
                followDefaultTemperature = false,
                followDefaultTopP = false,
                followDefaultMaxTokens = false,
                followDefaultReasoningEffort = false,
                marketAgentVersion = market.version,
                updatedAt = System.currentTimeMillis()
            )
            database.agentDao().update(updated)
            requestLocalChange("agent", updated.id)
            updated.toDomain()
        }
    }

    /** Pulls all server changes since the account cursor and applies them atomically. */
    suspend fun sync(): CloudSyncResult = withContext(Dispatchers.IO) {
        localDataMutex.withLock {
            checkSignedIn()
            val account = refreshUser()
            syncMutex.withLock {
                syncInternal(expectedServerVersion = account.syncVersion).also { _syncError.value = null }
            }
        }
    }

    /** Rebuilds the local account data from the server's complete entity history. */
    private suspend fun fullSync(): CloudSyncResult = withContext(Dispatchers.IO) {
        localDataMutex.withLock {
            checkSignedIn()
            val account = refreshUser()
            syncMutex.withLock {
                syncInternal(
                    sinceOverride = 0L,
                    replaceLocal = true,
                    expectedServerVersion = account.syncVersion
                ).also { _syncError.value = null }
            }
        }
    }

    /** Pushes the current local snapshot, then pulls the authoritative server delta. */
    suspend fun upload(): CloudSyncResult = withContext(Dispatchers.IO) {
        localDataMutex.withLock {
            checkSignedIn()
            val account = refreshUser()
            syncMutex.withLock {
                pushLocalSnapshot(onlyPending = false)
                syncInternal(expectedServerVersion = account.syncVersion).also { _syncError.value = null }
            }
        }
    }

    suspend fun completeLogin(useLocalData: Boolean): CloudSyncResult {
        return if (useLocalData) replaceCloudWithLocal() else fullSync()
    }

    /** Kept as the settings screen's restore action; incremental sync is non-destructive. */
    suspend fun downloadAndRestore(): CloudSyncResult = fullSync()

    fun requestLocalSync() {
        scheduledSync?.cancel()
        scheduledSync = syncScope.launch {
            delay(750.milliseconds)
            runCatching { pushPendingChanges() }
                .onFailure { _syncError.value = it.message ?: "Cloud synchronization failed" }
        }
    }

    suspend fun cancelPendingLocalSync() {
        syncScope.coroutineContext.cancelChildren()
        syncScope.coroutineContext[Job]?.children?.toList()?.joinAll()
        scheduledSync = null
        syncMutex.withLock {
            localChangesEnabled = false
            _syncError.value = null
        }
    }

    fun requestLocalChange(type: String, id: String, deleted: Boolean = false) {
        syncScope.launch {
            val account = user.first()?.takeIf { localChangesEnabled } ?: return@launch
            if (deleted) {
                appPreferences.addCloudPendingDelete(account.id, type, id)
                appPreferences.removeCloudPendingUpsert(account.id, type, id)
            } else {
                appPreferences.addCloudPendingUpsert(account.id, type, id)
                appPreferences.removeCloudPendingDelete(account.id, type, id)
            }
            requestLocalSync()
        }
    }

    fun requestAgentAvatarChange(previous: Agent?, current: Agent?) {
        if (previous?.avatar == current?.avatar) return
        syncScope.launch {
            if (user.first() == null || !localChangesEnabled) return@launch
            val agentId = current?.id ?: previous?.id ?: return@launch
            runCatching { uploadAgentAvatar(agentId, current?.avatar) }
                .onSuccess { _syncError.value = null }
                .onFailure { error ->
                    logE(TAG, "Agent avatar synchronization failed for agent=$agentId", error)
                    _syncError.value = error.message ?: "Avatar synchronization failed"
                }
        }
    }

    suspend fun uploadUserAvatar(path: String?): CloudAvatarResponse =
        avatarSyncMutex.withLock {
            withContext(Dispatchers.IO) {
                checkSignedIn()
                val currentAvatar = appPreferences.userAvatar.first()
                if (path != null && !isRemoteAvatar(path) && currentAvatar != path) {
                    // A concurrent upload already replaced and cleaned up this local file.
                    return@withContext CloudAvatarResponse(currentAvatar, 0L)
                }
                val localFile = path?.takeUnless(::isRemoteAvatar)
                val response = if (path == null || isRemoteAvatar(path)) {
                    request { apiClient.deleteUserAvatar(endpoint("api/avatars/user")) }
                } else {
                    request {
                        apiClient.uploadUserAvatar(
                            endpoint("api/avatars/user"),
                            avatarName(path),
                            avatarBytes(path),
                            avatarMime(path)
                        )
                    }
                }
                val configuredServerUrl = serverUrl.first()
                val cachedAvatar = response.url?.let { url ->
                    runCatching {
                        cacheRemoteAvatar(
                            "user",
                            user.first()!!.id,
                            user.first()!!.id,
                            url,
                            response.avatarVersion?.toString(),
                            configuredServerUrl
                        )
                    }
                        .getOrNull()
                }
                val localAvatar = cachedAvatar ?: if (response.url != null) path else null
                appPreferences.setUserAvatar(localAvatar)
                refreshCachedUserAvatar(response.url, response.avatarVersion)
                if (response.url == null) {
                    deleteCachedAvatars("user", user.first()!!.id, user.first()!!.id)
                }
                if (localFile != null && localFile != localAvatar) FileKit.delete(localFile)
                response
            }
        }

    suspend fun uploadAgentAvatar(agentId: String, path: String?): CloudAvatarResponse =
        avatarSyncMutex.withLock {
            withContext(Dispatchers.IO) {
                checkSignedIn()
                val currentAvatar = database.agentDao().getById(agentId).first()?.avatar
                if (path != null && !isRemoteAvatar(path) && currentAvatar != path) {
                    // A concurrent upload already replaced and cleaned up this local file.
                    return@withContext CloudAvatarResponse(currentAvatar, 0L)
                }
                val localFile = path?.takeUnless(::isRemoteAvatar)
                val response = if (path == null || isRemoteAvatar(path)) {
                    request { apiClient.deleteAgentAvatar(endpoint("api/avatars/agents/$agentId")) }
                } else {
                    request {
                        apiClient.uploadAgentAvatar(
                            endpoint("api/avatars/agents/$agentId"),
                            avatarName(path),
                            avatarBytes(path),
                            avatarMime(path)
                        )
                    }
                }
                val configuredServerUrl = serverUrl.first()
                val cachedAvatar = response.url?.let { url ->
                    runCatching {
                        cacheRemoteAvatar(
                            "agent",
                            user.first()!!.id,
                            agentId,
                            url,
                            response.avatarVersion?.toString(),
                            configuredServerUrl
                        )
                    }
                        .getOrNull()
                }
                val localAvatar = cachedAvatar ?: if (response.url != null) path else null
                database.agentDao().getById(agentId).first()?.let { agent ->
                    database.agentDao().insert(agent.copy(avatar = localAvatar))
                }
                if (response.url == null) {
                    deleteCachedAvatars("agent", user.first()!!.id, agentId)
                }
                if (localFile != null && localFile != localAvatar) FileKit.delete(localFile)
                response
            }
        }

    private suspend fun completeAuthentication(value: CloudUser, baseUrl: String): CloudLoginOutcome {
        val previousUser = user.first()
        if (previousUser == null || previousUser.id != value.id) {
            appPreferences.clearCloudSyncVersion(value.id)
        }
        appPreferences.setCloudSessionHost(baseUrl)
        saveUser(value)
        return CloudLoginOutcome(
            user = value,
            hasLocalData = localDataMutex.withLock { hasLocalData() },
            cloudVersion = value.syncVersion
        )
    }

    private suspend fun hasLocalData(): Boolean {
        // 内置云 AI 服务商由客户端在登录后自动创建，不算作用户本地数据，
        // 否则每次登录都会被误判为「本地有数据」而弹出同步选择框。
        if (database.providerDao().countExcluding(BUILTIN_PROVIDER_ID) > 0) return true
        if (database.modelDao().getAllEntities().any { it.providerId != BUILTIN_PROVIDER_ID }) return true
        if (database.conversationDao().count() > 0) return true
        if (database.messageDao().count() > 0) return true

        val agents = database.agentDao().getAllEntities()
        if (agents.any { !it.isDefault || !it.isDefaultAgentBaseline() }) return true

        if (appPreferences.userAvatar.first() != null) return true

        return false
    }

    private suspend fun replaceCloudWithLocal(): CloudSyncResult = withContext(Dispatchers.IO) {
        localDataMutex.withLock {
            checkSignedIn()
            val account = refreshUser(updateLocalAvatar = false)
            syncMutex.withLock {
                val remote = fetchCloudSync(since = 0L)
                mergeLocalDefaultWithCloudDefault(remote)
                val localAgents = database.agentDao().getAllEntities()
                val localProviders = database.providerDao().getAllEntities()
                val localConversations = database.conversationDao().getAllEntities()
                val localAgentIds = localAgents.map { it.id }.toSet()
                val localProviderIds = localProviders.map { it.id }.toSet()
                val localConversationIds = localConversations.map { it.id }.toSet()

                remote.agents.filter { !it.deleted && !it.isDefault && it.id !in localAgentIds }
                    .forEach { request { apiClient.deleteAgent(endpoint("api/agents/${it.id}")) } }
                remote.providers.filter { !it.deleted && it.id !in localProviderIds }
                    .forEach { request { apiClient.deleteProvider(endpoint("api/providers/${it.id}")) } }
                remote.conversations.filter { !it.deleted && it.id !in localConversationIds }
                    .forEach { request { apiClient.deleteConversation(endpoint("api/conversations/${it.id}")) } }

                val pushedVersion = pushLocalSnapshot(onlyPending = false)
                val currentAccount = refreshUser(updateLocalAvatar = false)
                val latestVersion = maxOf(account.syncVersion, currentAccount.syncVersion, pushedVersion)
                appPreferences.setCloudSyncVersion(currentAccount.id, latestVersion)
                localChangesEnabled = true
                CloudSyncResult(
                    latestVersion = latestVersion,
                    agents = localAgents.size,
                    conversations = localConversations.size,
                    providers = localProviders.size
                )
            }
        }
    }

    private suspend fun mergeLocalDefaultWithCloudDefault(remote: CloudSyncResponse) {
        val cloudDefault = remote.agents.firstOrNull { it.isDefault && !it.deleted } ?: return
        val localDefault = database.agentDao().getAllEntities().firstOrNull { it.isDefault } ?: return
        if (localDefault.id == cloudDefault.id) return
        database.agentDao().insert(localDefault.copy(id = cloudDefault.id))
        database.conversationDao().updateAgentId(localDefault.id, cloudDefault.id)
        database.agentDao().delete(localDefault.id)
        appPreferences.setCurrentAgentId(cloudDefault.id)
    }

    private suspend fun pushPendingChanges() {
        localDataMutex.withLock {
            val account = refreshUser()
            syncMutex.withLock {
                checkSignedIn()
                val pushedVersion = pushLocalSnapshot(onlyPending = true)
                // Use the server version after our push as the sync cursor so we
                // don't pull back the very deltas we just wrote. Without this,
                // applyDelta() rewrites every message of the just-pushed
                // conversation via delete + insertAll — Room re-emits the whole
                // messages Flow and ChatScreen recomposes from scratch (visible
                // as a full-page reload ~750ms after the AI message completes).
                syncInternal(
                    sinceOverride = pushedVersion.takeIf { it > 0L },
                    expectedServerVersion = account.syncVersion
                ).also { _syncError.value = null }
            }
        }
    }

    private suspend fun pushLocalSnapshot(onlyPending: Boolean): Long {
        val account = user.first() ?: error("Please sign in first")
        var latestVersion = pushPendingDeletes(account.id)
        val pending = appPreferences.cloudPendingUpserts(account.id)
        fun shouldPush(type: String, id: String) = !onlyPending || "$type:$id" in pending
        val agents = database.agentDao().getAllEntities()
        val providers = database.providerDao().getAllEntities().filter { it.id != BUILTIN_PROVIDER_ID }
        val conversations = database.conversationDao().getAllEntities()

        val pushConversations = conversations.filter { shouldPush("conversation", it.id) }
        val pushConversationIds = pushConversations.map { it.id }
        val messagesByConversation = if (pushConversationIds.isNotEmpty()) {
            database.messageDao().getByConversationIds(pushConversationIds).groupBy { it.conversationId }
        } else {
            emptyMap()
        }

        agents.filter { shouldPush("agent", it.id) }.forEach { agent ->
            val response = request {
                apiClient.putAgent(endpoint("api/agents/${agent.id}"), agent.toCloudRequest())
            }
            latestVersion = maxOf(latestVersion, response.version)
            appPreferences.removeCloudPendingUpsert(account.id, "agent", agent.id)
            syncAgentAvatar(agent)
        }
        providers.filter { shouldPush("provider", it.id) }.forEach { provider ->
            val response = request {
                apiClient.putProvider(
                    endpoint("api/providers/${provider.id}"),
                    provider.toCloudRequest(database.modelDao().getByProviderId(provider.id).first())
                )
            }
            latestVersion = maxOf(latestVersion, response.version)
            appPreferences.removeCloudPendingUpsert(account.id, "provider", provider.id)
        }
        pushConversations.forEach { conversation ->
            val response = request {
                apiClient.putConversation(
                    endpoint("api/conversations/${conversation.id}"),
                    conversation.toCloudRequest(messagesByConversation[conversation.id].orEmpty())
                )
            }
            latestVersion = maxOf(latestVersion, response.version)
            appPreferences.removeCloudPendingUpsert(account.id, "conversation", conversation.id)
        }
        val userAvatar = appPreferences.userAvatar.first()
        latestVersion = maxOf(
            latestVersion,
            when {
                userAvatar == null -> uploadUserAvatar(null).version
                !isRemoteAvatar(userAvatar) && !isCloudCachedAvatar(userAvatar) -> uploadUserAvatar(userAvatar).version
                else -> 0L
            }
        )
        return latestVersion
    }

    private suspend fun pushPendingDeletes(accountId: String): Long {
        var latestVersion = 0L
        appPreferences.cloudPendingDeletes(accountId).toList().forEach { value ->
            val separator = value.indexOf(':')
            if (separator <= 0) return@forEach
            val type = value.substring(0, separator)
            val id = value.substring(separator + 1)
            val response = runCatching {
                when (type) {
                    "agent" -> request { apiClient.deleteAgent(endpoint("api/agents/$id")) }
                    "conversation" -> request { apiClient.deleteConversation(endpoint("api/conversations/$id")) }
                    "provider" -> request { apiClient.deleteProvider(endpoint("api/providers/$id")) }
                    else -> null
                }
            }.onFailure { error ->
                val httpError = error.cause as? ResponseException
                if (httpError?.response?.status?.value != 404) throw error
            }.getOrNull()
            latestVersion = maxOf(latestVersion, response?.version ?: 0L)
            appPreferences.removeCloudPendingDelete(accountId, type, id)
        }
        return latestVersion
    }

    private suspend fun syncInternal(
        sinceOverride: Long? = null,
        replaceLocal: Boolean = false,
        expectedServerVersion: Long? = null
    ): CloudSyncResult {
        checkSignedIn()
        val account = user.first() ?: error("Please sign in first")
        val since = sinceOverride ?: appPreferences.cloudSyncVersion(account.id)
        val delta = rehydrateChatImages(fetchCloudSync(since))
        logI(
            TAG,
            "Cloud sync account=${account.id} since=$since latest=${delta.latestVersion} " +
                "agents=${delta.agents.size} conversations=${delta.conversations.size} providers=${delta.providers.size}"
        )
        check(delta.latestVersion >= since) {
            "Cloud sync cursor moved backwards: since=$since latest=${delta.latestVersion}"
        }
        check(expectedServerVersion == null || delta.latestVersion >= expectedServerVersion) {
            "Cloud sync version mismatch: account=${account.id} /me=${expectedServerVersion} /sync=${delta.latestVersion}"
        }
        if (replaceLocal) {
            database.providerDao().deleteAll()
            database.modelDao().deleteAll()
            database.agentDao().deleteAll()
            database.conversationDao().deleteAll()
            database.messageDao().deleteAll()
            appPreferences.setCurrentAgentId(null)
        }
        applyDelta(delta)
        // fullSync 的 replaceLocal 会 deleteAll 连带清掉内置服务商，这里补种。
        ensureBuiltinProvider(account)
        val configuredServerUrl = serverUrl.first()
        cacheAgentAvatars(account.id, delta, configuredServerUrl)
        cacheLegacyAgentAvatars(account.id, configuredServerUrl)
        appPreferences.setCloudSyncVersion(account.id, delta.latestVersion)
        localChangesEnabled = true
        logI(
            TAG,
            "Cloud sync applied agents=${database.agentDao().getAllEntities().size} " +
                "conversations=${database.conversationDao().getAllEntities().size} " +
                "providers=${database.providerDao().getAllEntities().size}"
        )
        return CloudSyncResult(delta.latestVersion, delta.agents.size, delta.conversations.size, delta.providers.size)
    }

    /**
     * 按 collection 翻页拉满所有 delta,最后组装成 CloudSyncResponse 复用既有路径。
     * 单批 conversation 文档可能很大(尤其含数千条 messages),分页避免单个
     * serverless 响应体爆内存、客户端一次解析卡 UI 线程。
     */
    private suspend fun fetchCloudSync(since: Long): CloudSyncResponse {
        val agents = mutableListOf<CloudAgentDocument>()
        var agentsLatest = since
        var cursor: String? = null
        do {
            val page = request { apiClient.syncAgentsPage(endpoint("api/sync"), since, cursor = cursor, limit = SYNC_PAGE_SIZE) }
            agents += page.documents
            agentsLatest = maxOf(agentsLatest, page.latestVersion)
            cursor = if (page.hasMore) page.nextCursor else null
        } while (cursor != null)

        val conversations = mutableListOf<CloudConversationDocument>()
        var conversationsLatest = since
        cursor = null
        do {
            val page = request {
                apiClient.syncConversationsPage(endpoint("api/sync"), since, cursor = cursor, limit = SYNC_PAGE_SIZE)
            }
            conversations += page.documents
            conversationsLatest = maxOf(conversationsLatest, page.latestVersion)
            cursor = if (page.hasMore) page.nextCursor else null
        } while (cursor != null)

        val providers = mutableListOf<CloudProviderDocument>()
        var providersLatest = since
        cursor = null
        do {
            val page = request { apiClient.syncProvidersPage(endpoint("api/sync"), since, cursor = cursor, limit = SYNC_PAGE_SIZE) }
            providers += page.documents
            providersLatest = maxOf(providersLatest, page.latestVersion)
            cursor = if (page.hasMore) page.nextCursor else null
        } while (cursor != null)

        return CloudSyncResponse(
            agents = agents,
            conversations = conversations,
            providers = providers,
            latestVersion = maxOf(agentsLatest, conversationsLatest, providersLatest)
        )
    }

    /**
     * 拉取端图片落地(rehydrate):partsJson 里的 localPath 是"源设备"的私有
     * 文件路径,换设备/重装恢复后该文件并不存在,而 UI(Coil)只用 localPath
     * 渲染图片。这里在写入 Room 之前把缺失文件的 image part 用其内嵌的
     * base64 dataUri 解码落地到 filesDir/chat_images/,并把 localPath 改写为
     * 本机路径,保证多模态图片在云同步后仍可显示。文件名由 messageId、part
     * 下标和 dataUri 摘要决定,重复同步幂等且不同消息互不共享文件(与
     * ChatImageStore 每条消息独立文件、删除消息时 reap 文件的约定一致)。
     */
    private fun rehydrateChatImages(delta: CloudSyncResponse): CloudSyncResponse {
        if (delta.conversations.isEmpty()) return delta
        val conversations = delta.conversations.map { conversation ->
            if (conversation.deleted || conversation.messages.isEmpty()) return@map conversation
            var conversationChanged = false
            val messages = conversation.messages.map { message ->
                val rewritten = rehydratePartsJson(message.id, message.partsJson) ?: return@map message
                conversationChanged = true
                message.copy(partsJson = rewritten)
            }
            if (conversationChanged) conversation.copy(messages = messages) else conversation
        }
        return delta.copy(conversations = conversations)
    }

    /** 返回改写后的 JSON;无需改写(纯文本/本地文件仍有效/JSON 非法)时返回 null。 */
    private fun rehydratePartsJson(messageId: String, partsJson: String?): String? {
        if (partsJson.isNullOrBlank()) return null
        val array = try {
            json.parseToJsonElement(partsJson) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return null
        var changed = false
        val rewritten = array.mapIndexed { index, element ->
            val part = element as? JsonObject ?: return@mapIndexed element
            if ((part["type"] as? JsonPrimitive)?.contentOrNull != "image") return@mapIndexed element
            val localPath = (part["localPath"] as? JsonPrimitive)?.contentOrNull
            if (localPath != null && FileKit.isUsableFile(localPath)) return@mapIndexed element
            val dataUri = (part["dataUri"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexed element
            val restored = runCatching { persistDataUri(messageId, index, dataUri) }
                .onFailure { logW(TAG, "Failed to rehydrate synced chat image message=$messageId part=$index", it) }
                .getOrNull() ?: return@mapIndexed element
            changed = true
            JsonObject(part + ("localPath" to JsonPrimitive(restored)))
        }
        return if (changed) JsonArray(rewritten).toString() else null
    }

    /**
     * 把 base64 data: URI 解码写入 filesDir/chat_images/。文件名是
     * messageId/part 下标/dataUri 的确定性摘要,重复调用直接命中已有文件。
     */
    private fun persistDataUri(messageId: String, partIndex: Int, dataUri: String): String? {
        val comma = dataUri.indexOf(',')
        if (!dataUri.startsWith("data:") || comma <= 5) return null
        val header = dataUri.substring(5, comma)
        if (!header.contains(";base64", ignoreCase = true)) return null
        val extension = when (header.substringBefore(';').trim().lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }
        val directory = chatImagesDir().apply { FileKit.mkdirs(this) }
        val target = directory.resolve("sync_${digest("$messageId|$partIndex|$dataUri")}.$extension")
        if (FileKit.isUsableFile(target.toString())) return target.toString()
        val bytes = dataUri.substring(comma + 1).decodeBase64()?.toByteArray() ?: return null
        if (bytes.isEmpty()) return null
        FileKit.writeBytesAtomic(target, bytes)
        return target.toString()
    }

    private suspend fun syncAgentAvatar(agent: AgentEntity) {
        if (agent.avatar != null && !isRemoteAvatar(agent.avatar) && !isCloudCachedAvatar(agent.avatar)) {
            uploadAgentAvatar(agent.id, agent.avatar)
        }
    }

    private suspend fun applyDelta(delta: CloudSyncResponse) {
        delta.agents.forEach { remote ->
            if (remote.deleted) {
                database.conversationDao().deleteByAgentId(remote.id)
                database.agentDao().delete(remote.id)
            } else {
                val existingAvatar = database.agentDao().getById(remote.id).first()?.avatar
                    ?.takeUnless(::isRemoteAvatar)
                    ?.takeIf(::isUsableLocalAvatar)
                if (remote.isDefault) {
                    database.agentDao().getAllEntities()
                        .filter { it.isDefault && it.id != remote.id }
                        .forEach { database.agentDao().insert(it.copy(isDefault = false)) }
                }
                // Keep an uncached URL only as a retry marker; AgentAvatar never renders it.
                database.agentDao().insert(remote.toEntity(existingAvatar ?: remote.avatarUrl))
            }
        }
        delta.providers.forEach { remote ->
            // 内置云 AI 服务商不参与云同步（本地自建，见 BUILTIN_PROVIDER_ID）。
            if (remote.id == BUILTIN_PROVIDER_ID) return@forEach
            if (remote.deleted) {
                database.modelDao().deleteByProviderId(remote.id)
                database.providerDao().delete(remote.id)
            } else {
                database.modelDao().deleteByProviderId(remote.id)
                database.providerDao().insert(remote.toEntity())
                database.modelDao().insertAll(remote.models.map { it.toEntity(remote.id) })
            }
        }
        delta.conversations.forEach { remote ->
            if (remote.deleted) {
                database.messageDao().deleteByConversationId(remote.id)
                database.conversationDao().delete(remote.id)
            } else {
                if (database.agentDao().getById(remote.agentId).first() == null) {
                    database.messageDao().deleteByConversationId(remote.id)
                    database.conversationDao().delete(remote.id)
                    return@forEach
                }
                database.messageDao().deleteByConversationId(remote.id)
                database.conversationDao().insert(remote.toEntity())
                remote.messages.map { it.toEntity(remote.id) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { database.messageDao().insertAll(it) }
            }
        }
    }

    private suspend fun cacheAgentAvatars(accountId: String, delta: CloudSyncResponse, configuredServerUrl: String) {
        delta.agents.forEach { remote ->
            if (remote.deleted) {
                deleteCachedAvatars("agent", accountId, remote.id)
                return@forEach
            }

            val agent = database.agentDao().getById(remote.id).first() ?: return@forEach
            val previousAvatar = agent.avatar
            if (remote.avatarUrl == null) {
                val localAvatar = previousAvatar
                    ?.takeUnless(::isCloudCachedAvatar)
                    ?.takeIf(::isUsableLocalAvatar)
                if (localAvatar == null) {
                    database.agentDao().insert(agent.copy(avatar = null))
                    if (isCloudCachedAvatar(previousAvatar)) {
                        deleteLocalAvatarIfReplaced(previousAvatar, null)
                    }
                }
                deleteCachedAvatars("agent", accountId, remote.id)
                return@forEach
            }

            if (previousAvatar != null && !isCloudCachedAvatar(previousAvatar) &&
                isUsableLocalAvatar(previousAvatar)
            ) {
                return@forEach
            }

            val cachedAvatar = runCatching {
                cacheRemoteAvatar(
                    "agent",
                    accountId,
                    remote.id,
                    remote.avatarUrl,
                    remote.avatarVersion?.toString(),
                    configuredServerUrl
                )
            }.getOrNull() ?: return@forEach
            database.agentDao().insert(agent.copy(avatar = cachedAvatar))
            deleteLocalAvatarIfReplaced(previousAvatar, cachedAvatar)
        }
    }

    private suspend fun cacheLegacyAgentAvatars(accountId: String, configuredServerUrl: String) {
        database.agentDao().getAllEntities().forEach { agent ->
            val remoteUrl = agent.avatar?.takeIf(::isRemoteAvatar) ?: return@forEach
            val cachedAvatar = runCatching {
                cacheRemoteAvatar("agent", accountId, agent.id, remoteUrl, baseUrl = configuredServerUrl)
            }.getOrNull() ?: return@forEach
            database.agentDao().insert(agent.copy(avatar = cachedAvatar))
        }
    }

    private suspend fun requireAgentForMarket(agentId: String): AgentEntity {
        return checkNotNull(database.agentDao().getById(agentId).first()) { "Agent not found." }
    }

    private suspend fun AgentEntity.toMarketRequest(): CloudMarketAgentRequest {
        val defaultAgent = database.agentDao().getAllEntities().firstOrNull { it.isDefault }
        return CloudMarketAgentRequest(
            name = name.trim(),
            systemPrompt = if (followDefaultSystemPrompt) defaultAgent?.systemPrompt ?: systemPrompt else systemPrompt,
            temperature = (if (followDefaultTemperature) defaultAgent?.temperature ?: temperature else temperature).toDouble(),
            topP = (if (followDefaultTopP) defaultAgent?.topP ?: topP else topP).toDouble(),
            maxTokens = if (followDefaultMaxTokens) defaultAgent?.maxTokens ?: maxTokens else maxTokens,
            reasoningEffort = if (followDefaultReasoningEffort) defaultAgent?.reasoningEffort ?: reasoningEffort else reasoningEffort
        )
    }

    private suspend fun updateMarketAvatar(market: CloudMarketAgent, avatar: String?): CloudMarketAgent {
        val currentAvatar = avatar?.takeIf(::isUsableLocalAvatar)
        val response = when {
            currentAvatar != null -> request {
                apiClient.uploadMarketAgentAvatar(
                    endpoint("api/market/agents/${market.id}/avatar"),
                    avatarName(currentAvatar),
                    avatarBytes(currentAvatar),
                    avatarMime(currentAvatar)
                )
            }
            market.avatarUrl != null -> request {
                apiClient.deleteMarketAgentAvatar(endpoint("api/market/agents/${market.id}/avatar"))
            }
            else -> null
        }
        return response?.let { market.copy(version = it.version, avatarVersion = it.avatarVersion) } ?: market
    }

    private suspend fun persistMarketLink(
        agentId: String,
        marketAgentId: String?,
        marketAgentVersion: Long?,
        marketAgentRole: String?
    ) {
        val existing = requireAgentForMarket(agentId)
        val updated = existing.copy(
            marketAgentId = marketAgentId,
            marketAgentVersion = marketAgentVersion,
            marketAgentRole = marketAgentRole,
            updatedAt = System.currentTimeMillis()
        )
        database.agentDao().update(updated)
        requestLocalChange("agent", agentId)
    }

    private fun copyMarketAvatarToAgentStorage(sourcePath: String): String {
        if (!FileKit.isUsableFile(sourcePath)) return sourcePath
        val directory = filesDir.resolve("agent_avatars").apply { FileKit.mkdirs(this) }
        val target = directory.resolve("market_${randomUuid()}.${FileKit.extensionOf(sourcePath).ifBlank { "jpg" }}")
        FileKit.copy(sourcePath, target.toString())
        return target.toString()
    }

    private suspend fun checkSignedIn() {
        check(appPreferences.cloudSession.first() != null) { "Please sign in first" }
    }

    private suspend fun prepareRequestServer(override: String?): String {
        val baseUrl = normalizeServerUrl(override ?: serverUrl.first())
        val currentHost = appPreferences.cloudSessionHost.first()
        if (currentHost != null && currentHost != baseUrl) {
            localChangesEnabled = false
            localDataMutex.withLock {
                database.agentDao().clearAllMarketLinks()
                removeBuiltinProvider()
            }
            appPreferences.setCloudSession(null)
            appPreferences.setCloudSessionHost(null)
            appPreferences.setCloudUser(null)
            appPreferences.setCloudAiApiKey(null)
        }
        appPreferences.setCloudServerUrl(baseUrl.takeUnless { it == DEFAULT_CLOUD_SERVER_URL })
        return baseUrl
    }

    private suspend fun saveUser(value: CloudUser) {
        appPreferences.setCloudUser(json.encodeToString(CloudUser.serializer(), value))
        appPreferences.setCloudAiApiKey(value.aiApiKey)
        ensureBuiltinProvider(value)
    }

    /**
     * 登录后自动配置内置云 AI 服务商：固定 ID 指向 {server}/v1，API Key 为
     * 用户级 AI API Key（网页控制台可重置，重置后下一次 /me 刷新即同步到本地）。
     * 幂等：已存在且配置一致时跳过。随后自动同步模型列表（含 context window）。
     */
    private suspend fun ensureBuiltinProvider(value: CloudUser) {
        val apiKey = value.aiApiKey
        if (value.id.isEmpty() || apiKey.isNullOrBlank()) return
        val baseUrl = "${serverUrl.first()}/v1"
        var reconfigured = false
        val existing = database.providerDao().getById(BUILTIN_PROVIDER_ID).first()
        if (existing == null) {
            val now = System.currentTimeMillis()
            database.providerDao().insert(
                ProviderEntity(BUILTIN_PROVIDER_ID, BUILTIN_PROVIDER_NAME, baseUrl, apiKey, now, now)
            )
            reconfigured = true
            logI(TAG, "Seeded built-in cloud AI provider baseUrl=$baseUrl")
        } else if (existing.name != BUILTIN_PROVIDER_NAME || existing.baseUrl != baseUrl || existing.apiKey != apiKey) {
            val now = System.currentTimeMillis()
            database.providerDao().insert(
                existing.copy(name = BUILTIN_PROVIDER_NAME, baseUrl = baseUrl, apiKey = apiKey, updatedAt = now)
            )
            reconfigured = true
            logI(TAG, "Refreshed built-in cloud AI provider baseUrl=$baseUrl")
        }
        syncBuiltinProviderModels(force = reconfigured)
    }

    /**
     * 自动同步内置云 AI 服务商的模型列表（含 /v1/models 附带的
     * context_window 元数据）。每次拉取是幂等的整体替换：保留既有行的
     * id（Agent 的模型绑定不受影响）与 isEnabled（用户的启停选择不被
     * 覆盖），消失的模型移除，新增的模型以确定性 ID 落库。
     *
     * 触发时机：服务商（重）配置时强制同步；否则按 [BUILTIN_MODELS_SYNC_INTERVAL_MS]
     * 节流 —— /me 刷新挂在每次云同步与本地变更推送上，不能每次都打 /v1/models。
     * 失败只记日志，不阻塞登录/同步主流程。
     */
    private suspend fun syncBuiltinProviderModels(force: Boolean) {
        val provider = database.providerDao().getById(BUILTIN_PROVIDER_ID).first() ?: return
        val now = System.currentTimeMillis()
        val hasModels = database.modelDao().getByProviderId(BUILTIN_PROVIDER_ID).first().isNotEmpty()
        if (!force && hasModels && now - lastBuiltinModelsSyncAt < BUILTIN_MODELS_SYNC_INTERVAL_MS) return
        runCatching {
            val response = OpenAiClient(provider.baseUrl, provider.apiKey).getModels()
            val existingByModelId = database.modelDao().getByProviderId(BUILTIN_PROVIDER_ID)
                .first()
                .associateBy { it.modelId }
            val entities = response.data.map { dto ->
                val previous = existingByModelId[dto.id]
                ModelEntity(
                    id = previous?.id ?: "$BUILTIN_PROVIDER_ID:${dto.id}",
                    providerId = BUILTIN_PROVIDER_ID,
                    modelId = dto.id,
                    displayName = dto.id,
                    isEnabled = previous?.isEnabled ?: true,
                    contextWindow = dto.contextWindow ?: 0L,
                    createdAt = previous?.createdAt ?: System.currentTimeMillis()
                )
            }
            database.modelDao().deleteByProviderId(BUILTIN_PROVIDER_ID)
            if (entities.isNotEmpty()) {
                database.modelDao().insertAll(entities)
            }
            lastBuiltinModelsSyncAt = System.currentTimeMillis()
            logI(TAG, "Synced ${entities.size} built-in cloud AI models")
        }.onFailure { error ->
            logW(TAG, "Built-in cloud AI model sync failed", error)
        }
    }

    private suspend fun removeBuiltinProvider() {
        database.modelDao().deleteByProviderId(BUILTIN_PROVIDER_ID)
        database.providerDao().delete(BUILTIN_PROVIDER_ID)
    }

    private suspend fun refreshCachedUserAvatar(url: String?, avatarVersion: Long?) {
        val current = user.first() ?: return
        saveUser(current.copy(avatarUrl = url, avatarVersion = avatarVersion))
    }

    /** Stores a server avatar locally so rendering never depends on a network request. */
    private suspend fun cacheRemoteAvatar(
        scope: String,
        accountId: String,
        id: String,
        url: String,
        version: String? = null,
        baseUrl: String
    ): String {
        val directory = filesDir.resolve("cloud_avatars").apply { FileKit.mkdirs(this) }
        val identity = digest("$scope|$accountId|$id")
        val requestUrl = resolveAvatarUrl(url, baseUrl)
        val urlKey = digest("$requestUrl|${version.orEmpty()}")
        // 本地命中文件(非空,扩展名任意)。命中后仍发条件 GET 校验 ETag,
        // server 返回 304 时直接复用本地文件,200 时覆盖并更新 sidecar ETag。
        val existing = FileKit.list(directory)
            .firstOrNull {
                it.name.startsWith("$identity-$urlKey.") && !it.name.endsWith(ETAG_SIDECAR_SUFFIX) &&
                    FileKit.isUsableFile(it.toString())
            }
        val storedEtag = existing?.let { readEtagSidecar(it) }

        val response = apiClient.download.get(requestUrl) {
            if (existing != null && !storedEtag.isNullOrEmpty()) {
                header(HttpHeaders.IfNoneMatch, storedEtag)
            }
        }
        // 304 命中:本地文件仍最新,直接复用,不重写 sidecar(ETag 不变)。
        if (response.status.value == 304 && existing != null) {
            return existing.toString()
        }
        if (!response.status.isSuccess()) {
            throw Exception("Avatar download failed (${response.status.value})")
        }
        val contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
        check(contentLength <= MAX_AVATAR_BYTES) {
            "Avatar must not exceed 5 MiB"
        }
        val bytes = response.bodyAsChannel().readRemaining(MAX_AVATAR_BYTES + 1).readByteArray()
        check(bytes.size <= MAX_AVATAR_BYTES) { "Avatar must not exceed 5 MiB" }
        check(bytes.isNotEmpty()) { "Avatar response was empty" }
        val extension = avatarExtension(response.headers[HttpHeaders.ContentType], requestUrl)
        val target = directory.resolve("$identity-$urlKey.$extension")
        FileKit.writeBytesAtomic(target, bytes)
        writeEtagSidecar(target, response.headers[HttpHeaders.ETag])
        deleteCachedAvatarsExcept(scope, accountId, id, target)
        return target.toString()
    }

    /** 复用同一文件名加 .etag 后缀存放 server 返回的 ETag。 */
    private fun etagSidecarFor(avatar: Path): Path =
        avatar.parent!!.resolve("${avatar.name}$ETAG_SIDECAR_SUFFIX")

    private fun readEtagSidecar(avatar: Path): String? =
        etagSidecarFor(avatar).takeIf { FileKit.exists(it.toString()) }
            ?.let { FileKit.readText(it.toString()) }?.trim()?.ifEmpty { null }

    private fun writeEtagSidecar(avatar: Path, etag: String?) {
        val sidecar = etagSidecarFor(avatar)
        if (etag.isNullOrEmpty()) {
            FileKit.delete(sidecar.toString())
        } else {
            FileKit.writeText(sidecar.toString(), etag)
        }
    }

    private fun deleteCachedAvatars(scope: String, accountId: String, id: String) {
        val identity = digest("$scope|$accountId|$id")
        FileKit.list(filesDir.resolve("cloud_avatars"))
            .filter { it.name.startsWith("$identity-") }
            .forEach { FileKit.delete(it.toString()) }
    }

    private fun deleteCachedAvatarsExcept(scope: String, accountId: String, id: String, keep: Path) {
        val identity = digest("$scope|$accountId|$id")
        FileKit.list(filesDir.resolve("cloud_avatars"))
            .filter { it.name.startsWith("$identity-") && it != keep }
            .forEach { FileKit.delete(it.toString()) }
    }

    private fun deleteLocalAvatarIfReplaced(previous: String?, current: String?) {
        if (previous != null && previous != current && !isRemoteAvatar(previous)) {
            FileKit.delete(previous)
            // 同步清理 cloud_avatars 目录下的 ETag sidecar(其他目录没有 sidecar,delete 静默跳过)
            val sidecar = etagSidecarFor(previous.toPath())
            FileKit.delete(sidecar.toString())
        }
    }

    private fun isUsableLocalAvatar(value: String): Boolean =
        !isRemoteAvatar(value) && FileKit.isUsableFile(value)

    private fun isCloudCachedAvatar(value: String?): Boolean =
        value != null && value.toPath().parent == filesDir.resolve("cloud_avatars")

    private fun avatarExtension(contentType: String?, url: String): String {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        return when (mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> url.substringBefore('?').substringAfterLast('.', "img")
                .lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) }
                ?: "img"
        }
    }

    private fun digest(value: String): String = value.encodeUtf8().sha256().hex()

    private fun avatarBytes(path: String): ByteArray {
        check(FileKit.exists(path)) { "Avatar file not found" }
        val size = FileKit.size(path)
        check(size in 1..MAX_AVATAR_BYTES) { "Avatar must not exceed 5 MiB" }
        return FileKit.readBytes(path)
    }

    private fun avatarName(path: String): String = FileKit.nameOf(path)

    private fun avatarMime(path: String): String = when (FileKit.extensionOf(path)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    private fun isRemoteAvatar(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")

    /** The API may build absolute URLs from its internal localhost origin. Use the configured server host. */
    private fun resolveAvatarUrl(url: String, baseUrl: String): String {
        val base = Url(baseUrl)
        val source = Url(url)
        return URLBuilder(
            protocol = base.protocol,
            host = base.host,
            port = base.port,
            pathSegments = source.rawSegments.ifEmpty { listOf("") },
            parameters = source.parameters,
            fragment = source.fragment
        ).buildString()
    }

    private suspend fun endpoint(path: String, baseUrl: String? = null): String =
        "${(baseUrl ?: serverUrl.first()).trimEnd('/')}/$path"

    private suspend fun <T> request(block: suspend () -> T): T {
        return try {
            block()
        } catch (error: ResponseException) {
            val message = cloudErrorMessage(error)
            _syncError.value = message
            if (error.response.status.value == 401) {
                localChangesEnabled = false
                appPreferences.setCloudSession(null)
                appPreferences.setCloudSessionHost(null)
                appPreferences.setCloudUser(null)
            }
            throw Exception(message, error)
        }
    }

    private suspend fun cloudErrorMessage(error: ResponseException): String {
        val body = try {
            error.response.bodyAsText()
        } catch (_: Exception) {
            null
        }
        val message = body?.let {
            runCatching {
                val parsed = json.parseToJsonElement(it).jsonObject
                when (val errorValue = parsed["error"]) {
                    is JsonPrimitive -> errorValue.contentOrNull
                    is JsonObject -> errorValue["message"]?.jsonPrimitive?.contentOrNull
                    else -> null
                }
            }.getOrNull()
        }
        return message ?: "Cloud request failed (${error.response.status.value})"
    }

    private fun normalizeServerUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
            "Server address must use HTTP or HTTPS"
        }
        return normalized
    }

    private fun chatImagesDir(): Path = filesDir.resolve(CHAT_IMAGES_SUBDIR)

    private companion object {
        const val TAG = "CloudSyncRepository"
        const val MAX_AVATAR_BYTES = 5L * 1024L * 1024L
        // 单次 /api/sync?collection=... 拉取的页大小。server 端 SYNC_MAX_LIMIT=500,
        // 这里取 100 兼顾请求数与单次响应体大小:一个 conversation 文档含数千条
        // messages 时,100 条/页已经接近 serverless 单次响应的舒适区。
        const val SYNC_PAGE_SIZE = 100
        const val ETAG_SIDECAR_SUFFIX = ".etag"
        /** 与 [cc.ptoe.messenger.data.local.ChatImageStore.CACHE_SUBDIR] 相同的目录。 */
        const val CHAT_IMAGES_SUBDIR = "chat_images"
        /** 内置云 AI 模型列表自动同步的最小间隔（进程内节流）。 */
        const val BUILTIN_MODELS_SYNC_INTERVAL_MS = 60L * 60L * 1000L
    }
}

private fun AgentEntity.toCloudRequest() = CloudAgentRequest(
    id = id,
    name = name,
    systemPrompt = systemPrompt,
    defaultModelId = defaultModelId,
    temperature = temperature.toDouble(),
    topP = topP.toDouble(),
    maxTokens = maxTokens,
    reasoningEffort = reasoningEffort,
    isDefault = isDefault,
    followDefaultSystemPrompt = followDefaultSystemPrompt,
    followDefaultModel = followDefaultModel,
    followDefaultTemperature = followDefaultTemperature,
    followDefaultTopP = followDefaultTopP,
    followDefaultMaxTokens = followDefaultMaxTokens,
    followDefaultReasoningEffort = followDefaultReasoningEffort,
    marketAgentId = marketAgentId,
    marketAgentVersion = marketAgentVersion,
    marketAgentRole = marketAgentRole,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun AgentEntity.isDefaultAgentBaseline(): Boolean =
    isDefault &&
        name == "默认 Agent" &&
        avatar == null &&
        systemPrompt == "You are a helpful assistant." &&
        defaultModelId == null &&
        temperature == 0.7f &&
        topP == 1.0f &&
        maxTokens == null &&
        reasoningEffort == null &&
        !followDefaultSystemPrompt &&
        !followDefaultModel &&
        !followDefaultTemperature &&
        !followDefaultTopP &&
        !followDefaultMaxTokens &&
        !followDefaultReasoningEffort

private fun ProviderEntity.toCloudRequest(models: List<ModelEntity>) = CloudProviderRequest(
    id = id,
    name = name,
    baseUrl = baseUrl,
    apiKey = apiKey,
    models = models.map {
        CloudModelRequest(it.id, it.modelId, it.displayName, it.isEnabled, it.contextWindow, it.createdAt)
    },
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun ConversationEntity.toCloudRequest(messages: List<MessageEntity>) = CloudConversationRequest(
    id = id,
    title = title,
    agentId = agentId,
    providerId = providerId,
    overrideModelId = overrideModelId,
    overrideTemperature = overrideTemperature?.toDouble(),
    overrideTopP = overrideTopP?.toDouble(),
    overrideMaxTokens = overrideMaxTokens,
    overrideReasoningEffort = overrideReasoningEffort,
    reasoningFormat = reasoningFormat,
    contextSummary = contextSummary,
    contextSummaryUntil = contextSummaryUntil,
    contextTokens = contextTokens,
    contextTokensAt = contextTokensAt,
    messages = messages.map { message ->
        CloudMessageRequest(
            id = message.id,
            role = message.role.lowercase(),
            content = message.content,
            timestamp = message.timestamp,
            status = message.status.uppercase(),
            errorMessage = message.errorMessage,
            partsJson = message.partsJson
        )
    },
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun CloudAgentDocument.toEntity(avatar: String?) = AgentEntity(
    id = id,
    name = name,
    avatar = avatar,
    systemPrompt = systemPrompt,
    defaultModelId = defaultModelId,
    temperature = temperature.toFloat(),
    topP = topP.toFloat(),
    maxTokens = maxTokens,
    reasoningEffort = reasoningEffort,
    isDefault = isDefault,
    followDefaultSystemPrompt = followDefaultSystemPrompt,
    followDefaultModel = followDefaultModel,
    followDefaultTemperature = followDefaultTemperature,
    followDefaultTopP = followDefaultTopP,
    followDefaultMaxTokens = followDefaultMaxTokens,
    followDefaultReasoningEffort = followDefaultReasoningEffort,
    marketAgentId = marketAgentId,
    marketAgentVersion = marketAgentVersion,
    marketAgentRole = marketAgentRole,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun AgentEntity.toDomain() = Agent(
    id = id,
    name = name,
    avatar = avatar,
    systemPrompt = systemPrompt,
    defaultModelId = defaultModelId,
    temperature = temperature,
    topP = topP,
    maxTokens = maxTokens,
    reasoningEffort = reasoningEffort,
    isDefault = isDefault,
    followDefaultSystemPrompt = followDefaultSystemPrompt,
    followDefaultModel = followDefaultModel,
    followDefaultTemperature = followDefaultTemperature,
    followDefaultTopP = followDefaultTopP,
    followDefaultMaxTokens = followDefaultMaxTokens,
    followDefaultReasoningEffort = followDefaultReasoningEffort,
    marketAgentId = marketAgentId,
    marketAgentVersion = marketAgentVersion,
    marketAgentRole = marketAgentRole,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun CloudProviderDocument.toEntity() = ProviderEntity(id, name, baseUrl, apiKey, createdAt, updatedAt)

private fun CloudModelDocument.toEntity(providerId: String) =
    ModelEntity(id, providerId, modelId, displayName, isEnabled, contextWindow, createdAt)

private fun CloudConversationDocument.toEntity() = ConversationEntity(
    id = id,
    title = title,
    providerId = providerId,
    agentId = agentId,
    overrideModelId = overrideModelId,
    overrideTemperature = overrideTemperature?.toFloat(),
    overrideTopP = overrideTopP?.toFloat(),
    overrideMaxTokens = overrideMaxTokens,
    overrideReasoningEffort = overrideReasoningEffort,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastMessage = messages.lastOrNull()?.content,
    reasoningFormat = reasoningFormat,
    contextSummary = contextSummary,
    contextSummaryUntil = contextSummaryUntil,
    contextTokens = contextTokens,
    contextTokensAt = contextTokensAt
)

private fun CloudMessageDocument.toEntity(conversationId: String) = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role.lowercase(),
    content = content,
    partsJson = partsJson,
    timestamp = timestamp,
    status = status.lowercase(),
    errorMessage = errorMessage
)
