/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.local.room.CloudSyncDao
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.RoomCloudSyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Isolated Room Cloud composition. This is not installed in the app while remaining CloudFacade
 * features and lifecycle integration are incomplete.
 */
internal class RoomCloudCoordinator(
    private val db: LocalDatabase,
    private val transport: RoomCloudHttpClient,
    private val serverUrl: String,
    private val avatars: RoomAvatarCache,
    private val images: CloudImageRehydrator,
) {
    private val dao: CloudSyncDao get() = db.cloudSync()
    private val mutex = Mutex()
    internal val providerGate = RoomCloudProviderGate.forDao(dao)
    private val auth = RoomCloudAuthentication(dao, transport, providerGate)
    private var active: StoredCloudSession? = null
    private val mutableUser = MutableStateFlow<CloudUser?>(null)
    val user: StateFlow<CloudUser?> = mutableUser
    private val mutableAvatar = MutableStateFlow<String?>(null)
    val userAvatar: StateFlow<String?> = mutableAvatar
    val sessionExpired: StateFlow<Boolean> = auth.sessionExpired
    private val mutableSyncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = mutableSyncError
    private val mutableReplacementProgress = MutableStateFlow<CloudReplacementProgress?>(null)
    val replacementProgress: StateFlow<CloudReplacementProgress?> = mutableReplacementProgress
    fun acknowledgeSessionExpired() = auth.acknowledgeSessionExpired()

    suspend fun restore(): CloudUser? = mutex.withLock {
        active = auth.restore(serverUrl)
        mutableUser.value = active?.user
        mutableAvatar.value = active?.let { avatars.cached(serverUrl, it.session, "user", null, it.user.avatarUrl, it.user.avatarVersion) }
        active?.user
    }
    suspend fun login(email: String, password: String, register: Boolean = false): CloudUser = mutex.withLock {
        val current = auth.login(serverUrl, email, password, register)
        active = current
        mutableUser.value = current.user
        cloudOperation(current) {
        dao.updateCloudUser(current.session, current.user, serverUrl)
        val avatar = avatars.get(api(current), current.session, "user", null, current.user.avatarUrl, current.user.avatarVersion)
        if (dao.currentSession() != current.session) {
            active = null
            mutableUser.value = null
            mutableAvatar.value = null
            error("Cloud session changed during avatar download")
        }
        mutableAvatar.value = avatar
        current.user
        }
    }
    suspend fun logout() = mutex.withLock {
        val current = active
        if (current != null) cloudOperation(current) { auth.logout(serverUrl) } else auth.logout(serverUrl)
        active = null
        mutableUser.value = null
        mutableAvatar.value = null
        mutableSyncError.value = null
        mutableReplacementProgress.value = null
    }
    /** Change of server clears only the current local session; pending dataset remains bound. */
    suspend fun disconnect() = mutex.withLock {
        active?.let { current -> cloudOperation(current) { auth.forgetIfCurrent(current.session) } }
        active = null
        mutableUser.value = null
        mutableAvatar.value = null
        mutableSyncError.value = null
        mutableReplacementProgress.value = null
    }
    private suspend fun expire(session: SyncSession) {
        auth.expireIfCurrent(session)
        if (active?.session == session && dao.currentSession() != session) {
            active = null
            mutableUser.value = null
            mutableAvatar.value = null
            mutableReplacementProgress.value = null
        }
    }
    internal suspend fun rejectSession(session: SyncSession) = mutex.withLock { expire(session) }
    private fun bound(): StoredCloudSession = checkNotNull(active) { "Please sign in first" }
    private fun api(current: StoredCloudSession) = RoomCloudApi(transport, serverUrl, current, ::expire)
    private suspend fun <T> cloudOperation(current: StoredCloudSession, block: suspend () -> T): T {
        val token = dao.beginCloudOperation(current.session)
        var failure: Throwable? = null
        try { return block() }
        catch (error: Throwable) { failure = error; throw error }
        finally {
            try { withContext(NonCancellable) { dao.finishCloudOperation(current.session, token) } }
            catch (release: Throwable) { if (failure != null) failure.addSuppressed(release) else throw release }
        }
    }
    internal suspend fun <T> withSession(block: suspend (RoomCloudApi, StoredCloudSession) -> T): T = mutex.withLock {
        val current = bound()
        check(dao.currentSession() == current.session) { "Cloud session changed" }
        cloudOperation(current) { block(api(current), current) }
    }
    suspend fun refreshUser(): CloudUser = mutex.withLock {
        val current = bound()
        cloudOperation(current) {
        val fresh = api(current).refreshUser()
        val avatar = avatars.get(api(current), current.session, "user", null, fresh.avatarUrl, fresh.avatarVersion)
        dao.updateCloudUser(current.session, fresh, serverUrl)
        val refreshed = current.copy(user = fresh)
        active = refreshed
        mutableUser.value = fresh
        mutableAvatar.value = avatar
        fresh
        }
    }
    suspend fun sync(replaceLocal: Boolean = false, pushPending: Boolean = false): CloudSyncResult = mutex.withLock {
        val current = bound()
        cloudOperation(current) { syncLocked(current, replaceLocal, pushPending) }
    }
    private suspend fun syncLocked(current: StoredCloudSession, replaceLocal: Boolean, pushPending: Boolean): CloudSyncResult {
        check(dao.currentSession() == current.session) { "Cloud session changed" }
        val store = RoomCloudSyncStore(dao, ::expire, images)
        val syncTransport = KtorSyncTransport(transport.http, serverUrl, current.session, current.cookie)
        try {
            val result = KotlinCloudSyncEngine(store, syncTransport).sync(replaceLocal, pushPending)
            mutableSyncError.value = null
            return CloudSyncResult(result.latestVersion,
                result.documents[SyncCollection.AGENT].orEmpty().size,
                result.documents[SyncCollection.CONVERSATION].orEmpty().size,
                result.documents[SyncCollection.PROVIDER].orEmpty().size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableSyncError.value = error.message ?: "Cloud synchronization failed"
            throw error
        }
    }
    suspend fun pushPending() = mutex.withLock {
        val current = bound()
        cloudOperation(current) {
        check(dao.currentSession() == current.session) { "Cloud session changed" }
        val store = RoomCloudSyncStore(dao, ::expire, images)
        val syncTransport = KtorSyncTransport(transport.http, serverUrl, current.session, current.cookie)
        try {
            dao.beginAutomaticPush(current.session)
            KotlinCloudSyncEngine(store, syncTransport).pushPending()
            dao.finishPush(current.session)
            mutableSyncError.value = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableSyncError.value = error.message ?: "Cloud synchronization failed"; throw error }
        }
    }
    suspend fun finishLoginChoice() = mutex.withLock { dao.finishLoginChoice(bound().session) }
    suspend fun finishExplicitPush() = mutex.withLock { dao.finishPush(bound().session) }

    suspend fun upload(): CloudSyncResult = mutex.withLock {
        val current = bound()
        cloudOperation(current) {
            dao.enqueueFullSnapshot(current.session)
            dao.beginExplicitPush(current.session)
            syncLocked(current, replaceLocal = false, pushPending = true)
        }
    }

    suspend fun planReplacement(): CloudReplacementPlan = mutex.withLock {
        val current = bound()
        RoomCloudReplacement(db, api(current), KtorSyncTransport(transport.http, serverUrl, current.session, current.cookie), current.session, images, ::expire).plan()
    }
    suspend fun replaceCloud(plan: CloudReplacementPlan): CloudSyncResult = mutex.withLock {
        val current = bound()
        mutableReplacementProgress.value = null
        try {
            val result = RoomCloudReplacement(db, api(current), KtorSyncTransport(transport.http, serverUrl, current.session, current.cookie), current.session, images, ::expire)
                .execute(plan) { mutableReplacementProgress.value = it }
            mutableSyncError.value = null
            result
        } catch (cancelled: CancellationException) {
            if (mutableReplacementProgress.value?.let { it.outcomeMayBeUnknown || it.completedDeletions.isNotEmpty() || it.localPrepared } == true) {
                mutableSyncError.value = "Cloud replacement was cancelled; changes may be partial or have an unknown outcome"
            }
            throw cancelled
        } catch (error: Exception) {
            mutableSyncError.value = error.message ?: "Cloud replacement failed; changes may be partial"
            throw error
        }
    }
    suspend fun syncBuiltinModels(force: Boolean = false): Int = mutex.withLock {
        val current = bound()
        cloudOperation(current) { RoomBuiltinModels(dao, api(current), current.session).sync(force) }
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) = withSession { api, _ -> api.changePassword(currentPassword, newPassword) }
    suspend fun deleteAccount(currentPassword: String) = mutex.withLock {
        val current = bound()
        cloudOperation(current) {
        api(current).deleteAccount(currentPassword)
        if (auth.forgetIfCurrent(current.session)) {
            active = null; mutableUser.value = null; mutableAvatar.value = null; mutableSyncError.value = null
        }
        }
    }
    suspend fun registerPushDevice(registrationId: String) = withSession { api, _ -> api.registerPushDevice(registrationId) }
    suspend fun sendPushEvent(event: String, title: String?, targetKind: String?, conversationId: String?, deviceId: String?) = withSession { api, _ ->
        api.sendPushEvent(kotlinx.serialization.json.buildJsonObject {
            put("event", kotlinx.serialization.json.JsonPrimitive(event))
            title?.let { put("title", kotlinx.serialization.json.JsonPrimitive(it)) }
            targetKind?.let { kind ->
                require(kind in setOf("local", "remote") && conversationId?.matches(Regex("[A-Za-z0-9_:-]{1,200}")) == true) { "Invalid push target" }
                if (kind == "remote") require(deviceId?.matches(Regex("[A-Za-z0-9_-]{1,64}")) == true) { "Invalid push device" }
                put("target", kotlinx.serialization.json.buildJsonObject {
                    put("kind", kotlinx.serialization.json.JsonPrimitive(kind))
                    put("conversationId", kotlinx.serialization.json.JsonPrimitive(requireNotNull(conversationId)))
                    if (kind == "remote") put("deviceId", kotlinx.serialization.json.JsonPrimitive(requireNotNull(deviceId)))
                })
            }
        })
    }
    suspend fun previewCard(code: String): CloudCardPreview = withSession { api, _ -> api.previewCard(code) }
    suspend fun redeemCard(code: String): CloudRedeemResponse = withSession { api, _ -> api.redeemCard(code) }
    suspend fun avatar(agentId: String?, filename: String?, bytes: ByteArray?, mime: String?): CloudAvatarResponse =
        withSession { api, _ -> api.avatar(agentId, filename, bytes, mime) }
    suspend fun listMarket(query: String, cursor: String?): CloudMarketAgentListResponse = withSession { api, _ -> api.listMarket(query, cursor) }
    suspend fun marketAgent(id: String): CloudMarketAgent = withSession { api, _ -> api.marketAgent(id) }
    suspend fun publishMarket(id: String): CloudMarketAgent = withSession { api, current -> RoomCloudMarket(dao, api, current.session, avatars).publish(id) }
    suspend fun updatePublishedMarket(id: String): CloudMarketAgent = withSession { api, current -> RoomCloudMarket(dao, api, current.session, avatars).updatePublished(id) }
    suspend fun removeMarket(id: String) = withSession { api, current -> RoomCloudMarket(dao, api, current.session, avatars).removePublished(id) }
    suspend fun importMarket(id: String) = withSession { api, current -> RoomCloudMarket(dao, api, current.session, avatars).import(id) }
    suspend fun checkMarketUpdate(id: String) = withSession { api, current -> RoomCloudMarket(dao, api, current.session, avatars).checkUpdate(id) }
    suspend fun applyMarketUpdate(id: String, market: CloudMarketAgent) = withSession { api, current -> RoomCloudMarket(dao, api, current.session, avatars).applyUpdate(id, market) }
}
