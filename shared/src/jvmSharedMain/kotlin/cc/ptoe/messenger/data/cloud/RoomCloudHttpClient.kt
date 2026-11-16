/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.Protocol

/** A Cloud failure must never cause OkHttp to repeat an authenticated request. */
internal fun createRoomCloudHttpClient(block: HttpClientConfig<OkHttpConfig>.() -> Unit = {}): HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(10, TimeUnit.SECONDS)
                readTimeout(30, TimeUnit.SECONDS)
                writeTimeout(30, TimeUnit.SECONDS)
                retryOnConnectionFailure(false)
                followRedirects(false)
                followSslRedirects(false)
                authenticator(Authenticator.NONE)
                proxyAuthenticator(Authenticator.NONE)
                // Prevent HTTP/2 coalescing from triggering a 421 follow-up.
                protocols(listOf(Protocol.HTTP_1_1))
                // OkHttp otherwise repeats a 503 with Retry-After: 0 even when
                // retryOnConnectionFailure is disabled.
                addNetworkInterceptor { chain ->
                    chain.proceed(chain.request()).let { response ->
                        if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
                    }
                }
            }
        }
        followRedirects = false
        expectSuccess = false
        block()
    }

/** Dedicated transport: session cookies and AI keys never follow redirects or use an ambient jar. */
internal class RoomCloudHttpClient : AutoCloseable {
    internal val http = createRoomCloudHttpClient()
    override fun close() = http.close()
}
