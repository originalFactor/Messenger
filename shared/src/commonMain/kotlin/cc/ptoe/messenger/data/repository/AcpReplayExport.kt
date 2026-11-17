/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.core.AcpReplaySnapshot
import cc.ptoe.messenger.core.CoreBridge
import kotlinx.serialization.json.*

/** Assemble only a complete, consistently sourced snapshot; never export partial pages. */
internal suspend fun exportAcpReplay(core: CoreBridge, sessionId: String, snapshot: AcpReplaySnapshot): ByteArray {
    require(snapshot.eventCount in 0..10_000 && snapshot.byteCount in 1..16 * 1024 * 1024) { "Invalid ACP replay snapshot" }
    val events = mutableListOf<JsonElement>()
    var source: JsonObject? = null
    var cursor = 0
    var bytes = 0L
    while (true) {
        val page = core.readAcpReplaySnapshot(sessionId, snapshot.id, cursor)
        val previous = source
        if (previous == null) source = page.source else check(previous == page.source) { "ACP replay source changed" }
        check((page.source["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull == sessionId) { "ACP replay session changed" }
        bytes += page.events.sumOf { it.toString().encodeToByteArray().size.toLong() + 1 }
        check(bytes <= 16L * 1024 * 1024 && events.size + page.events.size <= snapshot.eventCount) { "ACP replay exceeds limit" }
        events.addAll(page.events)
        val next = page.next ?: break
        check(page.events.isNotEmpty() && next == cursor + page.events.size && next < snapshot.eventCount) { "Invalid ACP replay cursor" }
        cursor = next
    }
    check(events.size == snapshot.eventCount) { "ACP replay is incomplete" }
    val payload = JsonObject(checkNotNull(source) + ("events" to JsonArray(events)))
    check(payload.toString().encodeToByteArray().size == snapshot.byteCount) { "ACP replay size changed" }
    return buildJsonObject {
        put("format", "messenger-acp-replay"); put("version", 1); put("snapshotId", snapshot.id)
        put("capturedAt", snapshot.capturedAt); put("snapshot", payload)
    }.toString().encodeToByteArray()
}
