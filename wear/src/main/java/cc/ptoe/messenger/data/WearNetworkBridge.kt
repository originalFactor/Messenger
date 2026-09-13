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

package cc.ptoe.messenger.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Snapshot of the bridge's current connection state, surfaced to the UI so
 * the chat list can show a "waiting for phone" banner with the underlying
 * reason.
 */
sealed interface WearConnectionState {
    data object Disconnected : WearConnectionState
    data class Connecting(val detail: String) : WearConnectionState
    data object Connected : WearConnectionState
    data class Error(val message: String) : WearConnectionState
}

/**
 * Low-level network bridge used by [WearBridgeClient] to talk to
 * [cc.ptoe.messenger.data.wear.MobileHttpServer] on the phone.
 *
 * Transport: standard WebSocket (OkHttp) over the watch's existing
 * tether / WiFi network, no GMS required. Discovery is done via Android's
 * built-in mDNS (NsdManager) — when the watch is tethered to the phone, both
 * are on the same L2 network and the phone advertises its `_messenger._tcp`
 * service there. The watch finds the host:port, opens a WebSocket, and
 * speaks the same line-delimited JSON protocol as before.
 *
 * Request/response correlation: every request carries a `requestId` and
 * every response echoes it. The bridge keeps a `requestId -> Deferred`
 * map that the WebSocket listener dispatches into.
 */
