package cc.ptoe.messenger

private const val RUNNING_SENTINEL = "Execution interrupted before completion"

internal data class ScheduledTaskSnapshot(val id: String, val run: Long?, val error: String?)

/** Diff snapshots without replaying historical runs or reporting a running task as failed. */
internal class ScheduledTaskObserver {
    private val seen = mutableMapOf<String, Long?>()
    private val pending = mutableMapOf<String, Long>()

    fun update(tasks: List<ScheduledTaskSnapshot>): List<String> {
        val events = mutableListOf<String>()
        val ids = tasks.mapTo(mutableSetOf()) { it.id }
        seen.keys.retainAll(ids)
        pending.keys.retainAll(ids)
        for ((id, run, error) in tasks) {
            val known = seen.containsKey(id)
            val previous = seen[id]
            if (run != null && error == RUNNING_SENTINEL) {
                pending[id] = run
            } else if (run != null && (pending[id] == run || (known && previous != run))) {
                if (error != "Execution cancelled") events += if (error == null) "completed" else "failed"
                pending.remove(id)
            }
            seen[id] = run
        }
        return events
    }
}
