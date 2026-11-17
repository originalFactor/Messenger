/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
 */
package cc.ptoe.messenger.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareDelete
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePut
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A session identity must change whenever the cookie or server changes, including re-login. */
internal data class SyncSession(val accountId: String, val generation: String)
internal enum class SyncCollection(val path: String) {
    AGENT("agents"), PROVIDER("providers"), PROJECT("projects"), CONVERSATION("conversations")
}
internal enum class SyncMutation { UPSERT, DELETE }
/** Revision identifies the queued edit, so acknowledging an older push cannot erase a newer edit. */
internal data class PendingSyncChange(
    val collection: SyncCollection,
    val id: String,
    val mutation: SyncMutation,
    val revision: Long,
    val body: JsonObject? = null,
) {
    override fun toString(): String = "PendingSyncChange(collection=$collection, id=$id, mutation=$mutation, revision=$revision, body=redacted)"
}
internal data class SyncPage(val documents: List<JsonObject>, val latestVersion: Long, val nextCursor: String?)
internal data class PulledCloudDelta(val documents: Map<SyncCollection, List<JsonObject>>, val latestVersion: Long)
internal class CloudSessionExpired : IllegalStateException("Cloud session expired")

/**
 * Persistence implementation must check session identity for every operation. commitPull must be
 * one transaction: replace only synced chat/provider/project records (preserving learning records,
 * assets and application data), apply tombstones with domain invariants, preserve pending edits,
 * then persist the cursor. Never expose partial restore or advance the cursor on failure.
 */
internal interface KotlinSyncStore {
    suspend fun session(): SyncSession
    suspend fun cursor(session: SyncSession): Long
    suspend fun pending(session: SyncSession): List<PendingSyncChange>
    suspend fun acknowledgePush(session: SyncSession, change: PendingSyncChange)
    suspend fun commitPull(session: SyncSession, delta: PulledCloudDelta, replaceLocal: Boolean)
    /** Clear only if this session is still current; a late 401 must not sign out a newer login. */
    suspend fun expireIfCurrent(session: SyncSession)
}
internal interface KotlinSyncTransport {
    /** Identity and credentials are fixed for the transport's entire lifetime. */
    val boundSession: SyncSession
    suspend fun page(collection: SyncCollection, since: Long, ack: Long?, cursor: String?): SyncPage
    suspend fun push(change: PendingSyncChange): Long
}

