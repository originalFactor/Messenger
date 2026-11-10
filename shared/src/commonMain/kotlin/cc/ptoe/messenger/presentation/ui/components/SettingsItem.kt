@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.presentation.ui.components.internal.rememberAnimatedShape

/** Single-pass settings layout. Animation values are read by the outline, not the content. */
@Composable
internal fun SettingsItem(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean? = null,
    index: Int = LocalSettingsItemPosition.current.index,
    count: Int = LocalSettingsItemPosition.current.count,
    containerColor: Color = MaterialTheme.colorScheme.surfaceBright,
    selectedContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    interactionSource: MutableInteractionSource? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    headlineContent: @Composable () -> Unit,
) {
    val clickable = onClick != null || onLongClick != null
    val source = interactionSource ?: remember { MutableInteractionSource() }
    var pressed by remember(source) { mutableStateOf(false) }
    // One collector handles overlapping presses and their cancellation; no per-frame content state.
    if (clickable) LaunchedEffect(source) {
        val active = mutableSetOf<PressInteraction.Press>()
        source.interactions.collect { event ->
            when (event) {
                is PressInteraction.Press -> active.add(event)
                is PressInteraction.Release -> active.remove(event.press)
                is PressInteraction.Cancel -> active.remove(event.press)
            }
            pressed = active.isNotEmpty()
        }
    }
    val shapes = expressiveItemShapes(index, count)
    val target = (if (pressed && enabled) shapes.pressedShape else shapes.shape) as CornerBasedShape
    val animated = if (clickable) rememberAnimatedShape(target, MaterialTheme.motionScheme.fastSpatialSpec()) else target
    val background = if (selected == true) selectedContainerColor else containerColor
    val foreground = if (selected == true) contentColorFor(selectedContainerColor) else MaterialTheme.colorScheme.onSurface
    val minimum = ((if (supportingContent == null) 56.dp else 72.dp) * LocalDensity.current.fontScale).coerceAtLeast(48.dp)
    val rowModifier = modifier.fillMaxWidth().heightIn(min = minimum)
        .clip(animated).background(background)
        .then(if (clickable) Modifier.combinedClickable(enabled = enabled, interactionSource = source,
            onClick = { onClick?.invoke() }, onLongClick = onLongClick) else Modifier)
        .semantics(mergeDescendants = true) {
            if (!enabled) disabled()
            if (selected != null) this.selected = selected
        }
    CompositionLocalProvider(LocalContentColor provides foreground) {
        Layout(
            modifier = rowModifier.padding(horizontal = 16.dp, vertical = 12.dp),
            content = {
                if (leadingContent != null) Box { leadingContent() }
                Column {
                    ProvideTextStyle(MaterialTheme.typography.titleMedium) { headlineContent() }
                    if (supportingContent != null) CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        supportingContent()
                    }
                }
                if (trailingContent != null) Box { trailingContent() }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val leading = if (leadingContent != null) measurables.first().measure(loose) else null
            val trailing = if (trailingContent != null) measurables.last().measure(loose) else null
            val gap = 16.dp.roundToPx()
            val occupied = (leading?.width ?: 0) + (trailing?.width ?: 0) +
                (if (leading != null) gap else 0) + (if (trailing != null) gap else 0)
            val textWidth = (constraints.maxWidth - occupied).coerceAtLeast(0)
            val text = measurables[if (leading != null) 1 else 0].measure(loose.copy(minWidth = textWidth, maxWidth = textWidth))
            val height = maxOf(text.height, leading?.height ?: 0, trailing?.height ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
            layout(constraints.maxWidth, height) {
                leading?.placeRelative(0, (height - leading.height) / 2)
                text.placeRelative(if (leading != null) leading.width + gap else 0, (height - text.height) / 2)
                trailing?.placeRelative(constraints.maxWidth - trailing.width, (height - trailing.height) / 2)
            }
        }
    }
}
