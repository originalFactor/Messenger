/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AcpRelayStrictJsonTest {
    @Test fun rejectsDuplicateKeysIncludingEscapedAliases() {
        assertFails { AcpRelayStrictJson.parse("""{"x":1,"x":2}""") }
        assertFails { AcpRelayStrictJson.parse("""{"outer":{"name":1,"na\u006de":2}}""") }
        assertEquals(1, AcpRelayStrictJson.parse("""{"left":{"x":1},"right":{"x":2}}""").jsonObject["left"]!!.jsonObject["x"]!!.jsonPrimitive.int)
    }
    @Test fun rejectsExcessiveNestingAndMalformedDocuments() {
        assertFails { AcpRelayStrictJson.parse("[".repeat(65) + "0" + "]".repeat(65)) }
        assertFails { AcpRelayStrictJson.parse("""{"x":true,}""") }
    }
}
