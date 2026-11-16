/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One complete, bounded error body; never reflect partial, malformed or unbounded data. */
internal object CloudProviderError {
    private const val MAX_BYTES = 16 * 1024
    private const val MAX_MESSAGE_CHARS = 2048
    private const val READ_TIMEOUT_MS = 3000L
    private val credentialAssignment = Regex("(?i)\\b(authorization|(?:x[-_])?api[-_ ]?key|password|secret|(?:access[-_]?|refresh[-_]?)?token|messenger_session|session[-_]?id|cookie|set-cookie)[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;}\\]]+)")
    private val bearer = Regex("(?i)\\bbearer\\s+[^\\s,;}\\]]+")

    suspend fun describe(response: HttpResponse, secrets: List<String>): String {
        val prefix = "HTTP ${response.status.value}"
        val bytes = try {
            withTimeoutOrNull(READ_TIMEOUT_MS) { response.bodyAsChannel().readRemaining(MAX_BYTES.toLong() + 1).readByteArray() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        if (bytes == null) return "$prefix: error details unavailable"
        if (bytes.size > MAX_BYTES) return "$prefix: error response exceeded size limit"
        val raw = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().trim()
        } catch (_: Exception) { return prefix }
        if (raw.isEmpty()) return prefix
        val body = parseMessage(raw) ?: return prefix
        val safe = secrets.filter(String::isNotEmpty).fold(body) { value, secret -> value.replace(secret, "[redacted]") }
            .replace(bearer, "Bearer [redacted]")
            .replace(credentialAssignment) { match -> match.groupValues[1] + "=[redacted]" }
            .map { if (it.isISOControl() && it != '\n' && it != '\t') ' ' else it }.joinToString("").trim()
        if (safe.isEmpty()) return prefix
        return "$prefix: " + if (safe.length > MAX_MESSAGE_CHARS) safe.take(MAX_MESSAGE_CHARS) + "…" else safe
    }

    private fun parseMessage(raw: String): String? {
        if (raw.startsWith('{') || raw.startsWith('[') || raw.startsWith('"')) {
            if (!boundedNesting(raw)) return null
            val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return null
            if (parsed is JsonPrimitive && parsed.isString) return parsed.content
            val root = parsed as? JsonObject ?: return null
            fun strings(obj: JsonObject) = listOf("message", "detail").mapNotNull { key ->
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            val error = root["error"]
            val nested = when (error) {
                is JsonObject -> strings(error)
                is JsonPrimitive -> if (error.isString) listOf(error.content) else emptyList()
                else -> emptyList()
            }
            return (strings(root) + nested).filter(String::isNotBlank).distinct().joinToString(" — ").takeIf(String::isNotBlank)
        }
        return raw
    }

    /** Bound nesting before passing provider-controlled JSON to the recursive parser. */
    private fun boundedNesting(raw: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 32) return false
                '}', ']' -> if (--depth < 0) return false
            }
        }
        return depth == 0 && !quoted
    }
}
