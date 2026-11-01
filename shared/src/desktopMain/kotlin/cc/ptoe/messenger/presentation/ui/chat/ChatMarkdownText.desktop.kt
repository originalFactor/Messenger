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
package cc.ptoe.messenger.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cc.ptoe.llmtypewriter.PlainTypewriterRenderer
import cc.ptoe.llmtypewriter.SpeedCurve
import cc.ptoe.llmtypewriter.StreamingTypewriter
import cc.ptoe.llmtypewriter.StreamingTypewriterState
import cc.ptoe.llmtypewriter.rememberMarkdownTypewriterRenderer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

@Composable
internal actual fun ChatMarkdownText(
    markdown: String,
    isStreaming: Boolean,
    modifier: Modifier,
) {
    if (!isStreaming) {
        // The markdown renderer paints the state's token buffer and ignores the
        // `text` argument its TypewriterRenderer receives, so a settled message
        // must arrive with a buffer that already holds it. Handing the renderer
        // a fresh, never-seeded state (the old behaviour) painted nothing, which
        // is why every finished reply showed as an empty pill.
        val staticState = remember(markdown) {
            StreamingTypewriterState().apply {
                appendToken(markdown)
                completeSource()
                skipToEnd()
            }
        }
        rememberMarkdownTypewriterRenderer(staticState).Render(markdown, modifier)
        return
    }

    val state = remember { StreamingTypewriterState() }
    val renderer = rememberMarkdownTypewriterRenderer(state)

    val tokens = remember { Channel<String>(Channel.UNLIMITED) }
    val flow = remember(tokens) { tokens.receiveAsFlow() }
    var emitted by remember { mutableStateOf("") }
    LaunchedEffect(markdown) {
        if (!markdown.startsWith(emitted)) {
            state.reset()
            emitted = ""
        }
        if (markdown.length > emitted.length) {
            val delta = markdown.substring(emitted.length)
            tokens.send(delta)
            emitted = markdown
        }
    }
    StreamingTypewriter(
        tokens = flow,
        state = state,
        renderer = renderer,
        modifier = modifier,
        baseDelayMs = 4L,
        speedCurve = SpeedCurve.Linear,
        tapToSkip = true,
        announceForAccessibility = false,
    )
}
