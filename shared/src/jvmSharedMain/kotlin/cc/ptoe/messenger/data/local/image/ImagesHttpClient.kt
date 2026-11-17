/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

internal class ImagesHttpFailure(val status: Int, val detail: String) : Exception("Provider HTTP $status: $detail")
internal class ImagesOutcomeUnknown(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable internal data class ImagesOutput(
    @SerialName("b64_json") val base64: String? = null, val url: String? = null,
    @SerialName("revised_prompt") val revisedPrompt: String? = null,
)
@Serializable internal data class ImagesResponse(val data: List<ImagesOutput>, val usage: kotlinx.serialization.json.JsonElement? = null)

/** A dedicated client: no redirect, authentication follow-up, connection retry, or replayable body. */
internal class ImagesHttpClient : AutoCloseable {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(okhttp3.Authenticator.NONE)
        .proxyAuthenticator(okhttp3.Authenticator.NONE).callTimeout(180, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generate(job: ImageJob, credentials: ImageCredentials): ImagesResponse {
        val payload = JsonObject(job.parameters + mapOf("model" to JsonPrimitive(job.profile.modelId), "prompt" to JsonPrimitive(job.request.prompt)))
        val bytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val body = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
        }
        val request = Request.Builder().url(credentials.baseUrl.trimEnd('/') + "/images/generations")
            .header("Authorization", "Bearer ${credentials.apiKey}").post(body).build()
        val response = execute(http.newCall(request))
        if (response.first !in 200..299) throw ImagesHttpFailure(response.first, response.second.toString(Charsets.UTF_8))
        val result = try { json.decodeFromString<ImagesResponse>(response.second.toString(Charsets.UTF_8)) }
            catch (error: Exception) { throw ImagesOutcomeUnknown("Invalid provider response", error) }
        if (result.data.isEmpty() || result.data.size > 4 || result.data.any { (it.base64 == null) == (it.url == null) }) {
            throw ImagesOutcomeUnknown("Provider returned invalid image outputs")
        }
        return result
    }

    private suspend fun execute(call: Call): Pair<Int, ByteArray> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(ImagesOutcomeUnknown("Provider request failed; outcome is unknown", error))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val limit = if (it.isSuccessful) 112L * 1024 * 1024 else 64L * 1024
                        val source = it.body.source()
                        source.request(limit + 1)
                        if (source.buffer.size > limit) throw ImagesOutcomeUnknown("Provider response exceeds limit")
                        it.code to source.readByteArray()
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (error is ImagesOutcomeUnknown) error else ImagesOutcomeUnknown("Provider response interrupted", error))
                }
            }
        })
    }
    override fun close() { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
}
