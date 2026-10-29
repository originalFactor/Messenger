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
package cc.ptoe.messenger.core

import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.remote.createPlatformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JVM implementation of [CoreBridge] over the Rust core's versioned loopback
 * HTTP/SSE API. No business operation crosses JNI; the native library only
 * supplies the server address and its per-start bearer token.
 */
class RestCoreBridge(
    localAddress: String,
    private val token: String,
) : CoreBridge {
    private val baseUrl = "http://${localAddress.removeSuffix("/")}/api/v1"
    private val client: HttpClient = createPlatformHttpClient {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = Long.MAX_VALUE
            socketTimeoutMillis = Long.MAX_VALUE
        }
    }
    private val listeners = CopyOnWriteArrayList<(kind: String, ids: List<String>) -> Unit>()
    private val subscriptionStarted = AtomicBoolean(false)
    private val subscriptionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeConversationId: String? = null

    override fun subscribe(listener: (kind: String, ids: List<String>) -> Unit) {
        listeners += listener
        if (subscriptionStarted.compareAndSet(false, true)) {
            subscriptionScope.launch {
                while (isActive) {
                    try {
                        streamSse("/events") { data ->
                            val root = NetworkClient.json.parseToJsonElement(data).jsonObject
                            val kind = root["kind"]?.jsonPrimitive?.contentOrNull ?: return@streamSse
                            val ids = root["ids"]?.jsonArray
                                ?.map { it.jsonPrimitive.contentOrNull.orEmpty() }
                                .orEmpty()
                            listeners.forEach { it(kind, ids) }
                        }
                    } catch (_: Throwable) {
                        delay(1_000)
                    }
                }
            }
        }
    }

    override fun listProvidersJson(): String = blockingItems("/providers")
    override fun getProviderJson(id: String): String? = blockingOptional("/providers/${path(id)}")
    override fun upsertProviderJson(json: String) = blockingPut("/providers", json)
    override fun deleteProvider(id: String) = blockingDelete("/providers/${path(id)}")

    override fun listModelsJson(): String = blockingItems("/models")
    override fun listModelsByProviderJson(providerId: String): String =
        blockingItems("/providers/${path(providerId)}/models")
    override fun getModelJson(id: String): String? = blockingOptional("/models/${path(id)}")
    override fun upsertModelJson(json: String) = blockingPut("/models", json)
    override fun setModelEnabled(id: String, enabled: Boolean) {
        blockingPut(
            "/models/${path(id)}/enabled",
            buildJsonObject { put("enabled", enabled) }.toString(),
        )
    }
    override fun deleteModel(id: String) = blockingDelete("/models/${path(id)}")

    override fun listAgentsJson(): String = blockingItems("/agents")
    override fun getAgentJson(id: String): String? = blockingOptional("/agents/${path(id)}")
    override fun getDefaultAgentJson(): String? = blockingOptional("/agents/default")
    override fun getTitleAgentJson(): String? = blockingOptional("/agents/title")
    override fun upsertAgentJson(json: String) = blockingPut("/agents", json)
    override fun deleteAgent(id: String) = blockingDelete("/agents/${path(id)}")

    override fun listProjectsJson(): String = blockingItems("/projects")
    override fun getProjectJson(id: String): String? = blockingOptional("/projects/${path(id)}")
    override fun upsertProjectJson(json: String) = blockingPut("/projects", json)
    override fun deleteProject(id: String) = blockingDelete("/projects/${path(id)}")

    override fun listConversationsJson(): String = blockingItems("/conversations")
    override fun listConversationsByAgentJson(agentId: String): String =
        blockingItems("/agents/${path(agentId)}/conversations")
    override fun listConversationsByProjectJson(projectId: String): String =
        blockingItems("/projects/${path(projectId)}/conversations")
    override fun getConversationJson(id: String): String? =
        blockingOptional("/conversations/${path(id)}")
    override fun upsertConversationJson(json: String) = blockingPut("/conversations", json)
    override fun updateConversationLastMessage(id: String, lastMessage: String?, updatedAt: Long) {
        blockingPut(
            "/conversations/${path(id)}/last-message",
            buildJsonObject {
                if (lastMessage == null) put("lastMessage", kotlinx.serialization.json.JsonNull)
                else put("lastMessage", lastMessage)
                put("updatedAt", updatedAt)
            }.toString(),
        )
    }
    override fun deleteConversation(id: String) = blockingDelete("/conversations/${path(id)}")

    override fun sweepEmptyConversations(excludeIdsJson: String): String = blockingPost(
        "/conversations/sweep-empty",
        buildJsonObject {
            put("excludeIds", NetworkClient.json.parseToJsonElement(excludeIdsJson))
        }.toString(),
    )

    override fun listMessagesByConversationJson(conversationId: String): String =
        blockingAllMessages(conversationId)
    override fun listRecentMessagesByConversationJson(conversationId: String, limit: UInt): String =
        blockingItems("/conversations/${path(conversationId)}/messages?limit=$limit")
    override fun listMessagesBeforeJson(
        conversationId: String,
        beforeId: String,
        limit: UInt,
    ): String = blockingItems(
        "/conversations/${path(conversationId)}/messages?limit=$limit&beforeId=${query(beforeId)}",
    )
    override fun listMessagesFromJson(conversationId: String, fromId: String): String =
        blockingItems(
            "/conversations/${path(conversationId)}/messages?fromId=${query(fromId)}",
        )
    override fun getMessageJson(id: String): String? = blockingOptional("/messages/${path(id)}")
    override fun upsertMessageJson(json: String) {
        val root = NetworkClient.json.parseToJsonElement(json).jsonObject
        val id = root["id"]?.jsonPrimitive?.contentOrNull
            ?: error("message JSON is missing id")
        blockingPut("/messages/${path(id)}", json)
    }
    override fun deleteMessage(id: String) = blockingDelete("/messages/${path(id)}")
    override fun deleteMessagesByConversation(conversationId: String) =
        blockingDelete("/conversations/${path(conversationId)}/messages")

    override fun kvGet(key: String): String? = blockingOptional("/kv/${path(key)}")?.let { body ->
        runCatching {
            NetworkClient.json.parseToJsonElement(body).jsonPrimitive.contentOrNull
        }.getOrNull()
    }
    override fun kvSet(key: String, value: String) = blockingPut(
        "/kv/${path(key)}",
        buildJsonObject { put("value", value) }.toString(),
    )
    override fun kvDelete(key: String) = blockingDelete("/kv/${path(key)}")

    override suspend fun cloudLogin(email: String, password: String): String =
        postText("/cloud/login", credentials(email, password))
    override suspend fun cloudRegister(email: String, password: String): String =
        postText("/cloud/register", credentials(email, password))
    override suspend fun cloudLogout() = postNoContent("/cloud/logout")
    override suspend fun cloudRefreshUser(): String = postText("/cloud/refresh", null)
    override fun cloudCurrentUserJson(): String? = blockingOptional("/cloud/me")
    override fun cloudSetServerUrl(url: String) = blockingPut(
        "/cloud/server-url",
        buildJsonObject { put("url", url) }.toString(),
    )
    override fun cloudGetServerUrl(): String =
        NetworkClient.json.decodeFromString(blockingGet("/cloud/server-url"))
    override fun cloudHasLocalData(): Boolean =
        NetworkClient.json.decodeFromString(blockingGet("/cloud/has-local-data"))
    override fun cloudMarkChange(kind: String, id: String, deleted: Boolean) {
        blockingPost(
            "/changes",
            buildJsonObject {
                put("kind", kind)
                put("id", id)
                put("deleted", deleted)
            }.toString(),
        )
    }
    override suspend fun cloudSync(replaceLocal: Boolean): String = postText(
        "/cloud/sync",
        buildJsonObject { put("replaceLocal", replaceLocal) }.toString(),
    )
    override suspend fun cloudPushPending(): String = postText("/cloud/push-pending", null)
    override suspend fun cloudPreviewCard(code: String): String = postText(
        "/cloud/cards/preview",
        buildJsonObject { put("code", code) }.toString(),
    )
    override suspend fun cloudRedeemCard(code: String): String = postText(
        "/cloud/cards/redeem",
        buildJsonObject { put("code", code) }.toString(),
    )
    override suspend fun cloudSyncBuiltinModels(force: Boolean): Long = postText(
        "/cloud/builtin-models/sync",
        buildJsonObject { put("force", force) }.toString(),
    ).toLong()

    override suspend fun discoverProviderModelsJson(
        baseUrl: String,
        apiKey: String,
        apiFormat: String?,
    ): String = postText(
        "/models/discover",
        buildJsonObject {
            put("baseUrl", baseUrl)
            put("apiKey", apiKey)
            if (apiFormat == null) put("apiFormat", kotlinx.serialization.json.JsonNull)
            else put("apiFormat", apiFormat)
        }.toString(),
    )

    override suspend fun modelsDevMetadataJson(modelId: String): String? =
        optionalText("/models/metadata/${path(modelId)}")

    override suspend fun cloudListMarketAgents(query: String, cursor: String?): String {
        val suffix = buildString {
            append("?query=").append(query(query))
            if (cursor != null) append("&cursor=").append(query(cursor))
        }
        return getText("/cloud/market$suffix")
    }
    override suspend fun cloudGetMarketAgent(id: String): String =
        getText("/cloud/market/${path(id)}")
    override suspend fun cloudPublishMarketAgent(agentId: String): String =
        postText("/cloud/market/publish/${path(agentId)}", null)
    override suspend fun cloudImportMarketAgent(marketId: String): String =
        postText("/cloud/market/import/${path(marketId)}", null)
    override suspend fun cloudCacheAvatar(
        scope: String,
        accountId: String,
        id: String,
        url: String,
        version: String?,
        destDir: String,
    ): String = decodeJsonString(postText(
        "/cloud/cache-avatar",
        buildJsonObject {
            put("scope", scope)
            put("accountId", accountId)
            put("id", id)
            put("url", url)
            if (version == null) put("version", kotlinx.serialization.json.JsonNull)
            else put("version", version)
            put("destDir", destDir)
        }.toString(),
    ))

    override suspend fun cloudPushSnapshot(): Long = postText("/cloud/push-snapshot", null).toLong()
    override suspend fun cloudSyncWith(
        since: Long?,
        replaceLocal: Boolean,
        expectedServerVersion: Long?,
    ): String = postText(
        "/cloud/sync-with",
        buildJsonObject {
            if (since == null) put("since", kotlinx.serialization.json.JsonNull) else put("since", since)
            put("replaceLocal", replaceLocal)
            if (expectedServerVersion == null) {
                put("expectedServerVersion", kotlinx.serialization.json.JsonNull)
            } else {
                put("expectedServerVersion", expectedServerVersion)
            }
        }.toString(),
    )
    override suspend fun cloudReplaceCloudWithLocal(): String =
        postText("/cloud/replace-cloud-with-local", null)
    override suspend fun cloudChangePassword(currentPassword: String, newPassword: String) {
        postNoContent(
            "/cloud/change-password",
            buildJsonObject {
                put("currentPassword", currentPassword)
                put("newPassword", newPassword)
            }.toString(),
        )
    }
    override suspend fun cloudDeleteAccount(currentPassword: String) {
        deleteNoContent(
            "/cloud/account",
            buildJsonObject { put("currentPassword", currentPassword) }.toString(),
        )
    }
    override suspend fun cloudEnsureBuiltinTitleAgent() {
        postNoContent("/cloud/ensure-title-agent")
    }
    override suspend fun cloudUploadUserAvatar(
        bytes: ByteArray,
        filename: String,
        mime: String,
    ): String = decodeJsonString(postText("/cloud/avatars/user", avatar(bytes, filename, mime)))
    override suspend fun cloudDeleteUserAvatar(): String = decodeJsonString(deleteText("/cloud/avatars/user"))
    override suspend fun cloudUploadAgentAvatar(
        agentId: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
    ): String = decodeJsonString(
        postText("/cloud/avatars/agents/${path(agentId)}", avatar(bytes, filename, mime)),
    )
    override suspend fun cloudDeleteAgentAvatar(agentId: String): String =
        decodeJsonString(deleteText("/cloud/avatars/agents/${path(agentId)}"))
    override suspend fun cloudPushMarketAgentUpdate(agentId: String): String =
        postText("/cloud/market/update/${path(agentId)}", null)
    override suspend fun cloudRemoveMarketAgent(agentId: String) {
        deleteNoContent("/cloud/market/${path(agentId)}")
    }
    override suspend fun cloudCheckMarketAgentUpdate(agentId: String): String =
        getText("/cloud/market/check/${path(agentId)}")
    override suspend fun cloudApplyMarketAgentUpdate(agentId: String, marketJson: String): String =
        postText(
            "/cloud/market/apply/${path(agentId)}",
            buildJsonObject {
                put("market", NetworkClient.json.parseToJsonElement(marketJson))
            }.toString(),
        )
    override suspend fun cloudImportMarketAgentWithAvatar(marketId: String): String =
        postText("/cloud/market/import-avatar/${path(marketId)}", null)
    override suspend fun cloudClearMarketLinksAndBuiltinProvider() {
        deleteNoContent("/cloud/market-links")
    }
    override fun clearLocalDataForReinit() = blockingDelete("/local-data")

    override fun cancelTurn() {
        val conversationId = activeConversationId ?: return
        runCatching {
            runBlocking { postNoContent("/conversations/${path(conversationId)}/cancel") }
        }
    }

    override suspend fun runTurn(
        config: TurnConfigBridge,
        toolExecutor: suspend (name: String, argumentsJson: String) -> Pair<String, Boolean>,
        onEventJson: (String) -> Unit,
    ) {
        var finished = false
        activeConversationId = config.conversationId
        try {
            streamSsePost(
                "/conversations/${path(config.conversationId)}/messages",
                buildJsonObject {
                    put("operation", "continue")
                    put(
                        "toolSpecs",
                        kotlinx.serialization.json.JsonArray(
                            config.tools.map { tool ->
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parametersJson", tool.parametersJson)
                                }
                            },
                        ),
                    )
                }.toString(),
            ) { data ->
                val root = runCatching {
                    NetworkClient.json.parseToJsonElement(data).jsonObject
                }.getOrNull()
                val type = root?.get("type")?.jsonPrimitive?.contentOrNull
                if (type == "Finished" || type == "Error" || type == "Cancelled") finished = true
                onEventJson(data)
                if (type == "ToolCallRequested") {
                    val callId = root["callId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val name = root["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val argumentsJson = root["argumentsJson"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val (output, isError) = try {
                        toolExecutor(name, argumentsJson)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        (error.message ?: error::class.simpleName ?: "tool execution failed") to true
                    }
                    postNoContent(
                        "/turns/tools/${path(callId)}/result",
                        buildJsonObject {
                            put("output", output)
                            put("isError", isError)
                        }.toString(),
                    )
                }
            }
        } finally {
            activeConversationId = null
        }
        if (!finished) {
            onEventJson(
                buildJsonObject {
                    put("type", "Error")
                    put("code", "stream_ended")
                    put("message", "The core event stream ended before completion.")
                }.toString(),
            )
        }
    }

    /**
     * The bridge contract returns the complete conversation history. The REST
     * endpoint is deliberately paginated, so follow `nextBeforeId` until the
     * server reports that there are no older rows.
     */
    private fun blockingAllMessages(conversationId: String): String = runBlocking {
        val messages = mutableListOf<kotlinx.serialization.json.JsonElement>()
        var beforeId: String? = null
        while (true) {
            val suffix = buildString {
                append("?limit=500")
                if (beforeId != null) append("&beforeId=${query(beforeId)}")
            }
            val root = NetworkClient.json
                .parseToJsonElement(getText("/conversations/${path(conversationId)}/messages$suffix"))
                .jsonObject
            val page = root.getValue("items").jsonArray
            messages.addAll(0, page)
            beforeId = when {
                root["nextBeforeId"]?.jsonPrimitive?.contentOrNull != null ->
                    root["nextBeforeId"]!!.jsonPrimitive.content
                page.size == 500 -> page.first().jsonObject.getValue("id").jsonPrimitive.content
                else -> null
            }
            if (beforeId == null) break
        }
        kotlinx.serialization.json.JsonArray(messages).toString()
    }

    private fun blockingItems(path: String): String = runBlocking {
        val body = getText(path)
        NetworkClient.json.parseToJsonElement(body).jsonObject["items"]?.toString()
            ?: error("REST response for $path has no items")
    }

    private fun blockingGet(path: String): String = runBlocking { getText(path) }
    private fun blockingOptional(path: String): String? = runBlocking { optionalText(path) }
    private fun blockingPut(path: String, body: String) = runBlocking { putNoContent(path, body) }
    private fun blockingPost(path: String, body: String? = null): String =
        runBlocking { postText(path, body) }
    private fun blockingCloud(path: String, body: String? = null): String =
        runBlocking { postText(path, body) }
    private fun blockingDelete(path: String, body: String? = null) =
        runBlocking { deleteNoContent(path, body) }
    private fun blockingDeleteJson(path: String): String = runBlocking { deleteText(path) }

    private suspend fun getText(path: String): String {
        val response = request(HttpMethod.Get, path)
        return response.requireText()
    }

    private suspend fun optionalText(path: String): String? {
        val response = request(HttpMethod.Get, path)
        if (response.status == HttpStatusCode.NotFound) {
            response.bodyAsText()
            return null
        }
        val body = response.requireText()
        return body.takeUnless { it == "null" }
    }

    private suspend fun putNoContent(path: String, body: String) {
        request(HttpMethod.Put, path, body).requireNoContent()
    }

    private suspend fun postText(path: String, body: String?): String =
        request(HttpMethod.Post, path, body).requireText()

    private suspend fun postNoContent(path: String, body: String? = null) {
        request(HttpMethod.Post, path, body).requireNoContent()
    }

    private suspend fun deleteNoContent(path: String, body: String? = null) {
        request(HttpMethod.Delete, path, body).requireNoContent()
    }

    private suspend fun deleteText(path: String): String =
        request(HttpMethod.Delete, path).requireText()

    private suspend fun request(method: HttpMethod, path: String, body: String? = null): HttpResponse =
        client.request(baseUrl + path) {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }

    private suspend fun streamSse(path: String, onData: suspend (String) -> Unit) {
        client.prepareGet(baseUrl + path) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.execute { response ->
            readSse(response, onData)
        }
    }

    private suspend fun streamSsePost(path: String, body: String, onData: suspend (String) -> Unit) {
        client.preparePost(baseUrl + path) {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent(body, ContentType.Application.Json))
        }.execute { response ->
            readSse(response, onData)
        }
    }

    private suspend fun readSse(response: HttpResponse, onData: suspend (String) -> Unit) {
        if (!response.status.isSuccess()) error(response.errorMessage())
        val channel = response.bodyAsChannel()
        val data = StringBuilder()
        while (true) {
            val line = channel.readLine() ?: break
            when {
                line.isBlank() -> {
                    if (data.isNotEmpty()) {
                        onData(data.toString())
                        data.clear()
                    }
                }
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substringAfter("data:").removePrefix(" "))
                }
            }
        }
        if (data.isNotEmpty()) onData(data.toString())
    }

    private suspend fun HttpResponse.requireText(): String {
        if (!status.isSuccess()) error(errorMessage())
        return bodyAsText()
    }

    private suspend fun HttpResponse.requireNoContent() {
        if (!status.isSuccess()) error(errorMessage())
        bodyAsText()
    }

    private suspend fun HttpResponse.errorMessage(): String {
        val raw = runCatching { bodyAsText() }.getOrElse { return "HTTP ${status.value}" }
        val detail = runCatching {
            val root = NetworkClient.json.parseToJsonElement(raw).jsonObject
            root["detail"]?.jsonPrimitive?.contentOrNull
                ?: root["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return "HTTP ${status.value}: ${detail ?: raw}"
    }

    private fun decodeJsonString(body: String): String =
        NetworkClient.json.decodeFromString(body)

    private fun credentials(email: String, password: String): String = buildJsonObject {
        put("email", email)
        put("password", password)
    }.toString()

    private fun avatar(bytes: ByteArray, filename: String, mime: String): String = buildJsonObject {
        put("bytesBase64", Base64.getEncoder().encodeToString(bytes))
        put("filename", filename)
        put("mime", mime)
    }.toString()

    private fun path(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
    private fun query(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
