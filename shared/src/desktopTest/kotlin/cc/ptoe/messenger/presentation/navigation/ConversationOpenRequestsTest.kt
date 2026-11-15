package cc.ptoe.messenger.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ConversationOpenRequestsTest {
    @Test fun coldStartAndRepeatedNotificationTapsAreDeliveredIndependently() {
        ConversationOpenRequests.open("conversation", "device")
        val cold = requireNotNull(ConversationOpenRequests.requests.value)
        assertEquals("device", cold.deviceId)
        ConversationOpenRequests.consume(cold)
        assertNull(ConversationOpenRequests.requests.value)

        ConversationOpenRequests.open("conversation", "device")
        val warm = requireNotNull(ConversationOpenRequests.requests.value)
        assertNotEquals(cold.token, warm.token)
        // A delayed completion of the first navigation must not eat a later tap.
        ConversationOpenRequests.consume(cold)
        assertEquals(warm, ConversationOpenRequests.requests.value)
        ConversationOpenRequests.consume(warm)
    }

    @Test fun failedRefreshStillOpensLocalConversationWithoutRetry() = runBlocking {
        var syncCalls = 0
        var reported: Throwable? = null
        val opened = prepareLocalConversation(
            signedIn = true,
            sync = { syncCalls++; error("offline") },
            exists = { true },
            onSyncFailure = { reported = it },
        )
        assertEquals(true, opened)
        assertEquals(1, syncCalls)
        assertEquals("offline", reported?.message)
    }
}
