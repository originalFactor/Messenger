package cc.ptoe.messenger.presentation.ui.acp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.platform.BackHandler
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun IntegrationFab(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, onManual: () -> Unit, onStore: () -> Unit) {
    BackHandler(enabled = expanded, onBack = { onExpandedChange(false) }) {}
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            ToggleFloatingActionButton(checked = expanded, onCheckedChange = onExpandedChange) {
                Icon(if (checkedProgress > .5f) Icons.Default.Close else Icons.Default.Add,
                    stringResource(if (expanded) Res.string.action_close else Res.string.action_add),
                    Modifier.animateIcon({ checkedProgress }))
            }
        },
        modifier = Modifier.navigationBarsPadding().offset(
            x = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 16.dp else (-16).dp, y = 16.dp)
    ) {
        FloatingActionButtonMenuItem(onClick = { onExpandedChange(false); onStore() },
            text = { Text(stringResource(Res.string.integrations_store)) }, icon = { Icon(Icons.Default.Storefront, null) })
        FloatingActionButtonMenuItem(onClick = { onExpandedChange(false); onManual() },
            text = { Text(stringResource(Res.string.integrations_manual)) }, icon = { Icon(Icons.Default.Add, null) })
    }
}

@Composable
internal fun IntegrationStoreDialog(kind: String, onDismiss: () -> Unit, onSelect: (JsonObject) -> Unit) {
    val cloud = AppContainerHolder.instance.cloud
    val user by cloud.user.collectAsState(initial = null)
    var rows by remember { mutableStateOf(emptyList<JsonObject>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(user?.id, kind) {
        loading = true
        rows = emptyList()
        error = ""
        try {
            if (user != null) rows = requestAcp(AppContainerHolder.instance.coreBridge, "store") { put("kind", kind) }.objects("items")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            error = failure.message.orEmpty()
            if (failure is AcpCommandException && failure.code == "session_expired") {
                runCatching { cloud.refreshUser() }
            }
        } finally { loading = false }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.integrations_store)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (user == null) Text(stringResource(Res.string.integrations_store_signin))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                if (user != null && !loading && error.isEmpty() && rows.isEmpty()) Text(stringResource(Res.string.integrations_empty))
                LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.text("id") }) { row ->
                        Card(onClick = { onSelect(row) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(row.text("name"), style = MaterialTheme.typography.titleMedium)
                                Text(row.text("description"), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_close)) } })
}
