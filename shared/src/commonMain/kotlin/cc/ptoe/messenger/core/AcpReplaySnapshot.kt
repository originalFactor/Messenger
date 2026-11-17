/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** A saved agent replay is separate from the local chat transcript. */
data class AcpReplaySnapshot(val id: String, val capturedAt: Long, val eventCount: Int, val byteCount: Int)
data class AcpReplayPage(val source: JsonObject, val events: JsonArray, val next: Int?)
