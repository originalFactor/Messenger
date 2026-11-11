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

package cc.ptoe.llmtypewriter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Renders the [state]'s revealed text inline, wired to a token source [Flow<String>] that streams
 * the text in.
 *
 * The reveal cadence is decided by [speedCurve] (linear/ease-out/natural — see [SpeedCurve]),
 * with a baseline [baseDelayMs]. Tap the rendered area to skip to the buffer's tail.
 *
 * For non-streaming (already-have-the-string) usage, see [TypewriterText].
 *
 * Test tags:
 *  - root: `llm_typewriter`
 *  - rendered text area: `llm_typewriter_text`
 */
@Composable
fun StreamingTypewriter(
    tokens: Flow<String>,
    modifier: Modifier = Modifier,
    state: StreamingTypewriterState = rememberStreamingTypewriterState(),
    renderer: TypewriterRenderer = PlainTypewriterRenderer,
    baseDelayMs: Long = LlmTypewriterDefaults.DefaultBaseDelayMs,
    speedCurve: SpeedCurve = SpeedCurve.Natural,
    tapToSkip: Boolean = true,
    announceForAccessibility: Boolean = true,
) {
    // Collect the source flow into the state's buffer. `collectLatest` cancels the prior collector
    // when `tokens` identity changes — host can swap to a fresh stream (e.g. "Regenerate") and the
    // reveal loop just keeps consuming the buffer, which itself is reset via `state.reset()`.
    LaunchedEffect(tokens, state) {
        try {
            tokens.collect { token -> state.appendToken(token) }
        } finally {
            state.completeSource()
        }
    }

    // Reveal loop — runs as long as the state is in an active phase. Suspends while Stopped /
    // Waiting / Done / Idle.
    LaunchedEffect(state, baseDelayMs, speedCurve) {
        snapshotFlow { state.phase }
            .distinctUntilChanged()
            .filter { it == TypewriterPhase.Revealing }
            .collectLatest {
                while (state.phase == TypewriterPhase.Revealing) {
                    val prev = state.lastRevealed
                    val ch = state.revealNext() ?: break
                    val delayMs = speedCurve.delayForNext(baseDelayMs, prev, ch)
                    if (delayMs > 0) delay(delayMs)
                }
            }
    }

    val skipModifier = if (tapToSkip) Modifier.clickable { state.skipToEnd() } else Modifier

    Row(
        modifier = modifier
            .testTag("llm_typewriter")
            .then(skipModifier)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val text = state.revealed
        val accessibilityModifier = if (announceForAccessibility) {
            // liveRegion alone is enough — screen readers announce the underlying Text node
            // value. Adding `contentDescription` here would shadow the visible text and break
            // text-equality assertions in tests.
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        } else Modifier
        renderer.Render(
            text = text,
            modifier = Modifier.testTag("llm_typewriter_text").then(accessibilityModifier),
        )
        if (state.phase == TypewriterPhase.Stopped) {
            Text(
                text = " (stopped)",
                modifier = Modifier.testTag("llm_typewriter_stopped"),
                color = LocalContentColor.current.copy(alpha = 0.5f),
            )
        }
    }
}

/**
 * Renders a static [text] string at typewriter cadence. Convenience for the non-streaming case
 * (hero banners, demos) — internally, this just builds a flow that emits each character once and
 * delegates to [StreamingTypewriter].
 */
@Composable
fun TypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    state: StreamingTypewriterState = rememberStreamingTypewriterState(),
    renderer: TypewriterRenderer = PlainTypewriterRenderer,
    baseDelayMs: Long = LlmTypewriterDefaults.DefaultBaseDelayMs,
    speedCurve: SpeedCurve = SpeedCurve.Natural,
    tapToSkip: Boolean = true,
) {
    val tokens = remember(text) { staticFlowOf(text) }
    StreamingTypewriter(
        tokens = tokens,
        modifier = modifier,
        state = state,
        renderer = renderer,
        baseDelayMs = baseDelayMs,
        speedCurve = speedCurve,
        tapToSkip = tapToSkip,
    )
}

/**
 * A simple cycling banner — takes an immutable list of [phrases], reveals each one at the
 * typewriter cadence, holds for [holdMs], then deletes back to empty and types the next phrase.
 *
 * No flow plumbing required — internally builds a flow that produces characters and deletion
 * frames. The deletion is implemented by appending the carriage-return character `\b` (not a real
 * character — see [BackspaceMarker]). For simple plain rendering only — markdown rendering of
 * the cycled phrases is not supported.
 */
@Composable
fun CyclingTypewriterText(
    phrases: List<String>,
    modifier: Modifier = Modifier,
    holdMs: Long = 1500L,
    typeDelayMs: Long = LlmTypewriterDefaults.DefaultBaseDelayMs,
    deleteDelayMs: Long = (LlmTypewriterDefaults.DefaultBaseDelayMs / 2).coerceAtLeast(1L),
) {
    val state = rememberStreamingTypewriterState()
    LaunchedEffect(phrases, holdMs, typeDelayMs, deleteDelayMs) {
        var i = 0
        while (true) {
            val phrase = phrases.getOrNull(i % phrases.size) ?: return@LaunchedEffect
            state.reset()
            for (ch in phrase) {
                state.appendToken(ch.toString())
                // Let the reveal loop catch up between appends.
                delay(typeDelayMs)
            }
            delay(holdMs)
            // Delete by collapsing the revealed text one char at a time.
            repeat(phrase.length) {
                state.deleteLastRevealed()
                delay(deleteDelayMs)
            }
            i++
        }
    }
    // Just paint the state directly — no source flow needed.
    Row(
        modifier = modifier.testTag("llm_typewriter_cycling"),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = state.revealed, modifier = Modifier.testTag("llm_typewriter_text"))
    }
}
