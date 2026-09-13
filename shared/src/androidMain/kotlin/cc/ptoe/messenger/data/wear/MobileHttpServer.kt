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

package cc.ptoe.messenger.data.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import cc.ptoe.messenger.presentation.utils.stripThinkBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress

/**
 * Foreground service that exposes the Messenger data layer to a paired Wear OS
 * watch over a stock WebSocket transport.
 *
 * This is a Bluetooth-free transport. The previous Bluetooth RFCOMM socket
 * was abandoned because Samsung China-region Galaxy Watches have broken
 * GMS for Wear OS *and* because we kept hitting Samsung's Bluetooth pairing
 * quirks. Wear OS watches tether their network to the phone (Bluetooth PAN),
 * so the watch and phone are always on the same L2 network — we can just run
 * a regular WebSocket server on the phone and let the watch find it via mDNS
 * (Android's NsdManager).
 *
 * Protocol: one JSON object per WebSocket text frame, requestId is always
 * echoed in the response so the client can correlate.
 *
 *   <- {"type":"sync", "requestId":"..."}
 *   -> {"type":"sync_response", "requestId":"...", "agents":[...], ...}
 *   <- {"type":"chat",  "requestId":"...", "conversationId":"...", "text":"..."}
 *   -> {"type":"chat_delta", "requestId":"...", "delta":"..."}   (zero or more)
 *   -> {"type":"chat_done",  "requestId":"...", "content":"...", "userMessageId":"...", "assistantMessageId":"..."}
 *      — or chat_error if the request fails before streaming starts
 *   <- {"type":"new_conversation", "requestId":"...", "agentId":"..."}
 *   -> {"type":"new_conversation_response", "requestId":"...", "conversationId":"..."}
 *
 * The chat path streams (many chat_delta frames followed by one terminal
 * chat_done / chat_error), so onMessage dispatches it directly to a streaming
 * handler instead of the single-response handleRequest path used by the others.
 *
 * The chat and new_conversation paths reuse [MobileWearChatHandler] so the
 * business logic stays identical to the previous transport.
 */
