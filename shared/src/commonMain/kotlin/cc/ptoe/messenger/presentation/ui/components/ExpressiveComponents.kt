@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** InstallerX-inspired connected rows, using theme corners rather than private radius constants. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun expressiveItemShapes(index: Int = 0, count: Int = 1): ListItemShapes {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    val shape = outer.copy(
        topStart = if (index == 0) outer.topStart else inner.topStart,
        topEnd = if (index == 0) outer.topEnd else inner.topEnd,
        bottomStart = if (index == count - 1) outer.bottomStart else inner.bottomStart,
        bottomEnd = if (index == count - 1) outer.bottomEnd else inner.bottomEnd,
    )
    return ListItemDefaults.shapes(shape = shape, pressedShape = outer, selectedShape = shape,
        hoveredShape = shape, focusedShape = shape)
}

/** Keep fast corner interpolation bounded; the other expressive springs remain unchanged. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveListMotion(content: @Composable () -> Unit) {
    val motion = MaterialTheme.motionScheme
    val bounded = remember(motion) { object : MotionScheme by motion {
        override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = tween(180)
    } }
    MaterialTheme(motionScheme = bounded, content = content)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ExpressiveRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    enabled: Boolean = true,
    index: Int = 0,
    count: Int = 1,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    ExpressiveListMotion {
        val rowContent: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = content)
        }
        val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer)
        val rowModifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
        if (selected == null) androidx.compose.material3.ListItem(
            onClick = onClick, onLongClick = onLongClick, enabled = enabled,
            modifier = rowModifier, shapes = expressiveItemShapes(index, count),
            colors = colors, interactionSource = interactionSource, content = rowContent,
        ) else androidx.compose.material3.ListItem(
            selected = selected, onClick = onClick, onLongClick = onLongClick, enabled = enabled,
            modifier = rowModifier, shapes = expressiveItemShapes(index, count),
            colors = colors, interactionSource = interactionSource, content = rowContent,
        )
    }
}

@Composable
internal fun ExpressiveChoiceRow(
    label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, index: Int = 0, count: Int = 1,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    ExpressiveRow(onClick, modifier.semantics { role = Role.RadioButton }, selected = selected, index = index, count = count) {
        leadingContent?.invoke()
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        RadioButton(selected, null)
    }
}

private val LocalPageScroll = staticCompositionLocalOf<TopAppBarScrollBehavior?> { null }

/** One surface hierarchy for management screens; callers retain ownership of insets and scroll. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpressiveScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    CompositionLocalProvider(LocalPageScroll provides scroll) {
        Scaffold(modifier.nestedScroll(scroll.nestedScrollConnection), topBar, bottomBar, snackbarHost, floatingActionButton,
            floatingActionButtonPosition, containerColor, contentColor, contentWindowInsets, content)
    }
}

@Composable
internal fun ExpressiveSwitchRow(
    title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    ExpressiveRow(onClick = { onCheckedChange(!checked) }, enabled = enabled,
        modifier = modifier.semantics(mergeDescendants = true) {
            role = Role.Switch
            toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
        }) {
        icon?.let { Icon(it, null, Modifier.padding(end = 16.dp)) }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, null, enabled = enabled, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** Management pages collapse naturally; conversation toolbars remain compact. */
@Composable
internal fun ExpressiveTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    ),
    scrollBehavior: TopAppBarScrollBehavior? = LocalPageScroll.current,
) {
    LargeFlexibleTopAppBar(title = title, modifier = modifier, navigationIcon = navigationIcon,
        actions = actions, windowInsets = windowInsets, colors = colors, scrollBehavior = scrollBehavior)
}
