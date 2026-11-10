package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Immutable
internal data class SettingsItemPosition(val index: Int = 0, val count: Int = 1)
internal val LocalSettingsItemPosition = staticCompositionLocalOf { SettingsItemPosition() }

/** Runtime collections stay lazy, keyed and type-reusable; only visible entries are composed. */
internal fun <T> LazyListScope.settingsItems(
    items: List<T>,
    key: (T) -> Any,
    contentType: Any,
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) {
    itemsIndexed(items, key = { _, item -> key(item) }, contentType = { _, _ -> contentType }) { index, item ->
        CompositionLocalProvider(LocalSettingsItemPosition provides SettingsItemPosition(index, items.size)) {
            itemContent(item)
        }
    }
}

internal class SettingsGroupScope {
    internal val entries = mutableListOf<@Composable () -> Unit>()
    fun item(content: @Composable () -> Unit) { entries += content }
}

/** For small groups known at composition time; data-backed collections use settingsItems. */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: SettingsGroupScope.() -> Unit) {
    val entries = SettingsGroupScope().apply(content).entries
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        entries.forEachIndexed { index, row ->
            CompositionLocalProvider(LocalSettingsItemPosition provides SettingsItemPosition(index, entries.size)) { row() }
        }
    }
}
