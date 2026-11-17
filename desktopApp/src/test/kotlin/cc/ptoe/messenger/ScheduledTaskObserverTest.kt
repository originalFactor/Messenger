package cc.ptoe.messenger

import cc.ptoe.messenger.presentation.platform.TaskPushEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduledTaskObserverTest {
    @Test fun focusAndIdleDetermineDelivery() {
        assertEquals(false, shouldPushTaskEvent(true, IDLE_NANOS - 1))
        assertEquals(true, shouldPushTaskEvent(true, IDLE_NANOS))
        assertEquals(true, shouldPushTaskEvent(false, 0))
    }

    @Test fun taskTargetRequiresAnAccessibleIdentity() {
        assertEquals("remote", taskTargetKind("acp", "completed", "conversation", "desktop", false))
        assertEquals("remote", taskTargetKind("chat", "approval", "conversation", "desktop", false))
        assertEquals("remote", taskTargetKind("chat", "failed", "conversation", "desktop", true))
        assertEquals("local", taskTargetKind("chat", "completed", "conversation", "desktop", false))
        assertEquals("local", taskTargetKind("chat", "failed", "conversation", null, true))
        assertEquals(null, taskTargetKind("acp", "completed", "conversation", null, false))
        assertEquals(null, taskTargetKind("chat", "approval", "conversation", null, false))
        assertEquals(null, taskTargetKind("chat", "completed", null, "desktop", true))
    }

    @Test fun titleRemovesControlsAndRespectsLength() {
        assertEquals("A B", pushTitle(" A\u0000 B "))
        assertEquals("x".repeat(159), pushTitle("x".repeat(159) + "😀"))
        assertEquals(null, pushTitle("\n"))
    }

    @Test fun snapshotsReportOnlyNewCompletedRuns() {
        val observer = ScheduledTaskObserver()
        val running = "Execution interrupted before completion"
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 1, null), ScheduledTaskSnapshot("b", 2, running))))
        assertEquals(listOf(TaskPushEvent("completed", null)), observer.update(listOf(ScheduledTaskSnapshot("a", 1, null), ScheduledTaskSnapshot("b", 2, null))))
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 1, null), ScheduledTaskSnapshot("b", 2, null), ScheduledTaskSnapshot("c", 3, "old error"))))
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 4, running))))
        assertEquals(listOf(TaskPushEvent("failed", "conversation-4")), observer.update(listOf(ScheduledTaskSnapshot("a", 4, "failed", "conversation-4"))))
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 4, "failed"))))
        assertEquals(listOf(TaskPushEvent("completed", null)), observer.update(listOf(ScheduledTaskSnapshot("a", 5, null))))
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 6, running))))
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 6, "Execution cancelled"))))
    }
}
