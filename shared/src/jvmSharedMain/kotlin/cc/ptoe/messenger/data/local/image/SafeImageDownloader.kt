/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Public HTTPS only; resolve and pin validated DNS per hop, with no proxy or automatic redirect. */
internal class SafeImageDownloader {
    suspend fun download(source: String): Pair<ByteArray, String> = withTimeout(60_000) {
        require(source.toByteArray(Charsets.UTF_8).size <= 8192) { "Image URL exceeds limit" }
        var url = source.toHttpUrlOrNull() ?: error("Invalid image URL")
        repeat(4) { hop ->
            require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()) { "Image URL requires public HTTPS on port 443" }
            val host = url.host
            val addresses = withContext(Dispatchers.IO) { InetAddress.getAllByName(host).toList() }
            require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) { "Image URL resolves to a non-public address" }
            val pinned = Dns { requested -> if (requested == host) addresses else throw UnknownHostException("Unexpected host") }
            val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(pinned).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
                // OkHttp otherwise retries a 503 carrying Retry-After: 0 even with connection retries disabled.
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
                }.callTimeout(30, TimeUnit.SECONDS).build()
            val response = try { execute(client.newCall(Request.Builder().url(url).get().build())) }
                finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
            if (response.status in 300..399) {
                require(hop < 3) { "Image redirect limit exceeded" }
                url = url.resolve(response.location ?: error("Invalid image redirect")) ?: error("Invalid image redirect")
            } else {
                check(response.status in 200..299) { "Image download HTTP ${response.status}" }
                return@withTimeout response.bytes to response.mime
            }
        }
        error("Image redirect limit exceeded")
    }

    private data class Download(val status: Int, val location: String?, val mime: String, val bytes: ByteArray)
    private suspend fun execute(call: Call): Download = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val source = it.body.source()
                        source.request(20L * 1024 * 1024 + 1)
                        require(source.buffer.size <= 20L * 1024 * 1024) { "Image download exceeds byte limit" }
                        Download(it.code, it.header("Location"), it.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase(), source.readByteArray())
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
}

internal fun isPublicAddress(address: InetAddress): Boolean = when (address) {
    is Inet4Address -> {
        val octets = address.address.map { it.toInt() and 255 }
        val (a, b, c, d) = octets
        !(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress || a == 0 || a >= 240 || (a == 255 && b == 255 && c == 255 && d == 255) ||
            (a == 100 && b in 64..127) || (a == 198 && b in 18..19) || (a == 192 && b == 0 && c == 0) ||
            (a == 192 && b == 0 && c == 2) || (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113))
    }
    is Inet6Address -> {
        val octets = address.address.map { it.toInt() and 255 }
        val first = (octets[0] shl 8) or octets[1]
        val second = (octets[2] shl 8) or octets[3]
        first and 0xe000 == 0x2000 && first != 0x2001 && first != 0x2002 && !(first == 0x3fff && second < 0x1000)
    }
    else -> false
}
