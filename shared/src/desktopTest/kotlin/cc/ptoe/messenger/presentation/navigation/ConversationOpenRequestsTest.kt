package cc.ptoe.messenger.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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

    @Test fun missingChatAfterFailedRefreshKeepsOpenRequestForExplicitRetry() = runBlocking {
        ConversationOpenRequests.open("conversation")
        val request = requireNotNull(ConversationOpenRequests.requests.value)
        var reports = 0
        var opened = false
        handleConversationOpenRequest(request) {
            if (prepareLocalConversation(true, sync = { error("offline") }, exists = { false }, onSyncFailure = { reports++ })) opened = true
        }
        val failed = requireNotNull(ConversationOpenRequests.requests.value)
        assertEquals("offline", failed.error)
        assertEquals(false, opened)
        assertEquals(0, reports)

        ConversationOpenRequests.retry(failed)
        val retry = requireNotNull(ConversationOpenRequests.requests.value)
        handleConversationOpenRequest(retry) {
            if (prepareLocalConversation(true, sync = {}, exists = { true }, onSyncFailure = { reports++ })) opened = true
        }
        assertEquals(true, opened)
        assertNull(ConversationOpenRequests.requests.value)
    }

    @Test fun cancelledNavigationIsRetriedByTheNextOwner() = runBlocking {
        ConversationOpenRequests.open("conversation")
        val request = requireNotNull(ConversationOpenRequests.requests.value)
        val entered = CompletableDeferred<Unit>()
        val firstOwner = launch {
            handleConversationOpenRequest(request, open = { entered.complete(Unit); awaitCancellation() })
        }
        entered.await()
        firstOwner.cancelAndJoin()
        assertEquals(request, ConversationOpenRequests.requests.value)

        var opened: String? = null
        handleConversationOpenRequest(request, open = { opened = request.conversationId })
        assertEquals("conversation", opened)
        assertNull(ConversationOpenRequests.requests.value)
    }

    @Test fun failedOpenNeedsExplicitRetryAndOlderFailuresCannotReplaceNewRequests() = runBlocking {
        ConversationOpenRequests.open("first")
        val first = requireNotNull(ConversationOpenRequests.requests.value)
        var calls = 0
        handleConversationOpenRequest(first, open = { calls++; error("database unavailable") })
        val failed = requireNotNull(ConversationOpenRequests.requests.value)
        assertEquals("database unavailable", failed.error)
        assertEquals(1, calls)
        ConversationOpenRequests.retry(failed)
        val retry = requireNotNull(ConversationOpenRequests.requests.value)
        assertNull(retry.error)
        assertNotEquals(first, retry)
        ConversationOpenRequests.fail(first, IllegalStateException("stale failure"))
        ConversationOpenRequests.consume(first)
        assertEquals(retry, ConversationOpenRequests.requests.value)
        handleConversationOpenRequest(retry, open = { calls++ })
        assertEquals(2, calls)
        assertNull(ConversationOpenRequests.requests.value)

        ConversationOpenRequests.open("old")
        val old = requireNotNull(ConversationOpenRequests.requests.value)
        handleConversationOpenRequest(old, open = { ConversationOpenRequests.open("new"); error("old failed") })
        val newer = requireNotNull(ConversationOpenRequests.requests.value)
        assertEquals("new", newer.conversationId)
        assertNull(newer.error)
        ConversationOpenRequests.retry(failed)
        ConversationOpenRequests.consume(failed)
        assertEquals(newer, ConversationOpenRequests.requests.value)
        ConversationOpenRequests.consume(newer)
    }
}
