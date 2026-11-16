package cc.ptoe.messenger.presentation.navigation

import cc.ptoe.messenger.data.util.randomUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConversationOpenRequest(
    val token: String,
    val conversationId: String,
    val deviceId: String?,
    val error: String? = null,
    val attempt: Long = 0,
)

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

    fun retry(request: ConversationOpenRequest) {
        if (request.error != null) pending.compareAndSet(request, request.copy(error = null, attempt = request.attempt + 1))
    }

    internal fun fail(request: ConversationOpenRequest, failure: Exception) {
        val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure.toString()
        pending.compareAndSet(request, request.copy(error = detail))
    }
}

/** Keep a request available for the next UI owner if navigation is cancelled. */
internal suspend fun handleConversationOpenRequest(
    request: ConversationOpenRequest,
    open: suspend () -> Unit,
) {
    try {
        open()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ConversationOpenRequests.fail(request, failure)
        return
    }
    ConversationOpenRequests.consume(request)
}

/** Refresh once when signed in; a failed refresh must not hide an existing local chat. */
internal suspend fun prepareLocalConversation(
    signedIn: Boolean,
    sync: suspend () -> Unit,
    exists: suspend () -> Boolean,
    onSyncFailure: (Throwable) -> Unit,
): Boolean {
    val syncFailure = if (signedIn) {
        try { sync(); null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { failure }
    } else null
    val found = exists()
    if (syncFailure != null) {
        // A failed refresh cannot establish that an absent local conversation is unavailable.
        if (!found) throw syncFailure
        onSyncFailure(syncFailure)
    }
    return found
}
