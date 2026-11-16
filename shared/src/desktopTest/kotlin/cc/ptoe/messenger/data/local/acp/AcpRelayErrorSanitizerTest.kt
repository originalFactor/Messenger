/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AcpRelayErrorSanitizerTest {
    private val key = "provider-secret+a/b"
    private fun format(status: Int, body: ByteArray?): JsonObject =
        Json.parseToJsonElement(AcpRelayErrorSanitizer.format(status, body).decodeToString()).jsonObject["error"]!!.jsonObject
    @Test fun mapsKnownCodeButNeverReflectsMessageOrSensitiveFields() {
        val body = """{"error":{"code":"context_length_exceeded","message":"Requested 150000 tokens, maximum 128000","api_key":"provider-secret+a/b","request":{"prompt":"private conversation"},"headers":{"authorization":"Bearer value"}},"request_id":"private-id"}""".encodeToByteArray()
        val result = format(400, body)
        assertEquals("context_length_exceeded", result["code"]!!.jsonPrimitive.content)
        assertEquals("Provider returned HTTP 400: Request exceeds the provider context limit", result["message"]!!.jsonPrimitive.content)
        for (secret in listOf(key, "150000", "private conversation", "private-id", "Bearer value")) assertFalse(result.toString().contains(secret))
    }
    @Test fun ignoresCredentialVariantsAndSensitiveProse() {
        val encoded = URLEncoder.encode(key, "UTF-8")
        val base64 = Base64.getEncoder().encodeToString(key.encodeToByteArray())
        for (detail in listOf("Invalid API key $key", "Credential $encoded rejected", "Bearer $base64", "password hunter2", "authorization: Bearer hidden", "Request body includes private data", "Visit https://example.test/?key=private")) {
            val body = buildJsonObject { put("error", buildJsonObject { put("code", "invalid_api_key"); put("message", detail) }) }.toString().encodeToByteArray()
            val result = format(401, body)
            assertEquals("invalid_api_key", result["code"]!!.jsonPrimitive.content)
            assertEquals("Provider returned HTTP 401: Provider credentials were rejected", result["message"]!!.jsonPrimitive.content)
            for (forbidden in listOf(key, encoded, base64, "hunter2", "hidden", "private data", "example.test")) assertFalse(result.toString().contains(forbidden))
        }
    }
    @Test fun unknownInvalidAndNonJsonBodiesReturnOnlyStatus() {
        assertEquals("Provider returned HTTP 429", format(429, "rate limit exceeded".encodeToByteArray())["message"]!!.jsonPrimitive.content)
        assertEquals("Provider returned HTTP 500", format(500, "<html>private</html>".encodeToByteArray())["message"]!!.jsonPrimitive.content)
        assertEquals("Provider returned HTTP 400", format(400, "{\"error\":\"a\",\"error\":\"b\"}".encodeToByteArray())["message"]!!.jsonPrimitive.content)
        assertEquals("Provider returned HTTP 502", format(502, byteArrayOf(0xc3.toByte(), 0x28))["message"]!!.jsonPrimitive.content)
        assertEquals("Provider returned HTTP 503", format(503, null)["message"]!!.jsonPrimitive.content)
        val unknown = format(400, """{"error":{"code":"private-identifier","message":"Invalid request"}}""".encodeToByteArray())
        assertEquals("upstream_error", unknown["code"]!!.jsonPrimitive.content)
        assertEquals("Provider returned HTTP 400", unknown["message"]!!.jsonPrimitive.content)
    }
}
