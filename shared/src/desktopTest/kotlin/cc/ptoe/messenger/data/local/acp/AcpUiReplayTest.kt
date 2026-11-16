/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpUiReplayTest {
    @Test fun reconnectRestoresOnlyLiveEvictedPermission(): Unit = runBlocking {
        val replay = AcpEventBuffer(maxEvents = 2, maxBytes = 4096)
        val prompt = AcpPermissionPrompt("turn", listOf(AcpVisibleOption("nonce/0", "Allow", "allow_once")), "Write", "file")
        replay.permission(prompt)
        replay.append(prompt.toUiEvent())
        replay.append(buildJsonObject { put("type", "TextDelta"); put("text", "a") })
        replay.append(buildJsonObject { put("type", "TextDelta"); put("text", "b") })
        val batch = replay.snapshot(0).toUiBatch("request")
        assertEquals(listOf("ReplayGap", "Permission", "TextDelta", "TextDelta"), batch["events"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        assertEquals("nonce/0", batch["events"]!!.jsonArray[1].jsonObject["options"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(replay.snapshot(0).latestSequence, batch["nextCursor"]!!.jsonPrimitive.long)
        assertFalse(batch["done"]!!.jsonPrimitive.boolean)
        replay.permission(null)
        replay.append(buildJsonObject { put("type", "PermissionResolved") })
        replay.complete()
        val finished = replay.snapshot(0).toUiBatch("request")
        assertTrue(finished["done"]!!.jsonPrimitive.boolean)
        assertTrue(finished["events"]!!.jsonArray.none { it.jsonObject["type"]!!.jsonPrimitive.content == "Permission" })
    }

    @Test fun retainedPermissionIsNotDuplicated(): Unit = runBlocking {
        val replay = AcpEventBuffer(maxEvents = 2, maxBytes = 4096)
        replay.append(buildJsonObject { put("type", "old") })
        val prompt = AcpPermissionPrompt("turn", listOf(AcpVisibleOption("nonce/0", "Allow", "allow_once")), "Write", "file")
        replay.permission(prompt)
        replay.append(prompt.toUiEvent())
        replay.append(buildJsonObject { put("type", "TextDelta"); put("text", "a") })
        val events = replay.snapshot(0).toUiBatch("request")["events"]!!.jsonArray
        assertEquals(listOf("ReplayGap", "Permission", "TextDelta"), events.map { it.jsonObject["type"]!!.jsonPrimitive.content })
    }
}
