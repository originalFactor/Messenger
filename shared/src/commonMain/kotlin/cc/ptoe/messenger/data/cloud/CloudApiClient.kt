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
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.remote.createPlatformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
internal data class CredentialsRequest(val email: String, val password: String)

@Serializable
internal data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)

@Serializable
internal data class AccountDeleteRequest(val currentPassword: String)

@Serializable
internal data class RedeemCodeRequest(val code: String)

@Serializable
internal data class UserResponse(val user: CloudUser)

@Serializable
internal data class SuccessResponse(val success: Boolean = false)

@Serializable
internal data class CloudAgentRequest(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val systemPrompt: String,
    val defaultModelId: String? = null,
    val temperature: Double,
    val topP: Double,
    val maxTokens: Int? = null,
    val reasoningEffort: String? = null,
    val isDefault: Boolean,
    val followDefaultSystemPrompt: Boolean,
    val followDefaultModel: Boolean,
    val followDefaultTemperature: Boolean,
    val followDefaultTopP: Boolean,
    val followDefaultMaxTokens: Boolean,
    val followDefaultReasoningEffort: Boolean,
    val marketAgentId: String? = null,
    val marketAgentVersion: Long? = null,
    val marketAgentRole: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
internal data class CloudMarketAgentRequest(
    val name: String,
    val systemPrompt: String,
    val temperature: Double,
    val topP: Double,
    val maxTokens: Int? = null,
    val reasoningEffort: String? = null
)

@Serializable
internal data class CloudMessageRequest(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val status: String,
    val errorMessage: String?,
    /**
     * JSON string mirroring the local [cc.ptoe.messenger.data.local.ContentPartCodec]
     * wire format. Absent (null) for pure-text messages so legacy
     * server payloads stay compatible.
     */
    val partsJson: String? = null
)

@Serializable
internal data class CloudConversationRequest(
    val id: String,
    val title: String,
    val agentId: String,
    val providerId: String,
    val overrideModelId: String?,
    val overrideTemperature: Double?,
    val overrideTopP: Double?,
    val overrideMaxTokens: Int?,
    val overrideReasoningEffort: String?,
    val reasoningFormat: String?,
    val contextSummary: String? = null,
    val contextSummaryUntil: Long = 0,
    val contextTokens: Long = 0,
    val contextTokensAt: Long = 0,
    val messages: List<CloudMessageRequest>,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
internal data class CloudModelRequest(
    val id: String,
    val modelId: String,
    val displayName: String,
    val isEnabled: Boolean,
    val contextWindow: Long = 0,
    val createdAt: Long
)

@Serializable
internal data class CloudProviderRequest(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<CloudModelRequest>,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Ktor replacement for the Retrofit `CloudApi` interface. Two clients:
 * - [http] with `expectSuccess` (non-2xx throws ResponseException with
 *   the error body, mapped by the repository to cloud error messages);
 * - [download] without it so avatar downloads can handle 304
 *   Not-Modified themselves.
 */
internal class CloudApiClient(appPreferences: AppPreferences) {

    private val cookieStorage = PersistentCookieStorage(appPreferences)

    val http: HttpClient = createPlatformHttpClient {
        expectSuccess = true
        install(ContentNegotiation) { json(NetworkClient.json) }
        install(Logging) { level = LogLevel.HEADERS }
        install(HttpCookies) { storage = cookieStorage }
    }

    /** Cookie-aware client for avatar downloads/uploads from Coil and the sync layer. */
    val download: HttpClient = createPlatformHttpClient {
        expectSuccess = false
        install(Logging) { level = LogLevel.HEADERS }
        install(HttpCookies) { storage = cookieStorage }
    }

    suspend fun register(url: String, body: CredentialsRequest): UserResponse =
        http.post(url) { jsonBody(body) }.body()

    suspend fun login(url: String, body: CredentialsRequest): UserResponse =
        http.post(url) { jsonBody(body) }.body()

    suspend fun logout(url: String): SuccessResponse = http.post(url).body()

    suspend fun me(url: String): UserResponse = http.get(url).body()

    suspend fun changePassword(url: String, body: PasswordChangeRequest): SuccessResponse =
        http.put(url) { jsonBody(body) }.body()

    suspend fun deleteAccount(url: String, body: AccountDeleteRequest): SuccessResponse =
        http.delete(url) { jsonBody(body) }.body()

    suspend fun previewRedeemCard(url: String, body: RedeemCodeRequest): CloudCardPreviewResponse =
        http.post(url) { jsonBody(body) }.body()

    suspend fun redeemCard(url: String, body: RedeemCodeRequest): CloudRedeemResponse =
        http.post(url) { jsonBody(body) }.body()

    suspend fun syncAgentsPage(url: String, since: Long, cursor: String?, limit: Int): CloudSyncAgentsPage =
        http.get(url) { syncParams(since, "agents", cursor, limit) }.body()

    suspend fun syncConversationsPage(url: String, since: Long, cursor: String?, limit: Int): CloudSyncConversationsPage =
        http.get(url) { syncParams(since, "conversations", cursor, limit) }.body()

    suspend fun syncProvidersPage(url: String, since: Long, cursor: String?, limit: Int): CloudSyncProvidersPage =
        http.get(url) { syncParams(since, "providers", cursor, limit) }.body()

    private fun HttpRequestBuilder.syncParams(since: Long, collection: String, cursor: String?, limit: Int) {
        parameter("since", since)
        parameter("collection", collection)
        cursor?.let { parameter("cursor", it) }
        parameter("limit", limit)
    }

    suspend fun listMarketAgents(url: String, query: String, cursor: String?): CloudMarketAgentListResponse =
        http.get(url) {
            parameter("query", query)
            cursor?.let { parameter("cursor", it) }
        }.body()

    suspend fun getMarketAgent(url: String): CloudMarketAgentResponse = http.get(url).body()

    suspend fun createMarketAgent(url: String, body: CloudMarketAgentRequest): CloudMarketAgentResponse =
        http.post(url) { jsonBody(body) }.body()

    suspend fun updateMarketAgent(url: String, body: CloudMarketAgentRequest): CloudMarketAgentResponse =
        http.put(url) { jsonBody(body) }.body()

    suspend fun deleteMarketAgent(url: String): SuccessResponse = http.delete(url).body()

    suspend fun putAgent(url: String, body: CloudAgentRequest): CloudUpsertResponse =
        http.put(url) { jsonBody(body) }.body()

    suspend fun deleteAgent(url: String): CloudUpsertResponse = http.delete(url).body()

    suspend fun putConversation(url: String, body: CloudConversationRequest): CloudUpsertResponse =
        http.put(url) { jsonBody(body) }.body()

    suspend fun deleteConversation(url: String): CloudUpsertResponse = http.delete(url).body()

    suspend fun putProvider(url: String, body: CloudProviderRequest): CloudUpsertResponse =
        http.put(url) { jsonBody(body) }.body()

    suspend fun deleteProvider(url: String): CloudUpsertResponse = http.delete(url).body()

    suspend fun uploadUserAvatar(url: String, fileName: String, bytes: ByteArray, mime: String): CloudAvatarResponse =
        http.put(url) { setBody(avatarForm(fileName, bytes, mime)) }.body()

    suspend fun deleteUserAvatar(url: String): CloudAvatarResponse = http.delete(url).body()

    suspend fun uploadAgentAvatar(url: String, fileName: String, bytes: ByteArray, mime: String): CloudAvatarResponse =
        http.put(url) { setBody(avatarForm(fileName, bytes, mime)) }.body()

    suspend fun deleteAgentAvatar(url: String): CloudAvatarResponse = http.delete(url).body()

    suspend fun uploadMarketAgentAvatar(url: String, fileName: String, bytes: ByteArray, mime: String): CloudAvatarResponse =
        http.put(url) { setBody(avatarForm(fileName, bytes, mime)) }.body()

    suspend fun deleteMarketAgentAvatar(url: String): CloudAvatarResponse = http.delete(url).body()

    private fun avatarForm(fileName: String, bytes: ByteArray, mime: String) =
        MultiPartFormDataContent(
            formData {
                append(
                    "file",
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, mime)
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    }
                )
            }
        )

    private fun HttpRequestBuilder.jsonBody(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}

/**
 * Session-cookie storage backed by DataStore, port of the OkHttp
 * `PersistentCookieJar`. Only the `messenger_session` cookie persists,
 * scoped to the scheme://host(:port) origin it was issued from.
 */
private class PersistentCookieStorage(private val preferences: AppPreferences) : CookiesStorage {

    override suspend fun get(requestUrl: Url): List<io.ktor.http.Cookie> {
        val session = preferences.cloudSession.first() ?: return emptyList()
        val host = preferences.cloudSessionHost.first() ?: return emptyList()
        if (host != originOf(requestUrl)) return emptyList()
        val separator = session.indexOf('=')
        if (separator <= 0) return emptyList()
        return listOf(
            io.ktor.http.Cookie(
                name = session.substring(0, separator),
                value = session.substring(separator + 1).substringBefore(';'),
                path = "/"
            )
        )
    }

    override suspend fun addCookie(requestUrl: Url, cookie: io.ktor.http.Cookie) {
        if (cookie.name != "messenger_session") return
        preferences.setCloudSession("${cookie.name}=${cookie.value}")
        preferences.setCloudSessionHost(originOf(requestUrl))
    }

    override fun close() {}

    private fun originOf(url: Url): String {
        val scheme = url.protocol.name
        val defaultPort = if (scheme == "https") 443 else 80
        return "$scheme://${url.host}" + if (url.port != defaultPort && url.port != 0) ":${url.port}" else ""
    }
}
