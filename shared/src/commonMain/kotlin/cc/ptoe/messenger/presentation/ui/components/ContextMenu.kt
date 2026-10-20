/*
 * Copyright 2026 originalFactor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.presentation.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

class ContextMenuState {
    var isVisible by mutableStateOf(false)
        private set

    /**
     * Cursor position in **window-level (screen)** coordinates (pixels).
     * This value is set by [Modifier.onContextMenu] on each right-click.
     */
    var windowOffset by mutableStateOf(Offset.Zero)
        private set

    /**
     * Top-left corner, in window coordinates, of the composable that the
     * [Modifier.onContextMenu] modifier was applied to. Set each layout
     * pass via [onGloballyPositioned].
     */
    internal var elementWindowTopLeft by mutableStateOf(Offset.Zero)

    fun showAtCursor(localClickPosition: Offset) {
        windowOffset = elementWindowTopLeft + localClickPosition
        isVisible = true
    }

    fun hide() {
        isVisible = false
    }
}

@Composable
fun rememberContextMenuState(): ContextMenuState = remember { ContextMenuState() }

private fun Modifier.captureContextMenuWindowOffset(state: ContextMenuState): Modifier =
    this.onGloballyPositioned { coordinates ->
        state.elementWindowTopLeft = coordinates.positionInWindow()
    }

/**
 * Detects right-click (secondary mouse button press) on the modified element
 * and records the cursor position in window coordinates inside [state].
 *
 * Pair with [CursorDropdownMenu] to display a Material 3 context menu at the
 * exact cursor position.
 */
fun Modifier.onContextMenu(state: ContextMenuState): Modifier =
    this
        .captureContextMenuWindowOffset(state)
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.buttons.isSecondaryPressed) {
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.changedToDown()) {
                            // change.position is element-local; captureWindowOffset
                            // recorded the element's top-left window corner, so
                            // summing them yields the cursor's window coordinate.
                            state.showAtCursor(change.position)
                        }
                        change.consume()
                    }
                }
            }
        }

/**
 * A cursor-anchored Material 3 context menu.
 *
 * The standard [DropdownMenu] positions its inner Popup relative to the
 * call-site composable using a fixed `BottomStart` anchor and an additive
 * `offset`. That works well for icon buttons but produces huge positioning
 * errors when the anchor is a large row (e.g. a conversation item), because
 * the base "BottomStart" of the row is already far from the cursor pixel.
 *
 * This wrapper therefore:
 *  1. Wraps the [DropdownMenu] in an empty 0×0 [Box] at the call site.
 *  2. Records that Box's window position on each layout pass.
 *  3. Computes the DropdownMenu's `offset` as
 *     `state.windowOffset - menuBoxWindowTopLeft` — i.e. moves the menu's
 *     Popup *exactly* to the right-click cursor position regardless of
 *     where the 0×0 anchor Box ends up inside the layout tree.
 *  4. Adds a tiny (4, 2) dp nudge so the cursor tip does not cover the
 *     first [DropdownMenuItem]'s leading icon.
 */
@Composable
fun CursorDropdownMenu(
    state: ContextMenuState,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current

    // Top-left corner (in window coordinates) of the 0×0 Box that wraps the
    // DropdownMenu call. Because DropdownMenu uses the call-site composable
    // as its anchor, this value lets us translate `state.windowOffset` into
    // the anchor-local coordinate space expected by DropdownMenu's offset.
    var menuBoxWindowTopLeft by remember { mutableStateOf(Offset.Zero) }

    val dpOffset = with(density) {
        val anchorLocalX = state.windowOffset.x - menuBoxWindowTopLeft.x
        val anchorLocalY = state.windowOffset.y - menuBoxWindowTopLeft.y
        DpOffset(
            x = anchorLocalX.toDp() + 4.dp,
            y = anchorLocalY.toDp() + 2.dp
        )
    }

    Box(
        Modifier.onGloballyPositioned { coordinates ->
            menuBoxWindowTopLeft = coordinates.positionInWindow()
        }
    ) {
        DropdownMenu(
            expanded = state.isVisible,
            onDismissRequest = {
                state.hide()
                onDismiss()
            },
            offset = dpOffset,
            content = content
        )
    }
}
