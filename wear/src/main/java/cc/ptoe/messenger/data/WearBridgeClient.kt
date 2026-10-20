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

package cc.ptoe.messenger.data

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Watch-side bridge to the phone. Replaces the previous DataLayer-based
 * implementation with a WebSocket transport (see [WearNetworkBridge]) plus
 * a polling loop, so the watch keeps in sync even on Samsung China-region
 * watches where Google Play Services for Wear OS is missing.
 *
 * The transport uses the watch's existing tether / WiFi network: the phone
 * advertises a `_messenger._tcp` NSD service, the watch finds it via mDNS,
 * opens a WebSocket, and both sides speak the same line-delimited JSON
 * protocol as before.
 *
 * Public surface: [syncUpdates] / [chatFrames] / [newChatResponses] flows
 * and the [requestChat] / [requestNewConversation] methods. Chat is now
 * streaming (see [chatFrames]); the other request types are still
 * single request / single response.
 */
class WearBridgeClient(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val networkBridge = WearNetworkBridge(context, scope)

    private val _syncUpdates = MutableSharedFlow<WearSyncSnapshot>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val syncUpdates: SharedFlow<WearSyncSnapshot> = _syncUpdates.asSharedFlow()

    /**
     * Streaming chat frames from the phone. The repository subscribes to this
     * (filtered by requestId) and accumulates deltas until a terminal Done /
     * Error frame arrives. See [WearNetworkBridge.chatFrames].
     */
    val chatFrames: SharedFlow<WearChatFrame> = networkBridge.chatFrames

    private val _newChatResponses = MutableSharedFlow<WearNewChatResponse>(
        // Same reasoning as the old chatResponses: requestNewConversation emits
        // before the repository subscribes, and a replay of 0 would drop it
        // and leave the UI stuck on "Creating...".
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val newChatResponses: SharedFlow<WearNewChatResponse> = _newChatResponses.asSharedFlow()

    /**
     * Snapshot of the network transport's current state, surfaced to the UI
     * so the chat list can show a "waiting for phone" banner.
     */
    val connectionState: StateFlow<WearConnectionState> = networkBridge.connectionState

    /** Bumped each time the user asks for an explicit reconnect from the UI. */
    private val _reconnectRequests = MutableStateFlow(0)
    val reconnectRequests: StateFlow<Int> = _reconnectRequests.asStateFlow()

    private var syncJob: Job? = null
    private var lastUpdatedAt: Long = 0L

    // 指数退避：空闲时逐步增大轮询间隔
    private var consecutiveNoChangeCount = 0
    private var currentPollIntervalMs: Long = BASE_POLL_INTERVAL_MS

    /**
     * In-memory cache of the avatar version we have on disk for each agent
     * (and "user" for the user's own avatar). Keyed by agent id. A version
     * of 0 means "no avatar on the phone" — the watch deletes any locally
     * cached file in that case so the UI falls back to the initial letter.
     */
    private val cachedAvatarVersions = mutableMapOf<String, Long>()

    private val avatarDir = File(appContext.filesDir, "agent_avatars").apply { mkdirs() }
    private val userAvatarFile = File(appContext.filesDir, "user_avatar.jpg")

    fun start() {
        if (syncJob != null) return
        syncJob = scope.launch {
            // Race the polling loop against user-initiated reconnect requests
            // so a "Reconnect" tap forces an immediate retry.
            val reconnectTrigger = _reconnectRequests
            while (true) {
                val targetTick = reconnectTrigger.value
                if (networkBridge.connect()) {
                    // Always do an initial fetch so the UI doesn't sit on the
                    // empty default state while waiting for the next tick.
                    requestSync()
                    while (networkBridge.isConnected.value) {
                        // Bail out of the inner loop early if the user asked
                        // to reconnect — we want to close the socket and try
                        // again straight away.
                        if (reconnectTrigger.value != targetTick) break
                        delay(currentPollIntervalMs)  // 使用动态间隔
                        if (reconnectTrigger.value != targetTick) break
                        requestSync()
                    }
                } else {
                    Log.d(TAG, "Phone not reachable via network, will retry")
                    // Wait either for the next reconnect tick or the normal
                    // backoff, whichever comes first.
                    val deadline = System.currentTimeMillis() + RECONNECT_DELAY_MS
                    while (System.currentTimeMillis() < deadline &&
                        reconnectTrigger.value == targetTick
                    ) {
                        delay(250L)
                    }
                }
                if (reconnectTrigger.value != targetTick) {
                    Log.d(TAG, "Reconnect requested — closing socket and retrying")
                    runCatching { networkBridge.close() }
                    // 重连时重置轮询间隔
                    currentPollIntervalMs = BASE_POLL_INTERVAL_MS
                    consecutiveNoChangeCount = 0
                }
            }
        }
    }

    /** Drops the active socket so the next [connect] starts fresh. */
    fun close() {
        runCatching { networkBridge.close() }
    }

    fun loadExistingState() {
        // The polling loop in start() already performs the initial fetch, so
        // this is a no-op kept for API compatibility with [WearChatRepository].
        start()
    }

    /**
     * Drops the current connection and asks the polling loop to retry
     * immediately on its next iteration. Use this from the UI when the user
     * taps a "Reconnect" button.
     */
    fun requestReconnect() {
        _reconnectRequests.value = _reconnectRequests.value + 1
    }

    private suspend fun requestSync() {
        val response = networkBridge.requestSync() ?: return
        if (response.optString("type") != "sync_response") return
        val updatedAt = response.optLong("updatedAt", 0L)

        // 检查是否有数据变化
        val hasChanges = lastUpdatedAt == 0L || updatedAt > lastUpdatedAt

        if (hasChanges) {
            // 有变化：更新 lastUpdatedAt，重置退避计数
            lastUpdatedAt = updatedAt
            consecutiveNoChangeCount = 0
            currentPollIntervalMs = BASE_POLL_INTERVAL_MS
        } else {
            // 无变化：增加退避计数，逐步增大轮询间隔
            consecutiveNoChangeCount++
            currentPollIntervalMs = minOf(
                BASE_POLL_INTERVAL_MS * (1 shl consecutiveNoChangeCount),
                MAX_POLL_INTERVAL_MS
            )
        }

        runCatching {
            val agentsArray = response.optJSONArray("agents") ?: JSONArray()
            val conversationsJson = response.optJSONArray("conversations")?.toString() ?: "[]"
            val messagesJson = response.optJSONObject("messages")?.toString() ?: "{}"

            // Pull avatar bytes only for agents whose version actually
            // changed since the last sync — keeps the (potentially large)
            // image transfer off the WebSocket on every 3s poll.
            val avatarPaths = fetchAgentAvatars(agentsArray)
            val userAvatarPath = fetchUserAvatar(response.optLong("userAvatarVersion", 0L))

            val agents = WearChatJsonCodec.decodeAgentsFromSync(
                agentsArray.toString(),
                avatarPaths
            )
            val conversations = WearChatJsonCodec.decodeConversationsFromSync(conversationsJson)
            val messages = WearChatJsonCodec.decodeMessagesFromSync(messagesJson)

            _syncUpdates.emit(
                WearSyncSnapshot(
                    agents = agents,
                    conversations = conversations,
                    messages = messages,
                    userAvatarPath = userAvatarPath,
                    updatedAt = updatedAt
                )
            )
        }.onFailure {
            Log.w(TAG, "Failed to parse sync response", it)
        }
    }

    /**
     * Walks the agents array from a sync_response and, for each agent whose
     * `avatarVersion` differs from the locally cached value, requests the
     * avatar bytes over the WebSocket and writes them to a local file. The
     * returned map points each agent id at the local file path (or null if
     * the agent has no avatar on the phone).
     */
    private suspend fun fetchAgentAvatars(agents: JSONArray): Map<String, String?> {
        if (agents.length() == 0) return emptyMap()
        val result = mutableMapOf<String, String?>()
        for (index in 0 until agents.length()) {
            val agent = agents.optJSONObject(index) ?: continue
            val id = agent.optString("id")
            if (id.isBlank()) continue
            val version = agent.optLong("avatarVersion", 0L)
            val path = syncAvatarForKey(id, version) { networkBridge.requestAvatar(it, agentId = id) }
            result[id] = path
        }
        return result
    }

    private suspend fun fetchUserAvatar(version: Long): String? =
        syncAvatarForKey(USER_AVATAR_KEY, version) { requestId ->
            networkBridge.requestAvatar(requestId, userScope = true)
        }

    /**
     * Core cache-and-fetch routine shared by agent and user avatars.
     *
     * - If [version] is 0 the phone reports no avatar; delete any cached
     *   file and return null.
     * - If [version] matches [cachedAvatarVersions] for [key] AND a cached
     *   file exists, return its path without hitting the network.
     * - Otherwise call [request] to fetch the base64 payload, decode it,
     *   write it to the cache file, and remember the new version.
     */
    private suspend fun syncAvatarForKey(
        key: String,
        version: Long,
        request: suspend (String) -> JSONObject?
    ): String? {
        if (version == 0L) {
            cachedAvatarVersions.remove(key)
            cachedAvatarFile(key)?.let { runCatching { it.delete() } }
            return null
        }
        val cachedFile = cachedAvatarFile(key)
        if (cachedAvatarVersions[key] == version && cachedFile != null && cachedFile.exists()) {
            return cachedFile.absolutePath
        }
        val requestId = "avatar-$key-${UUID.randomUUID()}"
        val response = runCatching { request(requestId) }.getOrNull() ?: return null
        if (response.optLong("version", 0L) == 0L) {
            // Phone now reports no avatar; clean up.
            cachedAvatarVersions.remove(key)
            cachedFile?.let { runCatching { it.delete() } }
            return null
        }
        val base64 = response.optString("base64").takeIf { it.isNotBlank() } ?: return null
        val bytes = runCatching { Base64.decode(base64, Base64.NO_WRAP) }.getOrNull() ?: return null
        val target = cachedAvatarFile(key) ?: return null
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            cachedAvatarVersions[key] = version
            target.absolutePath
        }.onFailure { Log.w(TAG, "Failed to persist avatar for $key", it) }
            .getOrNull()
    }

    private fun cachedAvatarFile(key: String): File? = when (key) {
        USER_AVATAR_KEY -> userAvatarFile
        else -> File(avatarDir, "$key.jpg")
    }

    /**
     * Sends a chat request to the phone. The phone then streams the reply back
     * as [chatFrames] (chat_delta* -> chat_done | chat_error), which the
     * caller is expected to subscribe to *before* calling this so no early
     * deltas are missed (the bridge buffers up to 128 recent frames anyway).
     *
     * Returns success once the request frame is queued; a failure means the
     * socket is not connected and the caller should surface a banner.
     */
    suspend fun requestChat(
        requestId: String,
        conversationId: String,
        text: String
    ): Result<Unit> {
        val sent = networkBridge.requestChat(requestId, conversationId, text)
        return if (sent) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("Phone is not connected."))
        }
    }

    suspend fun requestNewConversation(
        requestId: String,
        agentId: String?
    ): Result<Unit> {
        val response = networkBridge.requestNewConversation(requestId, agentId)
            ?: return Result.failure(IllegalStateException("Phone is not connected."))
        val error = response.optString("error").takeIf { it.isNotBlank() }
        if (error != null) {
            _newChatResponses.tryEmit(
                WearNewChatResponse(
                    requestId = requestId,
                    conversationId = null,
                    agentId = null,
                    error = error
                )
            )
            return Result.failure(IllegalStateException(error))
        }
        val conversationId = response.optString("conversationId").takeIf { it.isNotBlank() }
            ?: return Result.failure(IllegalStateException("Phone did not return a conversation."))
        _newChatResponses.tryEmit(
            WearNewChatResponse(
                requestId = requestId,
                conversationId = conversationId,
                agentId = response.optString("agentId").takeIf { it.isNotBlank() },
                error = null
            )
        )
        return Result.success(Unit)
    }

    companion object {
        private const val TAG = "WearBridgeClient"
        // 基础轮询间隔（秒级响应）
        private const val BASE_POLL_INTERVAL_MS = 3000L
        // 最大轮询间隔（空闲时退避到 60 秒）
        private const val MAX_POLL_INTERVAL_MS = 60_000L
        private const val RECONNECT_DELAY_MS = 5000L
        private const val USER_AVATAR_KEY = "user"
    }
}
