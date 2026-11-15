package cc.ptoe.messenger

import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduledTaskObserverTest {
    @Test fun focusAndIdleDetermineDelivery() {
        assertEquals(false, shouldPushTaskEvent(true, IDLE_NANOS - 1))
        assertEquals(true, shouldPushTaskEvent(true, IDLE_NANOS))
        assertEquals(true, shouldPushTaskEvent(false, 0))
    }

    @Test fun snapshotsReportOnlyNewCompletedRuns() {
        val observer = ScheduledTaskObserver()
        val running = "Execution interrupted before completion"
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 1, null), ScheduledTaskSnapshot("b", 2, running))))
        assertEquals(listOf("completed"), observer.update(listOf(ScheduledTaskSnapshot("a", 1, null), ScheduledTaskSnapshot("b", 2, null))))
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 1, null), ScheduledTaskSnapshot("b", 2, null), ScheduledTaskSnapshot("c", 3, "old error"))))
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 4, running))))
        assertEquals(listOf("failed"), observer.update(listOf(ScheduledTaskSnapshot("a", 4, "failed"))))
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 4, "failed"))))
        assertEquals(listOf("completed"), observer.update(listOf(ScheduledTaskSnapshot("a", 5, null))))
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 6, running))))
        assertEquals(emptyList(), observer.update(listOf(ScheduledTaskSnapshot("a", 6, "Execution cancelled"))))
    }
}
