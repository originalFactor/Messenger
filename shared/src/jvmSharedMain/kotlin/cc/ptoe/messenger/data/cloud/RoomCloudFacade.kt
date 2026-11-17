/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.repository.room.domain
import cc.ptoe.messenger.data.repository.room.row
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.repository.CloudFacade
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.util.AttributeKey
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Unconnected migration facade. All local writers must atomically enqueue changes in Room. */
internal class RoomCloudFacade private constructor(
    private val db: LocalDatabase,
    private val preferences: AppPreferences,
    private val transport: RoomCloudHttpClient,
    private val avatars: RoomAvatarCache,
    private val images: CloudImageRehydrator,
    initialBase: String,
    initial: RoomCloudCoordinator,
    allowAutoSync: Boolean,
    initialChoicePending: Boolean,
    initialPushUncertain: Boolean,
) : CloudFacade, AutoCloseable {
    private val operation = Mutex()
    private val scheduleLock = Any()
    private val remoteLock = Any()
    private val resourcesLock = Any()
    private val remoteJobs = mutableSetOf<Job>()
    private var avatarClientClosed = false
    private var transportClosed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scheduled: Job? = null
    @Volatile private var autoSyncEnabled = allowAutoSync
    private var pushing = false
    private var pendingAfterPush = false
    private var pendingAfterRecovery = false
    private var explicitUploadInProgress = false
    private var autoSyncBlocked = initialPushUncertain
    @Volatile private var coordinator = initial
    /** Supply this same gate to any isolated Room Kotlin turn store owned by this facade. */
    internal val providerGate: RoomCloudProviderGate get() = coordinator.providerGate
    @Volatile private var base = initialBase
    @Volatile private var closed = false
    private var remote: RoomCloudRemoteControl? = null
    private val userState = MutableStateFlow(initial.user.value)
    private val serverState = MutableStateFlow(initialBase)
    private val errorState = MutableStateFlow<String?>(when {
        initialChoicePending -> "Complete the cloud data choice before syncing"
        initialPushUncertain -> "A previous cloud push may have completed; retry explicitly"
        else -> null
    })
    private val expiryState = MutableStateFlow(initial.sessionExpired.value)
    override val user: Flow<CloudUser?> = userState.asStateFlow()
    override val serverUrl: Flow<String> = serverState.asStateFlow()
    override val syncError: StateFlow<String?> = errorState.asStateFlow()
    override val sessionExpired: StateFlow<Boolean> = expiryState.asStateFlow()
    override fun acknowledgeSessionExpired() { coordinator.acknowledgeSessionExpired(); expiryState.value = false }

    private data class AvatarAuthorization(val coordinator: RoomCloudCoordinator, val session: SyncSession)
    private val avatarAuthorization = AttributeKey<AvatarAuthorization>("room-cloud-avatar-session")
    private val avatarAdmission = AttributeKey<AvatarCallTracker.Admission>("room-cloud-avatar-admission")
    private val avatarTracker = AvatarCallTracker()
    private val avatarPlugin = createClientPlugin("RoomCloudAvatarAuthorization") {
        onRequest { request, _ ->
            val admission = avatarTracker.admit(request.executionContext)
            request.attributes.put(avatarAdmission, admission)
            try { avatarTracker.callback {
            val selected = coordinator
            val selectedBase = base
            if (isCloudAvatarUrl(selectedBase, request.url.buildString())) {
                val current = try { selected.withSession { _, session -> session } }
                    catch (error: IllegalStateException) {
                        if (selected.user.value != null) throw error
                        null
                    }
                if (current != null && selected === coordinator && selectedBase == base) {
                    request.headers.append(HttpHeaders.Cookie, current.cookie)
                    request.attributes.put(avatarAuthorization, AvatarAuthorization(selected, current.session))
                }
            }
            } } catch (error: Throwable) { avatarTracker.finish(admission); throw error }
        }
        onResponse { response ->
            avatarTracker.callback {
            val binding = response.call.request.attributes.getOrNull(avatarAuthorization)
            if (response.status.value == 401 && binding != null) {
                binding.coordinator.rejectSession(binding.session)
                if (binding.coordinator === coordinator) updateStates(binding.coordinator)
            }
            }
        }
    }
    override val avatarHttpClient: HttpClient = createRoomCloudHttpClient {
        install(avatarPlugin)
    }.also { client ->
        client.plugin(HttpSend).intercept { request ->
            val admission = request.attributes[avatarAdmission]
            try {
                avatarTracker.checkOpen()
                execute(request).also { call ->
                    avatarTracker.bind(admission, checkNotNull(call.coroutineContext[Job]))
                }
            } catch (error: Throwable) { avatarTracker.finish(admission); throw error }
        }
    }

    companion object {
        /** Restore is explicit and completed before the facade is exposed to a UI. */
        suspend fun open(db: LocalDatabase, preferences: AppPreferences, transport: RoomCloudHttpClient,
            avatars: RoomAvatarCache, images: CloudImageRehydrator, defaultServer: String = defaultCloudServerUrl,
            onCreated: (suspend (RoomCloudFacade) -> Unit)? = null): RoomCloudFacade {
            val selected = canonicalCloudBase(db.rows().getValue("cloud_server_url")?.value ?: defaultServer)
            val coordinator = RoomCloudCoordinator(db, transport, selected, avatars, images)
            coordinator.restore()
            val choicePending = db.cloudSync().loginChoicePending()
            val pushUncertain = db.cloudSync().automaticPushUncertain()
            val facade = RoomCloudFacade(db, preferences, transport, avatars, images, selected, coordinator,
                coordinator.user.value != null && !choicePending && !pushUncertain, choicePending, pushUncertain)
            var cleanupRegistered = false
            try {
                // Register the cleanup handle before further initialization can fail.
                onCreated?.let { it(facade); cleanupRegistered = true }
                coordinator.user.value?.let { facade.syncAvatarPreference(coordinator, it) }
                return facade
            } catch (failure: Throwable) {
                if (!cleanupRegistered) {
                    try { facade.shutdown() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
        }
    }

    private fun updateStates(source: RoomCloudCoordinator) {
        if (source !== coordinator) return
        userState.value = source.user.value
        expiryState.value = source.sessionExpired.value
    }

    /** A relay 401 may only expire the exact session that owned that connection. */
    internal suspend fun rejectDeviceHostSession(owner: LocalDatabase, expected: StoredCloudSession) = withContext(NonCancellable) {
        check(owner === db) { "Device host belongs to another Room owner" }
        providerGate.invalidate(expected.session)
        operation.withLock {
            check(!closed) { "Cloud facade is closed" }
            if (!RoomDeviceHostAccessSource(db) { it }.stillCurrent(expected)) return@withLock
            val selected = coordinator
            try { selected.rejectSession(expected.session) } finally { updateStates(selected) }
        }
    }

    internal fun requireDeviceHostOwner(owner: LocalDatabase) {
        check(owner === db) { "Device host belongs to another Room owner" }
    }
    private suspend fun <T> request(block: suspend (RoomCloudCoordinator) -> T): T = operation.withLock {
        check(!closed) { "Cloud facade is closed" }
        val selected = coordinator
        try {
            block(selected).also {
                if (!synchronized(scheduleLock) { autoSyncBlocked }) errorState.value = null
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { errorState.value = failure.message ?: "Cloud request failed"; throw failure }
        finally { updateStates(coordinator) }
    }

    private suspend fun switchLocked(url: String) {
        val target = canonicalCloudBase(url)
        if (target == base) return
        closeRemote()
        coordinator.disconnect()
        db.rows().upsertValues(listOf(KeyValueRow("cloud_server_url", target)))
        clearCloudAvatarPreference()
        val next = RoomCloudCoordinator(db, transport, target, avatars, images)
        next.restore()
        base = target
        coordinator = next
        serverState.value = target
        updateStates(next)
    }
    override suspend fun setServerUrl(url: String) {
        if (canonicalCloudBase(url) == base) return
        cancelPendingLocalSync()
        request { switchLocked(url) }
    }

    private suspend fun hasLocalData(): Boolean = db.useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val dao = db.cloudSync()
            val agents = dao.syncAgentIds().any { id ->
                val a = requireNotNull(dao.agentRow(id))
                val seed = a.isDefault && a.role == Agent.ROLE_CHAT && a.name in setOf("默认 Agent", "Default Agent", "Default") &&
                    a.description == Agent.DEFAULT_AGENT_DESCRIPTION && a.systemPrompt.trim() == Agent.DEFAULT_AGENT_PROMPT.trim() &&
                    a.avatar == null && a.defaultModelId == null && a.temperature?.toFloat() == 0.7f && a.topP?.toFloat() == 1f &&
                    a.maxTokens == null && a.reasoningEffort == null && !a.followDefaultModel && !a.followDefaultSystemPrompt &&
                    !a.followDefaultTemperature && !a.followDefaultTopP && !a.followDefaultMaxTokens && !a.followDefaultReasoningEffort &&
                    a.marketAgentId == null && a.marketAgentVersion == null && a.marketAgentRole == null &&
                    a.toolsEnabled && !a.toolsFollowDefault && a.toolsConfig in setOf("", "{}")
                !seed
            }
            agents || dao.syncProviderIds().isNotEmpty() || dao.syncProjectIds().isNotEmpty() || dao.syncConversationIds().isNotEmpty()
        }
    }
    private suspend fun signin(email: String, password: String, selected: String?, register: Boolean): CloudLoginOutcome {
        cancelPendingLocalSync()
        return request {
            if (selected != null) switchLocked(selected)
            closeRemote()
            val current = coordinator
            val user = current.login(email, password, register)
            syncAvatarPreference(current, user)
            CloudLoginOutcome(user, hasLocalData(), user.syncVersion)
        }
    }
    override suspend fun login(email: String, password: String, serverUrl: String?) = signin(email, password, serverUrl, false)
    override suspend fun register(email: String, password: String, serverUrl: String?) = signin(email, password, serverUrl, true)
    override suspend fun pendingLoginChoice(): CloudLoginOutcome? = operation.withLock {
        check(!closed) { "Cloud facade is closed" }
        if (!db.cloudSync().loginChoicePending()) return@withLock null
        val user = checkNotNull(coordinator.user.value) { "Cloud login choice has no active session" }
        CloudLoginOutcome(user, hasLocalData(), user.syncVersion)
    }
    override suspend fun refreshUser(): CloudUser = request { current ->
        val user = current.refreshUser()
        syncAvatarPreference(current, user)
        // The account refresh has already committed. Surface a catalog failure to
        // the caller and syncError instead of reporting the model refresh as successful.
        current.syncBuiltinModels(force = true)
        user
    }
    internal suspend fun syncBuiltinModels(force: Boolean): Long =
        request { it.syncBuiltinModels(force).toLong() }

    override suspend fun logout() {
        cancelPendingLocalSync()
        request { current -> closeRemote(); current.logout(); clearCloudAvatarPreference() }
    }
    override suspend fun changePassword(currentPassword: String, newPassword: String) = request { it.changePassword(currentPassword, newPassword) }
    override suspend fun deleteAccount(currentPassword: String) {
        cancelPendingLocalSync()
        request { current -> closeRemote(); current.deleteAccount(currentPassword); clearCloudAvatarPreference() }
    }
    override suspend fun registerPushDevice(registrationId: String) = request { it.registerPushDevice(registrationId) }
    override suspend fun sendPushEvent(event: String, title: String?, targetKind: String?, conversationId: String?, deviceId: String?) =
        request { it.sendPushEvent(event, title, targetKind, conversationId, deviceId) }
    override suspend fun previewRedeemCard(code: String) = request { it.previewCard(code) }
    override suspend fun redeemCard(code: String) = request { it.redeemCard(code) }
    override suspend fun listMarketAgents(query: String, cursor: String?) = request { it.listMarket(query, cursor) }
    override suspend fun marketAgent(id: String) = request { it.marketAgent(id) }
    override suspend fun publishMarketAgent(agentId: String) = request { it.publishMarket(agentId) }
    override suspend fun pushMarketAgentUpdate(agentId: String) = request { it.updatePublishedMarket(agentId) }
    override suspend fun removeMarketAgent(agentId: String) = request { it.removeMarket(agentId) }
    override suspend fun importMarketAgent(marketId: String) = request { it.importMarket(marketId).domain() }
    override suspend fun checkMarketAgentUpdate(agentId: String) = request { it.checkMarketUpdate(agentId) }
    override suspend fun applyMarketAgentUpdate(agentId: String, market: CloudMarketAgent) = request { it.applyMarketUpdate(agentId, market).domain() }
    private suspend fun requireLoginChoice() { check(!db.cloudSync().loginChoicePending()) { "Complete the cloud data choice before syncing" } }
    private suspend fun enableAutoSync() {
        val uncertain = db.cloudSync().automaticPushUncertain()
        val reschedule = synchronized(scheduleLock) {
            autoSyncBlocked = uncertain
            autoSyncEnabled = !uncertain
            (!uncertain && pendingAfterRecovery).also { if (it) pendingAfterRecovery = false }
        }
        if (reschedule) requestLocalSync()
    }
    override suspend fun sync() = request { requireLoginChoice(); it.sync().also { _ -> enableAutoSync() } }
    override suspend fun upload() = request {
        requireLoginChoice()
        synchronized(scheduleLock) { explicitUploadInProgress = true }
        try { it.upload().also { _ -> it.finishExplicitPush(); enableAutoSync() } }
        finally { synchronized(scheduleLock) { explicitUploadInProgress = false } }
    }
    override suspend fun completeLogin(useLocalData: Boolean) = request {
        check(db.cloudSync().loginChoicePending()) { "No cloud data choice is pending" }
        val result = if (useLocalData) it.replaceCloud(it.planReplacement()) else it.sync(replaceLocal = true)
        it.finishLoginChoice()
        if (useLocalData) it.finishExplicitPush()
        enableAutoSync()
        result
    }
    override suspend fun downloadAndRestore() = request { requireLoginChoice(); it.sync(replaceLocal = true).also { enableAutoSync() } }

    override suspend fun remoteControlCommand(requestId: String, deviceId: String, command: JsonObject, onEvent: (JsonObject) -> Unit) = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val selected = operation.withLock {
            check(!closed) { "Cloud facade is closed" }
            val current = coordinator
            current.withSession { _, session -> synchronized(remoteLock) {
                check(!closed) { "Cloud facade is closed" }
                val controller = remote ?: RoomCloudRemoteControl(session, { db.cloudSync().currentSession() == it }, {
                    current.rejectSession(it)
                    updateStates(current)
                }).also { remote = it }
                remoteJobs.add(job)
                controller
            } }
        }
        try { selected.command(requestId, deviceId, command, onEvent); errorState.value = null }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { errorState.value = failure.message ?: "Remote control failed"; throw failure }
        finally { synchronized(remoteLock) { remoteJobs.remove(job) } }
    }
    private fun closeRemote() = synchronized(remoteLock) { remote?.close(); remote = null }

    override fun requestLocalSync(): Unit = synchronized(scheduleLock) {
        if (closed || userState.value == null) return@synchronized
        if (autoSyncBlocked) {
            if (explicitUploadInProgress) pendingAfterRecovery = true
            return@synchronized
        }
        if (!autoSyncEnabled) return@synchronized
        if (pushing) { pendingAfterPush = true; return@synchronized }
        scheduled?.cancel()
        scheduled = scope.launch {
            delay(750)
            synchronized(scheduleLock) {
                if (closed || !autoSyncEnabled || autoSyncBlocked) return@launch
                pushing = true
            }
            var success = false
            try { request { requireLoginChoice(); it.pushPending() }; success = true }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { synchronized(scheduleLock) { autoSyncBlocked = true } }
            finally {
                val thisJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                val again = synchronized(scheduleLock) {
                    pushing = false
                    val retry = success && pendingAfterPush && autoSyncEnabled && !autoSyncBlocked
                    pendingAfterPush = false
                    if (scheduled == thisJob) scheduled = null
                    retry
                }
                if (again) requestLocalSync()
            }
        }
    }
    override suspend fun cancelPendingLocalSync() {
        val job = synchronized(scheduleLock) {
            autoSyncEnabled = false; pendingAfterPush = false; pendingAfterRecovery = false
            scheduled.also { scheduled = null }
        }
        job?.cancelAndJoin()
    }
    /** Room repositories enqueue changes transactionally; callbacks only schedule delivery. */
    override fun requestLocalChange(type: String, id: String, deleted: Boolean) { requestLocalSync() }
    override fun requestAgentAvatarChange(previous: Agent?, current: Agent?) {
        val id = current?.id ?: previous?.id ?: return
        if (previous?.avatar == current?.avatar || current?.avatar != null) return
        scope.launch {
            try { request { it.avatar(id, null, null, null) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* request publishes the failure to syncError. */ }
        }
    }

    private suspend fun clearCloudAvatarPreference() {
        if (preferences.userAvatarSyncKey.first() != null) {
            preferences.setUserAvatar(null)
            preferences.setUserAvatarSyncKey(null)
        }
    }
    private suspend fun syncAvatarPreference(current: RoomCloudCoordinator, user: CloudUser, keep: String? = null) {
        val url = user.avatarUrl?.takeIf { it.isNotBlank() }
        if (url == null) { clearCloudAvatarPreference(); return }
        val key = "$base|${user.id}|$url#${user.avatarVersion ?: 0}"
        val path = keep ?: current.userAvatar.value
        if (path == null) {
            if (preferences.userAvatarSyncKey.first() != key) clearCloudAvatarPreference()
            return
        }
        preferences.setUserAvatar(path)
        preferences.setUserAvatarSyncKey(key)
    }
    override suspend fun uploadUserAvatar(path: String?): CloudAvatarResponse = request { current ->
        val source = path?.takeIf(String::isNotBlank)
        val input = source?.let(::readAvatar)
        val response = current.avatar(null, input?.first, input?.second, input?.third)
        val user = current.refreshUser()
        syncAvatarPreference(current, user, source)
        response
    }
    override suspend fun uploadAgentAvatar(agentId: String, path: String?): CloudAvatarResponse = request { current ->
        val input = path?.takeIf(String::isNotBlank)?.let(::readAvatar)
        current.avatar(agentId, input?.first, input?.second, input?.third)
    }
    override suspend fun ensureBuiltinTitleAgent() {
        request {
            db.useWriterConnection { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val dao = db.cloudSync()
                    check(dao.value(cc.ptoe.messenger.data.local.room.CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
                    if (dao.titleCount() == 0 && dao.agentRow(Agent.BUILTIN_TITLE_AGENT_ID) == null) {
                        dao.saveAgent(Agent.builtinTitleAgent(currentTimeMillis()).row())
                    }
                }
            }
        }
    }
    private fun beginShutdown() {
        avatarTracker.stop()
        synchronized(remoteLock) {
            closed = true
            remote?.close()
            remote = null
        }
        synchronized(scheduleLock) { scheduled?.cancel(); scheduled = null }
        scope.cancel()
    }
    private fun closeResources() = synchronized(resourcesLock) {
        if (!avatarClientClosed) {
            avatarHttpClient.close()
            avatarClientClosed = true
        }
        if (!transportClosed) {
            transport.close()
            transportClosed = true
        }
    }
    override fun close() {
        beginShutdown()
        closeResources()
    }

    /**
     * Prevent new facade work, cancel avatar calls, internal jobs and remote calls, and wait for
     * admitted session callbacks and operations before the Room owner closes the database.
     * The host must not call this from an admitted callback.
     */
    suspend fun shutdown() = withContext(NonCancellable) {
        beginShutdown()
        coordinator.providerGate.shutdown()
        avatarTracker.awaitStopped()
        scope.coroutineContext[Job]?.join()
        operation.withLock { }
        synchronized(remoteLock) { remoteJobs.toList() }.joinAll()
        closeResources()
    }
}

/** The cookie is attached only to the configured server's known avatar routes. */
internal fun isCloudAvatarUrl(base: String, value: String): Boolean = try {
    val root = URI(canonicalCloudBase(base))
    val target = URI(value)
    fun port(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    if (!target.scheme.equals(root.scheme, true) || !target.host.equals(root.host, true) || port(target) != port(root) || target.rawUserInfo != null || target.rawFragment != null) false
    else {
        val prefix = root.rawPath.trimEnd('/')
        val path = target.rawPath ?: ""
        val relative = if (path.startsWith("$prefix/")) path.removePrefix(prefix) else ""
        relative == "/api/avatars/user" || Regex("^/api/avatars/agents/[A-Za-z0-9_-]{1,128}$").matches(relative) ||
            Regex("^/api/market/agents/[A-Za-z0-9_-]{1,128}/avatar$").matches(relative)
    }
} catch (_: Exception) { false }

/** Input comes from a user-selected local path or data URI and is read with a strict size cap. */
private fun readAvatar(source: String): Triple<String, ByteArray, String> {
    val maxBytes = 5 * 1024 * 1024
    val (bytes, mime) = if (source.startsWith("data:")) {
        val comma = source.indexOf(',')
        require(comma in 1..100 && source.length <= ((maxBytes + 2) / 3) * 4 + 100) { "Invalid avatar data URI" }
        val mime = source.substring(5, comma).lowercase().removeSuffix(";base64")
        require(source.substring(5, comma).lowercase() == "$mime;base64") { "Invalid avatar data URI" }
        Base64.getDecoder().decode(source.substring(comma + 1)) to mime
    } else {
        val path = Paths.get(source)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Invalid avatar file" }
        val mime = when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"
            else -> error("Unsupported avatar type")
        }
        Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val result = java.io.ByteArrayOutputStream()
            val input = java.nio.channels.Channels.newInputStream(channel)
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(result.size() <= maxBytes - count) { "Avatar exceeds size limit" }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        } to mime
    }
    require(bytes.isNotEmpty() && bytes.size <= maxBytes && mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) { "Invalid avatar" }
    val extension = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; else -> "gif" }
    return Triple("avatar.$extension", bytes, mime)
}
