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
import androidx.compose.ui.window.WindowScope
import java.awt.Frame
import java.awt.GraphicsDevice
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
