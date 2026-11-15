package cc.ptoe.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationNotificationTargetTest {
    @Test fun acceptsLocalAndRemoteConversations() {
        assertEquals(ConversationNotificationTarget("local", "acp:abc"), conversationNotificationTarget("local", "acp:abc", null))
        assertEquals(ConversationNotificationTarget("remote", "acp:abc", "desk-123"), conversationNotificationTarget("remote", "acp:abc", "desk-123"))
    }

    @Test fun rejectsUnsafeOrIncompleteTargets() {
        assertNull(conversationNotificationTarget("remote", "conversation", null))
        assertNull(conversationNotificationTarget("remote", "../conversation", "desk"))
        assertNull(conversationNotificationTarget("remote", "conversation", "desk/other"))
        assertNull(conversationNotificationTarget("local", "conversation", "desk"))
        assertNull(conversationNotificationTarget("other", "conversation", null))
    }

    @Test fun handlesDirectAndVendorWrappedExtras() {
        val expected = ConversationNotificationTarget("remote", "acp:abc", "desk-123")
        assertEquals(expected, notificationTargetFromExtras("remote", "acp:abc", "desk-123", null, null))
        assertEquals(expected, notificationTargetFromExtras(null, null, null,
            """{"kind":"remote","conversationId":"acp:abc","deviceId":"desk-123"}""", null))
        assertEquals(expected, notificationTargetFromExtras(null, null, null, null,
            """{"n_extras":{"kind":"remote","conversationId":"acp:abc","deviceId":"desk-123"}}"""))
        assertEquals(expected, notificationTargetFromExtras(null, null, null, null,
            """{"n_extras":"{\"kind\":\"remote\",\"conversationId\":\"acp:abc\",\"deviceId\":\"desk-123\"}"}"""))
    }

    @Test fun rejectsMalformedAndUnsafeWrappedExtras() {
        assertNull(notificationTargetFromExtras(null, null, null, null, "not json"))
        assertNull(notificationTargetFromExtras(null, null, null, null,
            """{"n_extras":{"kind":"remote","conversationId":"../bad","deviceId":"desk"}}"""))
        assertNull(notificationTargetFromExtras(null, null, null, "x".repeat(16_385), null))
    }
}
