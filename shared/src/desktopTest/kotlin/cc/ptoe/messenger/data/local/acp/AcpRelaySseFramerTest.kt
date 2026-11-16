/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class AcpRelaySseFramerTest {
    @Test fun preservesUtf8AcrossChunksAndDispatchesMultilineEvents() {
        val framer = AcpRelaySseFramer()
        val encoded = ": keepalive\r\nevent: ignored\r\ndata:你\r\ndata: two\r\n\r\ndata: [DONE]\n\n".encodeToByteArray()
        val result = encoded.flatMap { framer.feed(byteArrayOf(it)) }
        assertEquals(listOf("你\ntwo", "[DONE]"), result)
    }
    @Test fun rejectsInvalidUtf8AndOversizedLines() {
        assertFails { AcpRelaySseFramer().feed(byteArrayOf(0xc3.toByte(), 0x28, 10)) }
        assertFails { AcpRelaySseFramer().feed(ByteArray(8 * 1024 * 1024 + 1) { 'x'.code.toByte() }) }
    }
    @Test fun incompleteEventIsNotDispatched() {
        val framer = AcpRelaySseFramer()
        assertEquals(emptyList(), framer.feed("data: partial\n".encodeToByteArray()))
        assertEquals(listOf("partial"), framer.feed("\n".encodeToByteArray()))
    }
}
