package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.presentation.ui.components.resolveAcpAvatar
import kotlinx.coroutines.CancellationException

/** Resolve local ACP profiles for conversation rows, which only carry an agent ID. */
@Composable
internal fun rememberAcpConversationAvatars(agentIds: Set<String>): Map<String, String?> {
    val core = AppContainerHolder.instance.coreBridge
    val revision by acpProfilesRevision.collectAsState()
    var avatars by remember { mutableStateOf(emptyMap<String, String?>()) }
    LaunchedEffect(core, agentIds, revision) {
        val acpIds = agentIds.filter { it.startsWith("acp:") }.toSet()
        avatars = acpIds.associateWith { resolveAcpAvatar(it.removePrefix("acp:"), null) }
        if (acpIds.isNotEmpty()) {
            try {
                val profiles = core.controlSettings().objects("agents").associateBy { "acp:${it.text("id")}" }
                avatars = acpIds.associateWith { id ->
                    resolveAcpAvatar(id.removePrefix("acp:"), profiles[id]?.text("avatar"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The conversation remains usable with its default avatar if settings are unavailable.
            }
        }
    }
    return avatars
}
