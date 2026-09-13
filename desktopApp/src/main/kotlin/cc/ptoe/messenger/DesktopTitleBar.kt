/*
 * Copyright 2026 ECSDevs
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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

@Composable
fun WindowScope.DesktopTitleBar(
    title: String,
    onCloseRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val frame = window as Frame
    var isMaximized by remember {
        mutableStateOf((frame.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH)
    }

    DisposableEffect(frame) {
        val listener = object : java.awt.event.WindowAdapter() {
            override fun windowStateChanged(e: WindowEvent) {
                isMaximized = (e.newState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
            }
        }
        frame.addWindowStateListener(listener)
        onDispose { frame.removeWindowStateListener(listener) }
    }

    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val hoverColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val pressedColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(surfaceColor)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var pressScreen: Point? = null
                    var winStartLoc: Point? = null
                    var lastPressTime = 0L

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue

                        when (event.type) {
                            PointerEventType.Press -> {
                                pressScreen = MouseInfo.getPointerInfo()?.location
                                winStartLoc = frame.location
                                val now = System.currentTimeMillis()
                                if (now - lastPressTime < 300) {
                                    val maximized =
                                        (frame.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
                                    frame.extendedState =
                                        if (maximized) Frame.NORMAL else Frame.MAXIMIZED_BOTH
                                    lastPressTime = 0L
                                    pressScreen = null
                                    winStartLoc = null
                                } else {
                                    lastPressTime = now
                                }
                            }
                            PointerEventType.Move -> {
                                if (change.pressed) {
                                    val ps = pressScreen ?: continue
                                    val wl = winStartLoc ?: continue
                                    val current = MouseInfo.getPointerInfo()?.location ?: continue
                                    val dx = current.x - ps.x
                                    val dy = current.y - ps.y
                                    change.consume()
                                    if ((frame.extendedState and Frame.MAXIMIZED_BOTH) != Frame.MAXIMIZED_BOTH) {
                                        val newLoc = Point(wl.x + dx, wl.y + dy)
                                        SwingUtilities.invokeLater { frame.location = newLoc }
                                    }
                                }
                            }
                            PointerEventType.Release -> {
                                pressScreen = null
                                winStartLoc = null
                            }
                            else -> {}
                        }
                    }
                }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = remember {
                Thread.currentThread().contextClassLoader
                    .getResourceAsStream("logo.png")
                    ?.use { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
                    ?: ImageBitmap(1, 1)
            },
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            color = onSurfaceColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.weight(1f))
        TitleBarButton(
            onClick = { frame.extendedState = Frame.ICONIFIED },
            hoverColor = hoverColor,
            pressedColor = pressedColor,
            tint = onSurfaceColor
        ) { contentColor ->
            MinimizeGlyph(color = contentColor)
        }
        TitleBarButton(
            onClick = {
                frame.extendedState =
                    if (isMaximized) Frame.NORMAL else Frame.MAXIMIZED_BOTH
            },
            hoverColor = hoverColor,
            pressedColor = pressedColor,
            tint = onSurfaceColor
        ) { contentColor ->
            if (isMaximized) RestoreGlyph(color = contentColor) else MaximizeGlyph(color = contentColor)
        }
        TitleBarButton(
            onClick = onCloseRequest,
            isClose = true,
            hoverColor = hoverColor,
            pressedColor = pressedColor,
            tint = onSurfaceColor
        ) { contentColor ->
            CloseGlyph(color = contentColor)
        }
    }
}

@Composable
private fun TitleBarButton(
    onClick: () -> Unit,
    hoverColor: Color,
    pressedColor: Color,
    tint: Color,
    isClose: Boolean = false,
    content: @Composable (Color) -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }

    val closeHoverRed = Color(0xFFC42B1C)
    val closePressedDark = Color(0xFFB3271B)

    val backgroundColor = when {
        isClose && isPressed -> closePressedDark
        isClose && isHovered -> closeHoverRed
        isPressed -> pressedColor
        isHovered -> hoverColor
        else -> Color.Transparent
    }
    val contentColor = if (isClose && isHovered) Color.White else tint

    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 36.dp)
            .background(backgroundColor)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Enter -> isHovered = true
                            PointerEventType.Exit -> {
                                isHovered = false
                                isPressed = false
                            }
                            PointerEventType.Press -> {
                                isPressed = true
                                event.changes.forEach { it.consume() }
                            }
                            PointerEventType.Release -> {
                                if (isHovered && isPressed) {
                                    onClick()
                                }
                                isPressed = false
                                event.changes.forEach { it.consume() }
                            }
                            else -> {}
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content(contentColor)
    }
}

@Composable
private fun MinimizeGlyph(color: Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val y = size.height / 2f
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
            cap = StrokeCap.Square
        )
    }
}

@Composable
private fun MaximizeGlyph(color: Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val stroke = 1f
        val half = stroke / 2f
        drawRect(
            color = color,
            topLeft = Offset(half, half),
            size = Size(size.width - stroke, size.height - stroke),
            style = Stroke(width = stroke)
        )
    }
}

@Composable
private fun RestoreGlyph(color: Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val stroke = 1f
        val half = stroke / 2f
        val w = size.width
        val h = size.height
        // Back (top-right) rectangle.
        val backW = w * 0.7f
        val backH = h * 0.7f
        drawRect(
            color = color,
            topLeft = Offset(w - backW + half, half),
            size = Size(backW - stroke, backH - stroke),
            style = Stroke(width = stroke)
        )
        // Front (bottom-left) rectangle.
        drawRect(
            color = color,
            topLeft = Offset(half, h - backH + half),
            size = Size(backW - stroke, backH - stroke),
            style = Stroke(width = stroke)
        )
    }
}

@Composable
private fun CloseGlyph(color: Color) {
    Canvas(modifier = Modifier.size(10.dp)) {
        val stroke = 1f
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
            strokeWidth = stroke,
            cap = StrokeCap.Square
        )
        drawLine(
            color = color,
            start = Offset(size.width, 0f),
            end = Offset(0f, size.height),
            strokeWidth = stroke,
            cap = StrokeCap.Square
        )
    }
}
