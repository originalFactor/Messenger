/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.remote.NetworkClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareDelete
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.preparePut
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Fixed-identity cloud requests for the Room facade. The dedicated transport disables redirects and has no cookie jar. No request is retried; callers persist successful mutations separately.
 */
internal class RoomCloudApi(
    private val transport: RoomCloudHttpClient,
    baseUrl: String,
    private val session: StoredCloudSession,
    private val expire: suspend (SyncSession) -> Unit,
) {
    internal val boundSession: SyncSession get() = session.session
    internal val boundBase: String get() = root
    private val root = canonicalCloudBase(baseUrl)
    init {
        require(root == canonicalCloudBase(session.host)) { "Cloud session server mismatch" }
    }
    private fun path(id: String): String = id.encodeURLPathPart().replace("/", "%2F")
    private fun HttpRequestBuilder.auth() { header(HttpHeaders.Cookie, session.cookie) }
    private fun HttpRequestBuilder.json(body: JsonObject) { contentType(ContentType.Application.Json); setBody(body.toString()) }
    private suspend fun call(credentialGated: Boolean = false, block: suspend () -> HttpStatement): String {
        try { return block().execute { response ->
            // HttpStatement.execute exposes headers before buffering a potentially stalled body.
            if (response.status.value == 401 && !credentialGated) { expire(session.session); throw CloudSessionExpired() }
            val body = response.bodyAsText()
            check(response.status.value in 200..299) { "Cloud HTTP ${response.status.value}: $body" }
            body
        } } catch (error: ResponseException) {
            if (error.response.status.value == 401 && !credentialGated) { expire(session.session); throw CloudSessionExpired() }
            throw error
        }
    }
    private fun <T> decode(serializer: DeserializationStrategy<T>, raw: String): T = NetworkClient.json.decodeFromString(serializer, raw)

    suspend fun refreshUser(): CloudUser = decode(UserResponse.serializer(), call { transport.http.prepareGet("$root/api/auth/me") { auth() } }).user
    suspend fun listMarket(query: String, cursor: String?): CloudMarketAgentListResponse =
        decode(CloudMarketAgentListResponse.serializer(), call { transport.http.prepareGet("$root/api/market/agents") {
            auth(); parameter("query", query.trim()); cursor?.let { parameter("cursor", it) }
        } })
    suspend fun marketAgent(id: String): CloudMarketAgent =
        decode(CloudMarketAgentResponse.serializer(), call { transport.http.prepareGet("$root/api/market/agents/${path(id)}") { auth() } }).agent
    suspend fun createMarketAgent(body: CloudMarketAgentRequest): CloudMarketAgent =
        decode(CloudMarketAgentResponse.serializer(), call { transport.http.preparePost("$root/api/market/agents") {
            auth(); contentType(ContentType.Application.Json); setBody(NetworkClient.json.encodeToString(CloudMarketAgentRequest.serializer(), body))
        } }).agent
    suspend fun updateMarketAgent(id: String, body: CloudMarketAgentRequest): CloudMarketAgent =
        decode(CloudMarketAgentResponse.serializer(), call { transport.http.preparePut("$root/api/market/agents/${path(id)}") {
            auth(); contentType(ContentType.Application.Json); setBody(NetworkClient.json.encodeToString(CloudMarketAgentRequest.serializer(), body))
        } }).agent
    suspend fun deleteMarketAgent(id: String) { call { transport.http.prepareDelete("$root/api/market/agents/${path(id)}") { auth() } } }
    suspend fun registerPushDevice(registrationId: String) { call { transport.http.preparePut("$root/api/push/device") {
        auth(); json(buildJsonObject { put("registrationId", JsonPrimitive(registrationId)) })
    } } }
    suspend fun removePushDevice() { call { transport.http.prepareDelete("$root/api/push/device") { auth() } } }
    suspend fun sendPushEvent(payload: JsonObject) { call { transport.http.preparePost("$root/api/push/events") { auth(); json(payload) } } }
    suspend fun previewCard(code: String): CloudCardPreview = decode(CloudCardPreviewResponse.serializer(), call { transport.http.preparePost("$root/api/console/cards/preview") {
        auth(); json(buildJsonObject { put("code", JsonPrimitive(code.trim())) })
    } }).card
    suspend fun redeemCard(code: String): CloudRedeemResponse = decode(CloudRedeemResponse.serializer(), call { transport.http.preparePost("$root/api/console/redeem") {
        auth(); json(buildJsonObject { put("code", JsonPrimitive(code.trim())) })
    } })
    suspend fun changePassword(current: String, replacement: String) { call(credentialGated = true) { transport.http.preparePut("$root/api/auth/password") {
        auth(); json(buildJsonObject { put("currentPassword", JsonPrimitive(current)); put("newPassword", JsonPrimitive(replacement)) })
    } } }
    suspend fun deleteAccount(password: String) { call(credentialGated = true) { transport.http.prepareDelete("$root/api/auth/account") {
        auth(); json(buildJsonObject { put("currentPassword", JsonPrimitive(password)) })
    } } }
    suspend fun avatar(agentId: String?, filename: String?, bytes: ByteArray?, mime: String?): CloudAvatarResponse {
        val endpoint = if (agentId == null) "$root/api/avatars/user" else "$root/api/avatars/agents/${path(agentId)}"
        val raw = if (bytes == null) call { transport.http.prepareDelete(endpoint) { auth() } } else {
            require(!filename.isNullOrBlank() && !mime.isNullOrBlank() && filename.none { it == '"' || it == '\r' || it == '\n' })
            call { transport.http.preparePut(endpoint) {
                auth(); setBody(MultiPartFormDataContent(formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentType, mime)
                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                    })
                }))
            } }
        }
        return decode(CloudAvatarResponse.serializer(), raw)
    }
    /** The AI key has its own lifetime; rejection must not expire the Cloud session. */
    suspend fun builtinModels(apiKey: String): List<JsonObject> {
        require(apiKey.isNotBlank())
        return transport.http.prepareGet("$root/v1/models") { header(HttpHeaders.Authorization, "Bearer $apiKey") }.execute { response ->
            check(response.status.value in 200..299) { "Built-in model sync HTTP ${response.status.value}" }
            val bytes = response.bodyAsChannel().readRemaining(8L * 1024 * 1024 + 1).readByteArray()
            check(bytes.size <= 8 * 1024 * 1024) { "Built-in model catalog exceeds size limit" }
            val data = NetworkClient.json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: error("Invalid model catalog")
            (data["data"] as? kotlinx.serialization.json.JsonArray)?.map { it as? JsonObject ?: error("Invalid model catalog entry") }
                ?: error("Missing model catalog")
        }
    }

    /** Read an avatar only from a fixed, authenticated Cloud endpoint, with bounded bytes. */
    suspend fun downloadAvatar(kind: String, id: String? = null): ByteArray {
        val endpoint = when (kind) {
            "user" -> "$root/api/avatars/user"
            "agent" -> "$root/api/avatars/agents/${path(requireNotNull(id))}"
            "market-agent" -> "$root/api/market/agents/${path(requireNotNull(id))}/avatar"
            else -> error("Unknown avatar kind")
        }
        try {
            return transport.http.prepareGet(endpoint) { auth() }.execute { response ->
                if (response.status.value == 401) { expire(session.session); throw CloudSessionExpired() }
                check(response.status.value in 200..299) { "Cloud avatar HTTP ${response.status.value}" }
                val bytes = response.bodyAsChannel().readRemaining(8L * 1024 * 1024 + 1).readByteArray()
                check(bytes.isNotEmpty() && bytes.size <= 8 * 1024 * 1024) { "Cloud avatar size is invalid" }
                bytes
            }
        } catch (error: ResponseException) {
            if (error.response.status.value == 401) { expire(session.session); throw CloudSessionExpired() }
            throw error
        }
    }

}
