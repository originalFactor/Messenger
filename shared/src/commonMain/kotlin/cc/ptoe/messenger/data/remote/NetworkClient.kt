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

package cc.ptoe.messenger.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.json.Json

/**
 * Shared Ktor [HttpClient] factory for OpenAI-compatible providers.
 * Clients are cached per (baseUrl, apiKey) pair so connection pools are
 * reused across chat streams and model syncs, mirroring the previous
 * Retrofit/OkHttp cache semantics.
 */
@OptIn(ExperimentalAtomicApi::class)
object NetworkClient {

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    private val clientCache = AtomicReference<Map<String, HttpClient>>(emptyMap())

    fun clientFor(baseUrl: String, apiKey: String): HttpClient {
        val normalizedUrl = ensureTrailingSlash(baseUrl)
        val cacheKey = "$normalizedUrl|$apiKey"
        clientCache.load()[cacheKey]?.let { return it }

        val newClient = buildClient(apiKey)
        while (true) {
            val current = clientCache.load()
            current[cacheKey]?.let { return it }
            val next = current + (cacheKey to newClient)
            if (clientCache.compareAndSet(current, next)) return newClient
        }
    }

    private fun buildClient(apiKey: String): HttpClient = createPlatformHttpClient {
        // Non-2xx responses throw ResponseException so callers can inspect
        // status code + error body (the old Retrofit HttpException path).
        expectSuccess = true
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            level = LogLevel.HEADERS
            sanitizeHeader { header -> header == HttpHeaders.Authorization }
        }
        defaultRequest {
            if (apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }
    }

    private fun ensureTrailingSlash(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }
}
