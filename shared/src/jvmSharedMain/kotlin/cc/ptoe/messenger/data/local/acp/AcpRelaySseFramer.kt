/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Byte-oriented SSE framing; dispatches only complete blank-line-terminated data events. */
internal class AcpRelaySseFramer {
    private val line = ByteArrayOutputStream()
    private val data = mutableListOf<String>()
    private var size = 0L

    fun feed(chunk: ByteArray): List<String> {
        val events = mutableListOf<String>()
        for (byte in chunk) {
            if (byte == '\n'.code.toByte()) {
                val bytes = line.toByteArray(); line.reset()
                var end = bytes.size
                while (end > 0 && bytes[end - 1] == '\r'.code.toByte()) end--
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                val text = try { decoder.decode(ByteBuffer.wrap(bytes, 0, end)).toString() } catch (_: Exception) { error("Invalid UTF-8 in upstream event") }
                if (text.isEmpty()) {
                    if (data.isNotEmpty()) { events.add(data.joinToString("\n")); data.clear(); size = 0 }
                } else if (text.startsWith("data:")) {
                    val value = text.substring(5).removePrefix(" ")
                    size += value.encodeToByteArray().size + 1L
                    require(data.size < 65_536) { "Too many upstream SSE data lines" }
                    require(size <= MAX_FRAME) { "Upstream SSE frame exceeds limit" }
                    data.add(value)
                }
            } else {
                require(line.size() < MAX_FRAME) { "Upstream SSE line exceeds limit" }
                line.write(byte.toInt())
            }
        }
        return events
    }

    companion object { private const val MAX_FRAME = 8 * 1024 * 1024 }
}