class WearNetworkBridge(
    context: Context,
    @Suppress("unused") private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: no read timeout
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val mutex = Mutex()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var listenerJob: Job? = null

    // mDNS 发现缓存：记录上次成功发现的 endpoint，加速重连
    @Volatile private var cachedEndpoint: Endpoint? = null
    private val endpointCacheLock = Any()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _connectionState = MutableStateFlow<WearConnectionState>(
        WearConnectionState.Disconnected
    )
    val connectionState: StateFlow<WearConnectionState> = _connectionState.asStateFlow()

    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /**
     * Streaming chat frames from the phone. A chat request produces zero or
     * more [WearChatFrame.Delta] frames, then exactly one terminal
     * [WearChatFrame.Done] or [WearChatFrame.Error]. All share the
     * originating request's id.
     *
     * `extraBufferCapacity` is large so that deltas emitted in the brief window
     * between sending the chat request and the repository subscribing (and
     * during any read pause) are not lost — SharedFlow with replay=0 still
     * buffers up to `extraBufferCapacity` recent emissions for a slow/late
     * subscriber.
     */
    private val _chatFrames = MutableSharedFlow<WearChatFrame>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val chatFrames: SharedFlow<WearChatFrame> = _chatFrames.asSharedFlow()

    suspend fun connect(): Boolean = mutex.withLock {
        if (_isConnected.value) return@withLock true

        // 优先尝试缓存的 endpoint，加速重连
        val cached = synchronized(endpointCacheLock) { cachedEndpoint }
        val endpoint = if (cached != null) {
            Log.d(TAG, "Trying cached endpoint: ${cached.host}:${cached.port}")
            cached
        } else {
            discoverPhone()
        }

        if (endpoint == null) {
            _connectionState.value = WearConnectionState.Error(
                "找不到手机服务 — 检查手表和手机是否在同一网络下（通过手表系统设置 > 蓝牙 PAN 走网络）"
            )
            return@withLock false
        }

        _connectionState.value = WearConnectionState.Connecting("正在连 ${endpoint.host}:${endpoint.port}")
        val request = Request.Builder()
            .url("ws://${endpoint.host}:${endpoint.port}")
            .build()

        val result = CompletableDeferred<Result<Unit>>()

        val socketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket open: ${response.code} ${response.message}")
                this@WearNetworkBridge.webSocket = webSocket
                _isConnected.value = true
                _connectionState.value = WearConnectionState.Connected
                // 连接成功，缓存 endpoint 供下次重连使用
                synchronized(endpointCacheLock) {
                    cachedEndpoint = endpoint
                }
                if (!result.isCompleted) result.complete(Result.success(Unit))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncoming(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                failAllPending("Phone closed the connection.")
                this@WearNetworkBridge.webSocket = null
                _isConnected.value = false
                _connectionState.value = WearConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}", t)
                failAllPending(t.message ?: "Connection failed.")
                this@WearNetworkBridge.webSocket = null
                _isConnected.value = false
                _connectionState.value = WearConnectionState.Error(
                    "连不上手机: ${t.message ?: t.javaClass.simpleName}"
                )
                if (!result.isCompleted) result.complete(Result.failure(t))
            }
        }

        val socket = okHttpClient.newWebSocket(request, socketListener)
        webSocket = socket

        try {
            val outcome = withTimeout(10_000L) { result.await() }
            outcome.isSuccess
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "WebSocket connect timed out")
            runCatching { socket.cancel() }
            failAllPending("Connect timed out.")
            _isConnected.value = false
            _connectionState.value = WearConnectionState.Error("连接超时")
            false
        } catch (e: Exception) {
            runCatching { socket.cancel() }
            false
        }
    }

    /**
     * Find the phone's advertised service via mDNS. Returns (host, port) on
     * success, or null if no service is found within [DISCOVERY_TIMEOUT_MS].
     */
    private suspend fun discoverPhone(): Endpoint? = suspendCancellableCoroutine { cont ->
        val nsd = nsdManager
        if (nsd == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val resolved = CompletableDeferred<Endpoint?>()
        var discoveryListener: NsdManager.DiscoveryListener? = null
        var stopTimerJob: Job? = null

        fun cleanup() {
            if (stopTimerJob != null) {
                runCatching { stopTimerJob?.cancel() }
            }
            discoveryListener?.let { listener ->
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
        }

        cont.invokeOnCancellation { cleanup() }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "NSD discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "NSD service found: ${service.serviceName} ${service.serviceType}")
                if (resolved.isCompleted) return
                runCatching {
                    nsd.resolveServiceCompat(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                            Log.w(TAG, "NSD resolve failed: $errorCode for ${service.serviceName}")
                        }

                        override fun onServiceResolved(service: NsdServiceInfo) {
                            if (resolved.isCompleted) return
                            val host = service.firstHostAddress()
                            if (host == null) {
                                Log.w(TAG, "NSD resolved but no host address")
                                return
                            }
                            Log.d(TAG, "NSD resolved: ${service.serviceName} -> $host:${service.port}")
                            resolved.complete(Endpoint(host, service.port))
                        }
                    })
                }.onFailure { Log.w(TAG, "NSD resolveService threw", it) }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.d(TAG, "NSD service lost: ${service.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "NSD discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD start discovery failed: $errorCode for $serviceType")
                if (!resolved.isCompleted) resolved.complete(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD stop discovery failed: $errorCode for $serviceType")
            }
        }
        discoveryListener = listener

        runCatching {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            Log.w(TAG, "NSD discoverServices threw", it)
            if (!resolved.isCompleted) resolved.complete(null)
        }

        stopTimerJob = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (!resolved.isCompleted) {
                Log.w(TAG, "NSD discovery timed out after ${DISCOVERY_TIMEOUT_MS}ms")
                resolved.complete(null)
            }
            cleanup()
        }

        // Bridge the resolved deferred into the outer continuation.
        scope.launch {
            val endpoint = resolved.await()
            if (cont.isActive) cont.resume(endpoint)
        }
    }

    suspend fun requestSync(): JSONObject? = withRequest {
        JSONObject().apply {
            put("type", "sync")
            put("requestId", newRequestId())
        }
    }

    /**
     * Sends a chat request to the phone. Returns true once the request frame
     * has been queued onto the socket; the actual streaming response arrives
     * asynchronously as [chatFrames] (chat_delta* -> chat_done | chat_error).
     *
     * Unlike [requestSync] / [requestNewConversation] / [requestAvatar] this
     * does NOT wait for a response — there are many response frames per
     * request, so the repository subscribes to [chatFrames] and accumulates
     * deltas itself.
     */
    suspend fun requestChat(
        requestId: String,
        conversationId: String,
        text: String
    ): Boolean = mutex.withLock {
        val socket = webSocket
        if (socket == null || !_isConnected.value) {
            return@withLock false
        }
        val request = JSONObject().apply {
            put("type", "chat")
            put("requestId", requestId)
            put("conversationId", conversationId)
            put("text", text)
        }
        try {
            socket.send(request.toString())
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket chat send failed", e)
            false
        }
    }

    suspend fun requestNewConversation(
        requestId: String,
        agentId: String?
    ): JSONObject? = withRequest {
        JSONObject().apply {
            put("type", "new_conversation")
            put("requestId", requestId)
            if (!agentId.isNullOrBlank()) put("agentId", agentId)
        }
    }

    /**
     * Fetches the base64-encoded avatar image bytes for either an agent
     * (`agentId` non-null) or the user (`scope = "user"`). The bytes are
     * large, so this is only called when the watch detects that the
     * `avatarVersion` reported by the phone's sync_response differs from
     * the locally cached version.
     */
    suspend fun requestAvatar(
        requestId: String,
        agentId: String? = null,
        userScope: Boolean = false
    ): JSONObject? = withRequest {
        JSONObject().apply {
            put("type", "get_avatar")
            put("requestId", requestId)
            if (userScope) put("scope", "user")
            if (!agentId.isNullOrBlank()) put("agentId", agentId)
        }
    }

    private suspend fun withRequest(buildRequest: () -> JSONObject): JSONObject? = mutex.withLock {
        val socket = webSocket
        if (socket == null || !_isConnected.value) {
            return@withLock null
        }
        val request = buildRequest()
        val requestId = request.optString("requestId")
        if (requestId.isBlank()) return@withLock null

        val deferred = CompletableDeferred<String>()
        pendingRequests[requestId] = deferred

        try {
            val ok = socket.send(request.toString())
            if (!ok) {
                pendingRequests.remove(requestId)
                return@withLock null
            }
        } catch (e: Exception) {
            Log.w(TAG, "WebSocket send failed", e)
            pendingRequests.remove(requestId)
            return@withLock null
        }

        val text = try {
            withTimeout(REQUEST_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Request $requestId timed out")
            pendingRequests.remove(requestId)
            return@withLock null
        } catch (e: Exception) {
            Log.w(TAG, "Request $requestId failed", e)
            pendingRequests.remove(requestId)
            return@withLock null
        }

        try { JSONObject(text) } catch (e: Exception) { null }
    }

    private fun handleIncoming(text: String) {
        val response = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring malformed response: $text", e)
            return
        }
        val requestId = response.optString("requestId")
        if (requestId.isBlank()) {
            // Server-pushed message with no correlation. Nothing to do
            // today, but kept as a hook for future push-style messages.
            return
        }
        // Streaming chat frames are multiplexed onto chatFrames (many per
        // requestId) instead of the single-response pendingRequests map.
        when (response.optString("type")) {
            "chat_delta" -> {
                val delta = response.optString("delta")
                if (delta.isNotEmpty()) {
                    _chatFrames.tryEmit(WearChatFrame.Delta(requestId, delta))
                }
            }
            "chat_done" -> {
                _chatFrames.tryEmit(
                    WearChatFrame.Done(
                        requestId = requestId,
                        content = response.optString("content"),
                        userMessageId = response.optString("userMessageId")
                            .takeIf { it.isNotBlank() },
                        assistantMessageId = response.optString("assistantMessageId")
                            .takeIf { it.isNotBlank() }
                    )
                )
            }
            "chat_error" -> {
                val message = response.optString("error").ifBlank { "Unknown error" }
                _chatFrames.tryEmit(WearChatFrame.Error(requestId, message))
            }
            else -> {
                // Single-response types (sync_response, new_conversation_response,
                // avatar_response). Resolve the waiting deferred and drop it.
                val deferred = pendingRequests.remove(requestId) ?: return
                deferred.complete(text)
            }
        }
    }

    private fun failAllPending(reason: String) {
        if (pendingRequests.isEmpty()) return
        val copy = pendingRequests.keys.toList()
        for (id in copy) {
            pendingRequests.remove(id)?.completeExceptionally(IllegalStateException(reason))
        }
    }

    /**
     * Public force-close hook used by the polling loop on reconnect.
     */
    fun close() {
        runCatching { webSocket?.close(1000, "client closed") }
        webSocket = null
        _isConnected.value = false
        _connectionState.value = WearConnectionState.Disconnected
    }

    private fun newRequestId(): String =
        "req-${System.currentTimeMillis()}-${(0..0xffff).random()}"

    private data class Endpoint(val host: String, val port: Int)

    // NsdManager.resolveService(NsdServiceInfo, ResolveListener) and NsdServiceInfo.host are
    // deprecated on API 34+ (Executor-based resolveService / hostAddresses), but those
    // replacements require API 34 while the wear module's minSdk is 30 (Wear OS 3 devices).
    @Suppress("DEPRECATION")
    private fun NsdManager.resolveServiceCompat(
        service: NsdServiceInfo,
        listener: NsdManager.ResolveListener,
    ) = resolveService(service, listener)

    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.firstHostAddress(): String? = host?.hostAddress

    companion object {
        private const val TAG = "WearNetworkBridge"
        // NSD service type — DNS-SD. Must match the phone's registration.
        private const val SERVICE_TYPE = "_messenger._tcp."
        private const val DISCOVERY_TIMEOUT_MS = 4_000L  // 优化：从 8s 降到 4s
        private const val REQUEST_TIMEOUT_MS = 60_000L
    }
}
