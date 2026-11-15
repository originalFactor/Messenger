package cc.ptoe.messenger.presentation.navigation

import cc.ptoe.messenger.data.util.randomUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConversationOpenRequest(val token: String, val conversationId: String, val deviceId: String?)

/** Routes a notification tap after the Compose navigation graph has been installed. */
object ConversationOpenRequests {
    private val pending = MutableStateFlow<ConversationOpenRequest?>(null)
    val requests = pending.asStateFlow()

    fun open(conversationId: String, deviceId: String? = null) {
        pending.value = ConversationOpenRequest(randomUuid(), conversationId, deviceId)
    }

    fun consume(request: ConversationOpenRequest) {
        pending.compareAndSet(request, null)
    }
}

/** Refresh once when signed in; a failed refresh must not hide an existing local chat. */
internal suspend fun prepareLocalConversation(
    signedIn: Boolean,
    sync: suspend () -> Unit,
    exists: suspend () -> Boolean,
    onSyncFailure: (Throwable) -> Unit,
): Boolean {
    if (signedIn) {
        try { sync() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { onSyncFailure(failure) }
    }
    return exists()
}
