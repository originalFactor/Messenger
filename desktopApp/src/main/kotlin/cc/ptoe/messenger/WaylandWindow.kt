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

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.presentation.theme.MessengerTheme
import cc.ptoe.messenger.presentation.theme.ThemeMode
import cc.ptoe.messenger.presentation.ui.components.MainScaffold
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Select the toolkit before AWT initialization; never silently emulate a requested native backend. */
internal fun configureDesktopBackend(): Boolean {
    val backend = System.getProperty("messenger.desktop.backend", "auto")
    require(backend in setOf("auto", "wayland", "x11")) {
        "messenger.desktop.backend must be auto, wayland or x11 (was $backend)"
    }
    if (!System.getProperty("os.name").startsWith("Linux")) {
        require(backend != "wayland") { "Native Wayland requires Linux" }
        return false
    }
    val hasWayland = !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
    val hasToolkit = runCatching {
        Class.forName("sun.awt.wl.WLToolkit", false, ClassLoader.getPlatformClassLoader())
    }.isSuccess
    val requestedToolkit = System.getProperty("awt.toolkit.name")
    val useWayland = when (backend) {
        "wayland" -> true
        "x11" -> false
        else -> requestedToolkit == "WLToolkit" ||
            (requestedToolkit != "XToolkit" && hasWayland && hasToolkit)
    }
    if (!useWayland) {
        // Newer JBRs can select Wayland automatically; ComposeWindow needs X11.
        System.setProperty("awt.toolkit.name", "XToolkit")
        return false
    }
    check(hasWayland && hasToolkit) {
        "Native Wayland requires WAYLAND_DISPLAY and a JetBrains Runtime with WLToolkit. " +
            "Set MESSENGER_DESKTOP_JAVA_HOME (or -Pmessenger.desktop.javaHome) to that JDK."
    }
    System.setProperty("awt.toolkit.name", "WLToolkit")
    System.setProperty("java.awt.headless", "false")
    // ComposeWindow/SkiaSurface uses X11 JAWT handles even with SOFTWARE.
    // Draw into Swing's buffer instead; all popups/dialogs stay in this panel.
    System.setProperty("skiko.renderApi", "SOFTWARE")
    // Component layers use SwingComposeSceneLayer and a separate native picture
    // recorder per popup. Crash logs place SIGSEGV in SkCanvas::restore on this
    // software popup path. Keep overlays in the panel's scene to avoid it.
    System.setProperty("compose.layers.type", "SAME_CANVAS")
    System.setProperty("compose.application.configure.swing.globals", "false")
    check(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit") {
        "The JVM did not activate WLToolkit; refusing to fall back to XWayland"
    }
    return true
}

/** JBR owns decorations, move/resize and presentation to the Wayland compositor. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun launchWaylandWindow() {
    SwingUtilities.invokeLater {
        val frame = JFrame("Messenger")
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent) {
                // Dispose Compose on the EDT before JBR's Wayland shutdown hook
                // removes native peers on its own thread. Core shutdown still
                // belongs to the existing JVM hook, never the EDT.
                frame.dispose()
                exitProcess(0)
            }
        })
        val screen = frame.graphicsConfiguration.bounds
        frame.minimumSize = Dimension(minOf(800, screen.width), minOf(600, screen.height))
        frame.size = Dimension(
            minOf(1280, (screen.width * .9).toInt()),
            minOf(800, (screen.height * .9).toInt()),
        )
        Thread.currentThread().contextClassLoader.getResourceAsStream("logo.png")?.use {
            frame.iconImage = ImageIO.read(it)
        }
        val panel = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
        frame.contentPane.add(panel)
        panel.setContent {
            val themeMode by AppContainerHolder.instance.themePreferences.themeMode
                .collectAsState(initial = ThemeMode.SYSTEM)
            MessengerTheme(themeMode = themeMode) { MainScaffold() }
        }
        frame.isVisible = true
        System.err.println("Messenger desktop: native Wayland (WLToolkit, SwingGraphics, SOFTWARE)")
    }
}
