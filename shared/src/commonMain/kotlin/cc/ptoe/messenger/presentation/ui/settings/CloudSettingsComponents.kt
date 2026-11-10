package cc.ptoe.messenger.presentation.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.presentation.ui.components.ExpressiveRow

/** Cloud data uses the same section hierarchy and tonal surfaces as native settings. */
@Composable
internal fun CloudSettingsSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    groupedActions: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (groupedActions) Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        else Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceBright,
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
internal fun CloudActionRow(
    title: String, icon: ImageVector, onClick: () -> Unit,
    index: Int, count: Int, enabled: Boolean = true,
    description: String? = null, destructive: Boolean = false,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ExpressiveRow(onClick = onClick, enabled = enabled, index = index, count = count) {
        Icon(icon, null, Modifier.padding(end = 16.dp).size(24.dp), tint = color)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = color)
            description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = color)
    }
}

/** Stack quota metadata on phones so large balances cannot collide with the expiry date. */
@Composable
internal fun CloudQuotaSummary(balance: String, balanceLabel: String, expiryLabel: String, expiry: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val quota: @Composable () -> Unit = {
            Column {
                Text(balance, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Text(balanceLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val expiration: @Composable () -> Unit = {
            Column {
                Text(expiryLabel, style = MaterialTheme.typography.labelLarge)
                Text(expiry, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (maxWidth < 360.dp) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { quota(); expiration() }
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(end = 24.dp)) { quota() }
            expiration()
        }
    }
}
