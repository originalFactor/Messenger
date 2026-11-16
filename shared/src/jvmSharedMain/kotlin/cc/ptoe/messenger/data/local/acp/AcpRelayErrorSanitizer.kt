/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlinx.serialization.json.*

/** Only known error codes map to locally defined diagnostics; provider text is never reflected. */
internal object AcpRelayErrorSanitizer {
    private val knownCodes = mapOf(
        "invalid_api_key" to "Provider credentials were rejected",
        "authentication_error" to "Provider credentials were rejected",
        "unauthenticated" to "Provider credentials were rejected",
        "permission_denied" to "Provider permission was denied",
        "insufficient_quota" to "Provider quota is exhausted",
        "rate_limit_exceeded" to "Provider rate limit was reached",
        "rate_limit_error" to "Provider rate limit was reached",
        "resource_exhausted" to "Provider resource limit was reached",
        "context_length_exceeded" to "Request exceeds the provider context limit",
        "overloaded_error" to "Provider is overloaded",
        "unavailable" to "Provider is unavailable",
        "invalid_argument" to "Provider rejected the request",
        "not_found" to "Provider could not find the requested resource",
    )

    /** raw must contain the entire bounded response; null means unavailable or oversized. */
    fun format(status: Int, raw: ByteArray?): ByteArray {
        val json = raw?.let { bytes ->
            try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                AcpRelayStrictJson.parse(decoder.decode(ByteBuffer.wrap(bytes)).toString()) as? JsonObject
            } catch (_: Exception) { null }
        }
        val nested = json?.get("error") as? JsonObject
        val source = nested ?: json
        fun field(name: String): String? = (source?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
        val code = listOfNotNull(field("code"), field("type"), field("status"))
            .firstNotNullOfOrNull { value -> value.lowercase(Locale.ROOT).takeIf { it in knownCodes } }
        val message = "Provider returned HTTP $status" + (code?.let { ": ${knownCodes.getValue(it)}" } ?: "")
        return buildJsonObject { put("error", buildJsonObject {
            put("code", code ?: "upstream_error"); put("message", message)
        }) }.toString().encodeToByteArray()
    }
}
