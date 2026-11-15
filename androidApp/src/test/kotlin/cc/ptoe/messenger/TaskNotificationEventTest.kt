package cc.ptoe.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskNotificationEventTest {
    @Test fun approvalAndResolutionUseTheSameTaskIdentity() {
        val approval = taskNotificationAction("taskStatus", listOf("approval", "chat", "one")) as TaskNotificationAction.Show
        val resolved = taskNotificationAction("taskStatus", listOf("approvalResolved", "chat", "one")) as TaskNotificationAction.Clear
        assertEquals(approval.tag, resolved.tag)
        assertEquals(approval.tag, (taskNotificationAction("taskStatus", listOf("completed", "chat", "one")) as TaskNotificationAction.Show).tag)
        assertEquals(approval.tag, (taskNotificationAction("taskStatus", listOf("cancelled", "chat", "one")) as TaskNotificationAction.Clear).tag)
    }

    @Test fun distinctTasksAndKindsDoNotOverwriteEachOther() {
        val chat = taskNotificationAction("taskStatus", listOf("failed", "chat", "one")) as TaskNotificationAction.Show
        val otherChat = taskNotificationAction("taskStatus", listOf("failed", "chat", "two")) as TaskNotificationAction.Show
        val image = taskNotificationAction("localTaskStatus", listOf("completed", "image", "one")) as TaskNotificationAction.Show
        assertNotEquals(chat.tag, otherChat.tag)
        assertNotEquals(chat.tag, image.tag)
    }

    @Test fun unrelatedOrIncompleteEventsAreIgnored() {
        assertNull(taskNotificationAction("change", listOf("completed", "chat", "one")))
        assertNull(taskNotificationAction("taskStatus", listOf("completed")))
        assertNull(taskNotificationAction("taskStatus", listOf("completed", "unknown", "one")))
        assertNull(taskNotificationAction("taskStatus", listOf("started", "chat", "one")))
    }
}
