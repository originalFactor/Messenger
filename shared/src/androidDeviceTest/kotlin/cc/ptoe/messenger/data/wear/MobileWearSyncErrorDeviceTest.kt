/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.wear

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileWearSyncErrorDeviceTest {
    @Test fun syncFailureRetainsRequestIdentityAndDetails() {
        val response = mobileWearRequestError(JSONObject().put("type", "sync").put("requestId", "sync-1"),
            IllegalStateException("Conversation read failed"))
        assertEquals("sync-1", response.getString("requestId"))
        assertEquals("error", response.getString("type"))
        assertEquals("Conversation read failed", response.getString("error"))
    }
}