/** Uses a client without a mutable cookie jar; the captured cookie is bound to this session. */
internal class KtorSyncTransport(
    private val client: HttpClient,
    baseUrl: String,
    override val boundSession: SyncSession,
    private val sessionCookie: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : KotlinSyncTransport {
    private val root = baseUrl.trimEnd('/')

    override suspend fun page(collection: SyncCollection, since: Long, ack: Long?, cursor: String?): SyncPage {
        return client.prepareGet("$root/api/sync") {
            header(HttpHeaders.Cookie, sessionCookie)
            parameter("collection", collection.path)
            parameter("since", since)
            parameter("limit", 200)
            ack?.let { parameter("ack", it) }
            cursor?.let { parameter("cursor", it) }
        }.execute { response ->
            if (response.status.value == 401) throw CloudSessionExpired()
            check(response.status.value in 200..299) { "Cloud sync HTTP ${response.status.value}" }
            val payload = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: error("Invalid cloud sync page")
            val documents = (payload["documents"] as? JsonArray)?.map {
                it as? JsonObject ?: error("Invalid cloud sync document")
            } ?: error("Cloud sync page has no documents")
            val version = payload["latestVersion"]?.jsonPrimitive?.longOrNull
                ?: error("Cloud sync page has no version")
            check(payload["collection"]?.jsonPrimitive?.contentOrNull == collection.path) { "Cloud sync collection mismatch" }
            val hasMore = payload["hasMore"]?.jsonPrimitive?.booleanOrNull
                ?: error("Cloud sync page has no hasMore flag")
            val next = payload["nextCursor"]?.jsonPrimitive?.contentOrNull
            check(!hasMore || !next.isNullOrBlank()) { "Cloud sync page has no next cursor" }
            SyncPage(documents, version, if (hasMore) next else null)
        }
    }

    override suspend fun push(change: PendingSyncChange): Long {
        val url = "$root/api/${change.collection.path}/${change.id.encodeURLPathPart().replace("/", "%2F")}"
        val statement = when (change.mutation) {
            SyncMutation.DELETE -> client.prepareDelete(url) { header(HttpHeaders.Cookie, sessionCookie) }
            SyncMutation.UPSERT -> client.preparePut(url) {
                header(HttpHeaders.Cookie, sessionCookie)
                contentType(ContentType.Application.Json)
                setBody(requireNotNull(change.body) { "An upsert requires a snapshot" }.toString())
            }
        }
        return statement.execute { response ->
            if (response.status.value == 401) throw CloudSessionExpired()
            check(response.status.value in 200..299) { "Cloud sync HTTP ${response.status.value}" }
            val payload = json.parseToJsonElement(response.bodyAsText()) as? JsonObject
                ?: error("Invalid cloud sync push response")
            payload["version"]?.jsonPrimitive?.longOrNull ?: error("Cloud sync push has no version")
        }
    }
}

/** Serializes sync cycles. Failures propagate; no network request is automatically retried. */
internal class KotlinCloudSyncEngine(private val store: KotlinSyncStore, private val transport: KotlinSyncTransport) {
    private val mutex = Mutex()

    private suspend fun push(session: SyncSession) {
        // Deletes first, then agents/providers/projects before conversations.
        val changes = store.pending(session).sortedWith(compareBy<PendingSyncChange>(
            { if (it.mutation == SyncMutation.DELETE) 0 else 1 }, { it.collection.ordinal }
        ))
        for (change in changes) {
            transport.push(change)
            store.acknowledgePush(session, change)
        }
    }

    /** Send queued edits only; a local edit must never initiate an implicit pull. */
    suspend fun pushPending() = mutex.withLock {
        val session = store.session()
        check(session == transport.boundSession) { "Cloud sync transport belongs to another session" }
        try { push(session) }
        catch (error: ResponseException) {
            if (error.response.status.value == 401) { store.expireIfCurrent(session); throw CloudSessionExpired() }
            throw error
        } catch (error: CloudSessionExpired) { store.expireIfCurrent(session); throw error }
    }

    suspend fun sync(replaceLocal: Boolean = false, pushPending: Boolean = false): PulledCloudDelta = mutex.withLock {
        val session = store.session()
        check(session == transport.boundSession) { "Cloud sync transport belongs to another session" }
        try {
            if (pushPending) {
                push(session)
            }
            val durableCursor = store.cursor(session)
            val since = if (replaceLocal) 0L else durableCursor
            val ack = durableCursor.takeIf { since != 0L && it > 0L }
            val documents = linkedMapOf<SyncCollection, List<JsonObject>>()
            // Each server page reads a fresh upper bound. Advancing beyond the earliest
            // observed bound can skip changes in a collection already fetched.
            var latest: Long? = null
            for (collection in SyncCollection.entries) {
                val rows = mutableListOf<JsonObject>()
                val seen = mutableSetOf<String>()
                var next: String? = null
                do {
                    val page = transport.page(collection, since, ack, next)
                    rows.addAll(page.documents)
                    latest = minOf(latest ?: page.latestVersion, page.latestVersion)
                    next = page.nextCursor
                    check(next == null || seen.add(next)) { "Cloud sync cursor repeated" }
                } while (next != null)
                documents[collection] = rows
            }
            val safeVersion = checkNotNull(latest)
            check(safeVersion >= since) { "Cloud sync cursor moved backwards: since=$since latest=$safeVersion" }
            val delta = PulledCloudDelta(documents, safeVersion)
            store.commitPull(session, delta, replaceLocal)
            delta
        } catch (error: ResponseException) {
            if (error.response.status.value == 401) {
                store.expireIfCurrent(session)
                throw CloudSessionExpired()
            }
            throw error
        } catch (error: CloudSessionExpired) {
            store.expireIfCurrent(session)
            throw error
        }
    }
}
