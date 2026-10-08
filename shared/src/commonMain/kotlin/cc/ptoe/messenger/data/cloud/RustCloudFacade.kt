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

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.StoredAgentDto
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.remote.createPlatformHttpClient
import cc.ptoe.messenger.data.util.ioDispatcher
import cc.ptoe.messenger.data.util.logW
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.repository.CloudFacade
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

/**
 * [CloudFacade] over the Rust sync engine, for targets where Room does not
 * exist (the browser). It performs no networking itself — every call is a
 * [CoreBridge] method — which is why the same class is usable from any
 * platform that can host the core.
 *
 * What stays in Kotlin, deliberately:
 * - the sync debounce scheduler (the Rust core has no scheduler and states
 *   that debouncing is the caller's job),
 * - [syncError], a display-only channel with no Rust equivalent,
 * - the local-cleanup follow-ups of login/logout/server change,
 * - the cookie-aware Ktor client Coil uses for avatar images.
 */
class RustCloudFacade(
    val core: CoreBridge,
    private val appPreferences: AppPreferences,
    private val localDataMutex: Mutex = Mutex(),
) : CloudFacade {

    private val json: Json = NetworkClient.json

    private val syncScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /** Mirrors the Room facade's gate; without it nothing is marked dirty. */
    private var localChangesEnabled = false

    private var scheduledSync: Job? = null

    private val _syncError = MutableStateFlow<String?>(null)
    override val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _user = MutableStateFlow<CloudUser?>(null)
    private val _serverUrl = MutableStateFlow(defaultCloudServerUrl)

    init {
        // The Rust engine's own fallback base is the production host, not the
        // page's origin, so the effective base is pinned before any request
        // can be built (see the same-origin hosting note in the plan).
        val origin = platformOrigin()
        if (origin.isNotBlank()) {
            _serverUrl.value = origin
            runCatching { core.cloudSetServerUrl(origin) }
        } else {
            _serverUrl.value = core.cloudGetServerUrl()
        }
        readUserFromCore()
        core.subscribe { kind, _ ->
            // The sync engine persists user/session through kv_set, which
            // arrives as an Other-kind store event; re-read on those.
            if (kind == "Other") readUserFromCore()
        }
    }

    override val user: Flow<CloudUser?> = _user.asStateFlow()

    override val serverUrl: Flow<String> = _serverUrl.asStateFlow()

    /** Cookie-aware client Coil uses to render authenticated avatars. */
    override val avatarHttpClient: HttpClient by lazy {
        createPlatformHttpClient {
            expectSuccess = false
            install(Logging) { level = LogLevel.HEADERS }
            install(HttpCookies)
        }
    }

    private fun readUserFromCore() {
        val raw = runCatching { core.cloudCurrentUserJson() }.getOrNull()
        _user.value = raw?.let {
            runCatching { json.decodeFromString(CloudUser.serializer(), it) }.getOrNull()
        }
    }

    // ------------------------------------------------------------------
    // server / auth
    // ------------------------------------------------------------------

    override suspend fun setServerUrl(url: String) {
        val normalized = normalizeServerUrl(url)
        if (_serverUrl.value != normalized) {
            localChangesEnabled = false
            localDataMutex.withLock { core.cloudClearMarketLinksAndBuiltinProvider() }
            clearSessionKeys()
            _user.value = null
        }
        _serverUrl.value = normalized
        core.cloudSetServerUrl(normalized)
    }

    override suspend fun login(email: String, password: String, serverUrl: String?): CloudLoginOutcome =
        request {
            prepareRequestServer(serverUrl)
            val user = json.decodeFromString(CloudUser.serializer(), core.cloudLogin(email.trim(), password))
            _user.value = user
            localChangesEnabled = true
            CloudLoginOutcome(
                user = user,
                hasLocalData = core.cloudHasLocalData(),
                cloudVersion = user.syncVersion
            )
        }

    override suspend fun register(email: String, password: String, serverUrl: String?): CloudLoginOutcome =
        request {
            prepareRequestServer(serverUrl)
            val user = json.decodeFromString(CloudUser.serializer(), core.cloudRegister(email.trim(), password))
            _user.value = user
            localChangesEnabled = true
            CloudLoginOutcome(
                user = user,
                hasLocalData = core.cloudHasLocalData(),
                cloudVersion = user.syncVersion
            )
        }

    override suspend fun refreshUser(): CloudUser = request {
        val user = json.decodeFromString(CloudUser.serializer(), core.cloudRefreshUser())
        _user.value = user
        // Kotlin's refreshUser also refreshes the builtin provider's models
        // and the avatar cache; Rust's does the first only, and the avatar
        // part is what `cacheRemoteAvatar` below covers.
        runCatching { core.cloudSyncBuiltinModels(force = true) }
        user
    }

    override suspend fun logout() {
        runCatching { core.cloudLogout() }
        localChangesEnabled = false
        localDataMutex.withLock { core.cloudClearMarketLinksAndBuiltinProvider() }
        clearSessionKeys()
        _user.value = null
    }

    override suspend fun changePassword(currentPassword: String, newPassword: String) = request {
        core.cloudChangePassword(currentPassword, newPassword)
    }

    override suspend fun deleteAccount(currentPassword: String) {
        cancelPendingLocalSync()
        request { core.cloudDeleteAccount(currentPassword) }
        localDataMutex.withLock { core.cloudClearMarketLinksAndBuiltinProvider() }
        clearSessionKeys()
        _user.value = null
    }

    // ------------------------------------------------------------------
    // sync
    // ------------------------------------------------------------------

    override suspend fun sync(): CloudSyncResult = request {
        runCatching { refreshUser() }
        decodeSync(core.cloudSync(replaceLocal = false))
    }

    override suspend fun upload(): CloudSyncResult = request {
        runCatching { refreshUser() }
        core.cloudPushSnapshot()
        decodeSync(core.cloudSyncWith(since = null, replaceLocal = false, expectedServerVersion = null))
    }

    override suspend fun completeLogin(useLocalData: Boolean): CloudSyncResult = request {
        localChangesEnabled = true
        if (useLocalData) {
            decodeSync(core.cloudReplaceCloudWithLocal())
        } else {
            decodeSync(core.cloudSync(replaceLocal = true))
        }
    }

    override suspend fun downloadAndRestore(): CloudSyncResult = request {
        decodeSync(core.cloudSync(replaceLocal = true))
    }

    /**
     * The debounce lives here on purpose: the Rust core has no scheduler.
     * Mirrors `CloudSyncRepository.requestLocalSync` (750 ms, then push).
     */
    override fun requestLocalSync() {
        scheduledSync?.cancel()
        scheduledSync = syncScope.launch {
            delay(750.milliseconds)
            runCatching { core.cloudPushPending() }
                .onFailure { _syncError.value = it.message ?: "Cloud synchronization failed" }
        }
    }

    override suspend fun cancelPendingLocalSync() {
        syncScope.coroutineContext.cancelChildren()
        syncScope.coroutineContext[Job]?.children?.toList()?.joinAll()
        scheduledSync = null
        localChangesEnabled = false
        _syncError.value = null
    }

    override fun requestLocalChange(type: String, id: String, deleted: Boolean) {
        syncScope.launch {
            if (!localChangesEnabled) return@launch
            if (core.cloudCurrentUserJson() == null) return@launch
            runCatching { core.cloudMarkChange(type, id, deleted) }
        }
    }

    /**
     * Mirrors the Room facade's avatar bookkeeping: when an agent's avatar
     * disappears locally the remote copy is removed; uploads happen through
     * [uploadAgentAvatar] (the edit screen calls it explicitly).
     */
    override fun requestAgentAvatarChange(previous: Agent?, current: Agent?) {
        val agentId = current?.id ?: previous?.id ?: return
        if (previous?.avatar == current?.avatar) return
        if (current?.avatar != null) return
        syncScope.launch {
            runCatching { core.cloudDeleteAgentAvatar(agentId) }
        }
    }

    // ------------------------------------------------------------------
    // cards
    // ------------------------------------------------------------------

    override suspend fun previewRedeemCard(code: String): CloudCardPreview = request {
        json.decodeFromString(CloudCardPreview.serializer(), core.cloudPreviewCard(code))
    }

    override suspend fun redeemCard(code: String): CloudRedeemResponse = request {
        val response = json.decodeFromString(CloudRedeemResponse.serializer(), core.cloudRedeemCard(code))
        runCatching { refreshUser() }
        response
    }

    // ------------------------------------------------------------------
    // market
    // ------------------------------------------------------------------

    override suspend fun listMarketAgents(
        query: String,
        cursor: String?
    ): CloudMarketAgentListResponse = request {
        json.decodeFromString(
            CloudMarketAgentListResponse.serializer(),
            core.cloudListMarketAgents(query, cursor)
        )
    }

    override suspend fun marketAgent(id: String): CloudMarketAgent = request {
        json.decodeFromString(CloudMarketAgent.serializer(), core.cloudGetMarketAgent(id))
    }

    override suspend fun publishMarketAgent(agentId: String): CloudMarketAgent = request {
        json.decodeFromString(CloudMarketAgent.serializer(), core.cloudPublishMarketAgent(agentId))
    }

    override suspend fun pushMarketAgentUpdate(agentId: String): CloudMarketAgent = request {
        json.decodeFromString(CloudMarketAgent.serializer(), core.cloudPushMarketAgentUpdate(agentId))
    }

    override suspend fun removeMarketAgent(agentId: String) = request {
        core.cloudRemoveMarketAgent(agentId)
    }

    /**
     * Import with the market avatar cached (the Rust import alone leaves the
     * remote URL in place, which is what Android avoids too).
     */
    override suspend fun importMarketAgent(marketId: String): Agent = request {
        decodeStoredAgent(core.cloudImportMarketAgentWithAvatar(marketId))
    }

    override suspend fun checkMarketAgentUpdate(agentId: String): CloudMarketAgentUpdate = request {
        json.decodeFromString(
            CloudMarketAgentUpdate.serializer(),
            core.cloudCheckMarketAgentUpdate(agentId)
        )
    }

    override suspend fun applyMarketAgentUpdate(agentId: String, market: CloudMarketAgent): Agent = request {
        decodeStoredAgent(
            core.cloudApplyMarketAgentUpdate(
                agentId,
                json.encodeToString(CloudMarketAgent.serializer(), market)
            )
        )
    }

    // ------------------------------------------------------------------
    // avatars
    // ------------------------------------------------------------------

    override suspend fun uploadUserAvatar(path: String?): CloudAvatarResponse = request {
        val source = path?.takeIf { it.isNotBlank() }
        val url = if (source == null) {
            core.cloudDeleteUserAvatar()
        } else {
            val (bytes, mime) = avatarBytes(source)
            core.cloudUploadUserAvatar(bytes, "user_avatar.$mime", mime)
        }
        CloudAvatarResponse(url.ifBlank { null }, 0L)
    }

    override suspend fun uploadAgentAvatar(agentId: String, path: String?): CloudAvatarResponse = request {
        val source = path?.takeIf { it.isNotBlank() }
        val url = if (source == null) {
            core.cloudDeleteAgentAvatar(agentId)
        } else {
            val (bytes, mime) = avatarBytes(source)
            core.cloudUploadAgentAvatar(agentId, bytes, "agent_avatar.$mime", mime)
        }
        CloudAvatarResponse(url.ifBlank { null }, 0L)
    }

    override suspend fun ensureBuiltinTitleAgent() {
        runCatching { core.cloudEnsureBuiltinTitleAgent() }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun decodeSync(raw: String): CloudSyncResult =
        json.decodeFromString(CloudSyncResult.serializer(), raw)

    private fun decodeStoredAgent(raw: String): Agent {
        val dto = json.decodeFromString(StoredAgentDto.serializer(), raw)
        return Agent(
            id = dto.id,
            name = dto.name,
            avatar = dto.avatar,
            systemPrompt = dto.systemPrompt,
            description = dto.description,
            defaultModelId = dto.defaultModelId,
            temperature = dto.temperature ?: 0.7f,
            topP = dto.topP ?: 1.0f,
            maxTokens = dto.maxTokens,
            reasoningEffort = dto.reasoningEffort,
            isDefault = dto.isDefault,
            followDefaultSystemPrompt = dto.followDefaultSystemPrompt,
            followDefaultModel = dto.followDefaultModel,
            followDefaultTemperature = dto.followDefaultTemperature,
            followDefaultTopP = dto.followDefaultTopP,
            followDefaultMaxTokens = dto.followDefaultMaxTokens,
            followDefaultReasoningEffort = dto.followDefaultReasoningEffort,
            marketAgentId = dto.marketAgentId,
            marketAgentVersion = dto.marketAgentVersion,
            marketAgentRole = dto.marketAgentRole,
            role = dto.role,
            toolsEnabled = dto.toolsEnabled,
            toolsFollowDefault = dto.toolsFollowDefault,
            toolsConfig = cc.ptoe.messenger.data.util.ToolsConfigCodec.decode(dto.toolsConfig),
            createdAt = dto.createdAt,
            updatedAt = dto.updatedAt
        )
    }

    /** Avatar bytes + extension: `data:` URIs (web) or a real file path. */
    private suspend fun avatarBytes(source: String): Pair<ByteArray, String> {
        return if (source.startsWith("data:")) {
            val comma = source.indexOf(',')
            val header = source.substring(5, maxOf(5, comma))
            val extension = when (header.substringBefore(';').lowercase()) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "png"
            }
            decodeBase64(source.substring(comma + 1)) to extension
        } else {
            val extension = source.substringAfterLast('.', "jpg").substringBefore('?').lowercase()
            readBytesFromPath(source) to extension
        }
    }

    private fun decodeBase64(text: String): ByteArray {
        val clean = text.filterNot { it.isWhitespace() }
        val output = ByteArray(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var index = 0
        for (char in clean) {
            if (char == '=') break
            val value = BASE64_ALPHABET.indexOf(char)
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output[index++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return output.copyOf(index)
    }

    private fun prepareRequestServer(serverUrl: String?): String {
        val baseUrl = normalizeServerUrl(serverUrl ?: _serverUrl.value)
        _serverUrl.value = baseUrl
        core.cloudSetServerUrl(baseUrl)
        return baseUrl
    }

    private fun clearSessionKeys() {
        core.kvDelete(KV_SESSION)
        core.kvDelete(KV_SESSION_HOST)
        core.kvDelete(KV_USER)
    }

    private fun normalizeServerUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        return if (normalized.isBlank()) platformOrigin() else normalized
    }

    /** Kotlin-only error channel: set on failure, cleared on success. */
    private suspend fun <T> request(block: suspend () -> T): T =
        withContext(ioDispatcher) {
            try {
                block().also { _syncError.value = null }
            } catch (error: Throwable) {
                _syncError.value = error.message ?: "Cloud synchronization failed"
                logW(TAG, "Cloud request failed: ${error.message}")
                throw error
            }
        }

    private companion object {
        const val TAG = "RustCloudFacade"
        const val BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        const val KV_SESSION = "cloud_session"
        const val KV_SESSION_HOST = "cloud_session_host"
        const val KV_USER = "cloud_user_json"
    }
}


expect fun platformOrigin(): String

/**
 * File bytes for the avatar upload. Android/Desktop read the file; the
 * browser has no filesystem, and its avatars arrive as `data:` URIs (handled
 * before this is reached).
 */
internal expect suspend fun readBytesFromPath(path: String): ByteArray
