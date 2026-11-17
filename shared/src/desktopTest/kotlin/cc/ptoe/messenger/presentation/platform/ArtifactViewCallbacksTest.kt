/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ArtifactViewCallbacksTest {
    @Test fun capabilityKeysUseReferenceIdentity() {
        val first = String(charArrayOf('x'))
        val second = String(charArrayOf('x'))
        assertNotSame(first, second)
        assertEquals(first, second)
        assertEquals(ArtifactBindingIdentity(first), ArtifactBindingIdentity(first))
        assertNotEquals(ArtifactBindingIdentity(first), ArtifactBindingIdentity(second))
    }

    @Test fun disposalRevokesTheExactHandlerAndSuppressesLateReplies() {
        var calls = 0
        var disposeCount = 0
        var delayed: ((String) -> Unit)? = null
        val replies = mutableListOf<String>()
        val binding = ArtifactViewCallbacks({ _, reply -> calls++; delayed = reply }, { disposeCount++ })
        assertTrue(binding.active)
        binding.dispatch("first") { replies.add(it) }
        checkNotNull(delayed)("before")
        binding.close()
        binding.close()
        checkNotNull(delayed)("after")
        binding.dispatch("later") { replies.add(it) }
        assertFalse(binding.active)
        assertEquals(1, calls)
        assertEquals(1, disposeCount)
        assertEquals(listOf("before"), replies)
    }
}
