package cc.ptoe.messenger.presentation.platform

/** Optional platform hook for task events. Android and other clients leave it unset. */
object TaskPushNotifications {
    var onEvent: ((String) -> Unit)? = null

    fun notify(event: String) { onEvent?.invoke(event) }
}
