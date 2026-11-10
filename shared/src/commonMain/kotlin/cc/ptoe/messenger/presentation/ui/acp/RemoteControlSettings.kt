package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.generated.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RemoteControlSettings(onError: (String) -> Unit) {
    val core = AppContainerHolder.instance.coreBridge
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(core) {
        try {
            val settings = core.controlSettings()
            enabled = settings["enabled"]?.jsonPrimitive?.booleanOrNull == true
            name = settings.text("name")
            ready = true
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) { onError(failure.message.orEmpty()) }
    }
    fun save(nextEnabled: Boolean) {
        busy = true
        scope.launch {
            try {
                val current = core.controlSettings()
                core.saveControlSettings(JsonObject(current + mapOf("enabled" to JsonPrimitive(nextEnabled), "name" to JsonPrimitive(name.trim()))))
                enabled = core.controlSettings()["enabled"]?.jsonPrimitive?.booleanOrNull == true
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { onError(failure.message.orEmpty())
            } finally { busy = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.acp_enable_control), Modifier.weight(1f))
            Switch(enabled, { save(it) }, enabled = ready && !busy)
        }
        Text(stringResource(Res.string.acp_control_description), style = MaterialTheme.typography.bodyMedium)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(name, { name = it }, enabled = ready && !busy, label = { Text(stringResource(Res.string.acp_device_name)) }, modifier = Modifier.weight(1f), singleLine = true)
            TextButton(enabled = ready && !busy, onClick = { save(enabled) }) { Text(stringResource(Res.string.action_save)) }
        }
    }
}
