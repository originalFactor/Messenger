package cc.ptoe.messenger.presentation.platform

/** Core-owned task identity for the optional platform push handler. */
data class TaskPushEvent(val event: String, val conversationId: String?, val kind: String = "chat")

/** Optional platform hook for task events. Android and other clients leave it unset. */
object TaskPushNotifications {
    var onEvent: ((TaskPushEvent) -> Unit)? = null

    fun notify(event: TaskPushEvent) { onEvent?.invoke(event) }
}
