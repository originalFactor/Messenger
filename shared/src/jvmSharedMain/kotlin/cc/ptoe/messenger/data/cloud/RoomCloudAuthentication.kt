/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.local.room.CloudSyncDao
import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.util.randomUuid
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal data class StoredCloudSession(val session: SyncSession, val cookie: String, val host: String, val user: CloudUser) {
    override fun toString(): String = "StoredCloudSession(session=$session, host=$host, credentials=redacted)"
}

/** Isolated migration building block; the transport disables redirects and has no cookie jar. */
internal class RoomCloudAuthentication(private val dao: CloudSyncDao, private val transport: RoomCloudHttpClient,
    private val providerGate: RoomCloudProviderGate = RoomCloudProviderGate.forDao(dao)) {
    init { require(providerGate === RoomCloudProviderGate.forDao(dao)) { "Cloud gate belongs to another Room database" } }
    private val mutex = Mutex()
    private val deviceFence = RoomDeviceControlFence.forDao(dao)
    private val expired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = expired
    fun acknowledgeSessionExpired() { expired.value = false }

    suspend fun restore(baseUrl: String): StoredCloudSession? = mutex.withLock {
        deviceFence.mutate { dao.restoreCredentials(randomUuid(), canonicalCloudBase(baseUrl)) }
    }

    suspend fun login(baseUrl: String, email: String, password: String, register: Boolean = false): StoredCloudSession = mutex.withLock {
        val host = canonicalCloudBase(baseUrl)
        val endpoint = if (register) "register" else "login"
        val response = transport.http.post("$host/api/auth/$endpoint") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("email", JsonPrimitive(email.trim())); put("password", JsonPrimitive(password)) }.toString())
        }
        // Credential failures, including 401, do not affect an existing session.
        check(response.status.value in 200..299) { "Cloud authentication HTTP ${response.status.value}: ${response.bodyAsText()}" }
        val payload = NetworkClient.json.parseToJsonElement(response.bodyAsText()).jsonObject
        val user = NetworkClient.json.decodeFromJsonElement(CloudUser.serializer(), requireNotNull(payload["user"]))
        check(user.id.isNotBlank()) { "Cloud authentication returned no account ID" }
        val cookie = response.headers.getAll(HttpHeaders.SetCookie).orEmpty().asSequence()
            .map { it.substringBefore(';').trim() }
            .firstOrNull { it.startsWith("messenger_session=") && it.length > "messenger_session=".length }
            ?: error("Cloud authentication returned no session cookie")
        val result = StoredCloudSession(SyncSession(user.id, randomUuid()), cookie, host, user)
        deviceFence.mutate { dao.saveCredentials(result) }
        expired.value = false
        result
    }

    suspend fun expireIfCurrent(session: SyncSession) {
        if (providerGate.revoke(session)) expired.value = true
    }

    /** Clear locally after confirmed account deletion; never clear a newer login. */
    suspend fun forgetIfCurrent(session: SyncSession): Boolean = providerGate.revoke(session)

    suspend fun logout(baseUrl: String) = mutex.withLock {
        val base = canonicalCloudBase(baseUrl)
        val current = deviceFence.mutate { dao.restoreCredentials(randomUuid(), base) } ?: return@withLock
        // Invalidate locally before waiting on the best-effort remote logout request.
        if (!providerGate.revoke(current.session)) return@withLock
        try {
            transport.http.post("$base/api/auth/logout") { header(HttpHeaders.Cookie, current.cookie) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Logout still clears the local session if the server is unreachable.
        }
    }
}
