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

package cc.ptoe.messenger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import java.awt.Frame
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

/** Monitor bounds minus the taskbar / app-bar insets: the area a maximized window may fill. */
internal fun workAreaBounds(screen: Rectangle, insets: Insets): Rectangle = Rectangle(
    screen.x + insets.left,
    screen.y + insets.top,
    screen.width - insets.left - insets.right,
    screen.height - insets.top - insets.bottom
)

/**
 * Undecorated frames have no native frame for the window manager to fit into
 * the desktop work area: Windows maximizes them to the full monitor rectangle,
 * so the title-bar maximize button (and Win+Up / double-click) covers the
 * taskbar. AWT exposes [Frame.maximizedBounds] for exactly this case — fill it
 * with the monitor's work area.
 *
 * The value is a hint the platform applies when it maximizes, so it must be
 * set BEFORE the frame enters the maximized state: setting it while already
 * maximized does not resize the window.
 */
internal fun Frame.applyWorkAreaMaximizedBounds() {
    val configuration = graphicsConfiguration ?: return
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    maximizedBounds = workAreaBounds(configuration.bounds, insets)
}

/**
 * Initial window state clamped to the primary monitor's work area.
 *
 * A fixed 1280x800 default overflows any monitor whose work area is smaller:
 * at 175% scale that default is 2240x1400 physical pixels on a 2160x1440
 * screen, so the window opens wider and taller than the screen itself - the
 * window buttons land off-screen and the content overflows past the edge.
 *
 * AWT reports screen bounds in the same user-space units Compose calls dp
 * (both are physical pixels divided by the UI scale), so the work area can be
 * used directly as a Dp bound. When the default does not fit it shrinks to 90%
 * of the work area and is centred there, leaving a margin on every side; a
 * window that merely matched the work area would look maximized at launch.
 */
@Composable
internal fun initialWindowState(
    defaultWidth: Dp = 1280.dp,
    defaultHeight: Dp = 800.dp
): WindowState {
    val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice.defaultConfiguration
    val work = workAreaBounds(
        configuration.bounds,
        Toolkit.getDefaultToolkit().getScreenInsets(configuration)
    )
    val fits = defaultWidth.value <= work.width && defaultHeight.value <= work.height
    val width = if (fits) defaultWidth.value else work.width * 0.9f
    val height = if (fits) defaultHeight.value else work.height * 0.9f
    return rememberWindowState(
        size = DpSize(width.dp, height.dp),
        position = WindowPosition(
            x = (work.x + (work.width - width) / 2).dp,
            y = (work.y + (work.height - height) / 2).dp
        )
    )
}

/**
 * Keeps [Frame.maximizedBounds] pointed at the monitor the window is on, so a
 * later maximize (title-bar button or OS shortcut) stops at that monitor's
 * taskbar instead of covering it. Work areas differ per monitor, so the bounds
 * are re-applied whenever the window moves to another screen (or the screen's
 * configuration changes, e.g. a DPI or taskbar change).
 */
@Composable
fun WindowScope.WorkAreaMaximizedBounds() {
    val frame = window as Frame
    DisposableEffect(frame) {
        frame.applyWorkAreaMaximizedBounds()
        var lastDevice: GraphicsDevice? = frame.graphicsConfiguration?.device
        val listener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) {
                val device = frame.graphicsConfiguration?.device ?: return
                if (device != lastDevice) {
                    lastDevice = device
                    frame.applyWorkAreaMaximizedBounds()
                }
            }

            override fun componentResized(e: ComponentEvent) {
                // A maximize/restore and a DPI change both resize the frame; the
                // former is harmless to recompute, the latter needs it.
                frame.applyWorkAreaMaximizedBounds()
            }
        }
        frame.addComponentListener(listener)
        onDispose { frame.removeComponentListener(listener) }
    }
}