class MobileHttpServer : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var wsServer: WebSocketServer? = null
    @Volatile private var nsdManager: NsdManager? = null
    @Volatile private var registrationListener: NsdManager.RegistrationListener? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var isRunning = false

    private val app get() = AppContainerHolder.instance

    override fun onCreate() {
        super.onCreate()
        runCatching { startInForeground() }
        acquireMulticastLock()
        startServer()
        registerNsd()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        runCatching { wsServer?.stop(1_000) }
        wsServer = null
        unregisterNsd()
        runCatching { multicastLock?.release() }
        multicastLock = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                WEAR_NOTIFICATION_TITLE,
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(WEAR_NOTIFICATION_TITLE)
            .setContentText("Wear sync running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireMulticastLock() {
        // mDNS uses multicast. Without this lock, doze / power-save often
        // drops the multicast packets and NSD discovery silently fails.
        runCatching {
            val wifi = getSystemService(WifiManager::class.java) ?: return@runCatching
            val lock = wifi.createMulticastLock(MULTICAST_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        }.onFailure { Log.w(TAG, "Failed to acquire multicast lock", it) }
    }

    private fun startServer() {
        if (isRunning) return
        isRunning = true
        serviceScope.launch {
            val server = object : WebSocketServer(InetSocketAddress(PORT)) {
                override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                    Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
                }

                override fun onMessage(conn: WebSocket, message: String) {
                    val request = try {
                        JSONObject(message)
                    } catch (e: Exception) {
                        Log.w(TAG, "Invalid message: $message", e)
                        try {
                            conn.send(
                                JSONObject().apply {
                                    put("type", "error")
                                    put("error", "Invalid JSON: ${e.message}")
                                }.toString()
                            )
                        } catch (_: Exception) {}
                        return
                    }
                    // chat streams (chat_delta* -> chat_done | chat_error), so it
                    // bypasses the single-response handleRequest path and pushes
                    // frames directly onto the connection as deltas arrive.
                    if (request.optString("type") == "chat") {
                        serviceScope.launch {
                            val payload = request.toString().encodeToByteArray()
                            try {
                                MobileWearChatHandler(app).handleChatRequestStreaming(payload) { frame ->
                                    try {
                                        conn.send(frame.toString())
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to send chat frame", e)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to handle chat message: $message", e)
                            }
                        }
                        return
                    }
                    serviceScope.launch {
                        val response = try {
                            handleRequest(request)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to handle message: $message", e)
                            JSONObject().apply {
                                put("type", "error")
                                put("error", e.message ?: "Unknown error")
                            }
                        }
                        try {
                            conn.send(response.toString())
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to send response", e)
                        }
                    }
                }

                override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
                    Log.d(TAG, "Client disconnected: code=$code reason=$reason")
                }

                override fun onError(conn: WebSocket?, ex: Exception) {
                    Log.w(TAG, "Server error", ex)
                }

                override fun onStart() {
                    Log.d(TAG, "WebSocket server listening on $PORT")
                }
            }
            try {
                server.start()
                wsServer = server
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start WebSocket server", e)
            }
        }
    }

    private fun registerNsd() {
        val nsd = getSystemService(NsdManager::class.java) ?: run {
            Log.w(TAG, "NsdManager not available")
            return
        }
        nsdManager = nsd
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "NSD service registered: ${serviceInfo.serviceName} ${serviceInfo.serviceType}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: error=$errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d(TAG, "NSD service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed: error=$errorCode")
            }
        }
        registrationListener = listener
        runCatching {
            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { Log.w(TAG, "NSD registerService threw", it) }
    }

    private fun unregisterNsd() {
        val nsd = nsdManager ?: return
        val listener = registrationListener ?: return
        runCatching { nsd.unregisterService(listener) }
        registrationListener = null
    }

    private suspend fun handleRequest(request: JSONObject): JSONObject {
        val requestId = request.optString("requestId")
        return when (request.optString("type")) {
            "sync" -> {
                val response = handleSyncRequest()
                if (requestId.isNotBlank()) response.put("requestId", requestId)
                response
            }
            // "chat" is handled inline in onMessage (streaming) and never reaches here.
            "new_conversation" -> {
                val payload = request.toString().encodeToByteArray()
                val responseBytes = MobileWearChatHandler(app).handleNewConversation(payload)
                val responseJson = JSONObject(responseBytes.decodeToString())
                responseJson.put("type", "new_conversation_response")
                responseJson
            }
            "get_avatar" -> {
                val scope = request.optString("scope").ifBlank { null }
                val agentId = request.optString("agentId").ifBlank { null }
                val response = handleGetAvatarRequest(scope, agentId)
                response.put("type", "avatar_response")
                if (requestId.isNotBlank()) response.put("requestId", requestId)
                response
            }
            else -> JSONObject().apply {
                put("type", "error")
                put("error", "Unknown request type: ${request.optString("type")}")
                if (requestId.isNotBlank()) put("requestId", requestId)
            }
        }
    }

    private suspend fun handleSyncRequest(): JSONObject {
        val agents = app.agentRepository.getAll().first()
            .sortedWith(compareByDescending<Agent> { it.isDefault }.thenBy { it.name.lowercase() })
        val conversations = app.conversationRepository.getAll().first()
            .sortedByDescending { it.updatedAt }
            .take(MAX_CONVERSATIONS)

        val agentsJson = JSONArray().apply {
            agents.forEach { agent ->
                put(JSONObject().apply {
                    put("id", agent.id)
                    put("name", agent.name)
                    put("isDefault", agent.isDefault)
                    put("isReady", isAgentReady(agent))
                    // avatarVersion lets the watch decide whether it needs to
                    // fetch the bytes via get_avatar. 0 = no avatar on the phone.
                    put("avatarVersion", avatarVersionOf(agent.avatar))
                })
            }
        }

        val conversationsJson = JSONArray().apply {
            conversations.forEach { conv ->
                put(JSONObject().apply {
                    put("id", conv.id)
                    put("title", conv.title)
                    put("agentId", conv.agentId)
                    put("lastMessage", conv.lastMessage?.let { stripThinkBlock(it) })
                    put("updatedAt", conv.updatedAt)
                    put("createdAt", conv.createdAt)
                })
            }
        }

        val messagesJson = JSONObject()
        if (conversations.isNotEmpty()) {
            val conversationIds = conversations.map { it.id }
            val allMessages = app.messageRepository.getByConversationIds(conversationIds)
                .filter { it.role != MessageRole.SYSTEM }
                .groupBy { it.conversationId }

            conversations.forEach { conv ->
                val messages = allMessages[conv.id]?.takeLast(MAX_MESSAGES_PER_CONVERSATION) ?: emptyList()
                messagesJson.put(conv.id, JSONArray().apply {
                    messages.forEach { msg ->
                        put(JSONObject().apply {
                            put("id", msg.id)
                            put("conversationId", msg.conversationId)
                            put("role", when (msg.role) {
                                MessageRole.ASSISTANT -> "assistant"
                                MessageRole.TOOL -> "tool"
                                MessageRole.SYSTEM -> "system"
                                else -> "user"
                            })
                            put("content", msg.content.ifBlank {
                                // Pure-image messages have blank content on the phone
                                // (text is empty and only image parts exist). Wear can't
                                // display images, so send a placeholder so the bubble
                                // doesn't render as "No response."
                                if (msg.hasImages) "[图片]" else ""
                            })
                            put("timestamp", msg.timestamp)
                            put("isError", msg.status == MessageStatus.ERROR)
                            put("isPending", msg.status == MessageStatus.SENDING)
                        })
                    }
                })
            }
        }

        val userAvatarPath = app.appPreferences.userAvatar.first()
        return JSONObject().apply {
            put("type", "sync_response")
            put("agents", agentsJson)
            put("conversations", conversationsJson)
            put("messages", messagesJson)
            put("userAvatarVersion", avatarVersionOf(userAvatarPath))
            put("updatedAt", System.currentTimeMillis())
        }
    }

    /**
     * Returns the avatar file's [File.lastModified] (or 0 if missing). Used
     * as a cheap cache key so the watch only pulls the (potentially large)
     * image bytes over WebSocket when the avatar has actually changed.
     */
    private fun avatarVersionOf(avatarPath: String?): Long {
        if (avatarPath.isNullOrBlank()) return 0L
        val file = File(avatarPath)
        if (!file.exists() || !file.isFile) return 0L
        return file.lastModified()
    }

    /**
     * Reads the requested avatar file, base64-encodes it, and returns the
     * payload for an `avatar_response`. The watch persists the bytes to disk
     * and uses the local path as the agent's avatar.
     *
     * `scope` may be "user" for the user's own avatar, or null/blank with
     * `agentId` set for an agent avatar. Returns version 0 and no base64 if
     * the avatar is missing or unreadable.
     */
    private suspend fun handleGetAvatarRequest(scope: String?, agentId: String?): JSONObject {
        val path = when (scope) {
            "user" -> app.appPreferences.userAvatar.first()
            else -> {
                if (agentId.isNullOrBlank()) null
                else app.agentRepository.getById(agentId).first()?.avatar
            }
        }
        val version = avatarVersionOf(path)
        val response = JSONObject().apply {
            put("version", version)
            if (scope == "user") put("scope", "user")
            if (!agentId.isNullOrBlank()) put("agentId", agentId)
        }
        if (version == 0L || path.isNullOrBlank()) {
            response.put("base64", JSONObject.NULL)
            return response
        }
        return runCatching {
            val bytes = File(path).readBytes()
            response.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            response
        }.getOrElse { e ->
            Log.w(TAG, "Failed to read avatar at $path", e)
            response.put("version", 0L)
            response.put("base64", JSONObject.NULL)
            response
        }
    }

    private suspend fun isAgentReady(agent: Agent): Boolean {
        val enabledModels = app.modelRepository.getAll().first().filter { it.isEnabled }
        if (enabledModels.isEmpty()) return false
        val model = agent.defaultModelId?.let { modelId ->
            enabledModels.firstOrNull { it.id == modelId }
        } ?: enabledModels.firstOrNull() ?: return false
        return app.providerRepository.getById(model.providerId).first() != null
    }

    companion object {
        private const val TAG = "MobileHttpServer"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "wear_http_sync"
        private const val WEAR_NOTIFICATION_TITLE = "Messenger"
        private const val SERVICE_NAME = "MessengerWearSync"
        // NSD service type — DNS-SD. The trailing dot is required.
        private const val SERVICE_TYPE = "_messenger._tcp."
        private const val PORT = 18765
        private const val MULTICAST_LOCK_TAG = "MessengerWearSync"
        private const val MAX_CONVERSATIONS = 30
        private const val MAX_MESSAGES_PER_CONVERSATION = 40
    }
}
