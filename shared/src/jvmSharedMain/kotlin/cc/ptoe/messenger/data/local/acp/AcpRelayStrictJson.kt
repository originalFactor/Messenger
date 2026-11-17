/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*

/** Rejects duplicate keys and bounds tree complexity before JSON materialization. */
internal object AcpRelayStrictJson {
    fun parse(raw: String): JsonElement {
        rejectAmbiguousJson(raw)
        return Json.parseToJsonElement(raw)
    }
    private fun rejectAmbiguousJson(raw: String) {
        var pos = 0
        var nodes = 0
        fun space() { while (pos < raw.length && raw[pos] in " \t\r\n") pos++ }
        fun quoted(): String {
            space(); val start = pos
            require(pos < raw.length && raw[pos++] == '"') { "Invalid relay JSON" }
            while (pos < raw.length) {
                val char = raw[pos++]
                if (char == '\\') { require(pos < raw.length) { "Invalid relay JSON" }; pos++ }
                else if (char == '"') return raw.substring(start, pos)
            }
            error("Invalid relay JSON")
        }
        fun value(depth: Int) {
            require(depth <= 64 && ++nodes <= 200_000) { "Relay JSON exceeds structural limit" }
            space(); require(pos < raw.length) { "Invalid relay JSON" }
            when (raw[pos]) {
                '{' -> {
                    pos++; space(); val names = mutableSetOf<String>()
                    if (pos < raw.length && raw[pos] == '}') { pos++; return }
                    while (true) {
                        val encoded = quoted()
                        val name = try { (Json.parseToJsonElement(encoded) as? JsonPrimitive)?.takeIf { it.isString }?.content } catch (_: Exception) { null }
                            ?: error("Invalid relay JSON")
                        require(names.add(name)) { "Duplicate JSON field" }
                        space(); require(pos < raw.length && raw[pos++] == ':') { "Invalid relay JSON" }
                        value(depth + 1); space(); require(pos < raw.length) { "Invalid relay JSON" }
                        val separator = raw[pos++]
                        if (separator == '}') return
                        require(separator == ',') { "Invalid relay JSON" }
                    }
                }
                '[' -> {
                    pos++; space(); if (pos < raw.length && raw[pos] == ']') { pos++; return }
                    while (true) {
                        value(depth + 1); space(); require(pos < raw.length) { "Invalid relay JSON" }
                        val separator = raw[pos++]
                        if (separator == ']') return
                        require(separator == ',') { "Invalid relay JSON" }
                    }
                }
                '"' -> { quoted() }
                else -> {
                    val start = pos
                    while (pos < raw.length && raw[pos] !in ",]} \t\r\n") pos++
                    require(pos > start) { "Invalid relay JSON" }
                }
            }
        }
        value(0); space(); require(pos == raw.length) { "Invalid relay JSON" }
    }

}
