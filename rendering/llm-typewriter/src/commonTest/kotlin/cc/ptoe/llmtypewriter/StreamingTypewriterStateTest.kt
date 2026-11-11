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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-logic tests for [StreamingTypewriterState] — no composition required. */
class StreamingTypewriterStateTest {

    @Test
    fun freshState_isIdle() {
        val state = StreamingTypewriterState()
        assertEquals(TypewriterPhase.Idle, state.phase)
        assertEquals("", state.revealed)
        assertEquals(0, state.pendingChars)
        assertEquals(false, state.sourceComplete)
    }

    @Test
    fun appendToken_flipsToRevealingAndExposesPendingChars() {
        val state = StreamingTypewriterState()
        state.appendToken("hello")
        assertEquals(TypewriterPhase.Revealing, state.phase)
        assertEquals(5, state.pendingChars)
        assertEquals("", state.revealed)
    }

    @Test
    fun appendEmptyToken_isNoOp() {
        val state = StreamingTypewriterState()
        state.appendToken("")
        assertEquals(TypewriterPhase.Idle, state.phase)
        assertEquals(0, state.pendingChars)
    }

    @Test
    fun revealNext_revealsOneCharAtATime() {
        val state = StreamingTypewriterState()
        state.appendToken("ab")
        assertEquals('a', state.revealNext())
        assertEquals("a", state.revealed)
        assertEquals('b', state.revealNext())
        assertEquals("ab", state.revealed)
    }

    @Test
    fun revealNext_returnsNullWhenCaughtUp_andWaits() {
        val state = StreamingTypewriterState()
        state.appendToken("a")
        state.revealNext()
        assertEquals(null, state.revealNext())
        assertEquals(TypewriterPhase.Waiting, state.phase, "source not completed yet → Waiting, not Done")
    }

    @Test
    fun completeSource_flipsToDoneOnceRevealCaughtUp() {
        val state = StreamingTypewriterState()
        state.appendToken("a")
        state.revealNext()
        state.completeSource()
        assertEquals(true, state.sourceComplete)
        assertEquals(TypewriterPhase.Done, state.phase)
        assertTrue(state.isFinished)
    }

    @Test
    fun completeSource_whileBufferStillHasMore_keepsRevealing() {
        val state = StreamingTypewriterState()
        state.appendToken("ab")
        state.completeSource()
        assertEquals(TypewriterPhase.Revealing, state.phase)
        // After draining the buffer, it should be Done.
        state.revealNext()
        state.revealNext()
        assertEquals(TypewriterPhase.Done, state.phase)
    }

    @Test
    fun skipToEnd_flushesAllBuffered() {
        val state = StreamingTypewriterState()
        state.appendToken("hello world")
        state.skipToEnd()
        assertEquals("hello world", state.revealed)
        assertEquals(0, state.pendingChars)
        assertEquals(TypewriterPhase.Waiting, state.phase, "source open → Waiting")
    }

    @Test
    fun skipToEnd_thenComplete_flipsToDone() {
        val state = StreamingTypewriterState()
        state.appendToken("hi")
        state.skipToEnd()
        state.completeSource()
        assertEquals(TypewriterPhase.Done, state.phase)
    }

    @Test
    fun stop_freezesPhase_andResumeContinues() {
        val state = StreamingTypewriterState()
        state.appendToken("abc")
        state.revealNext()
        state.stop()
        assertEquals(TypewriterPhase.Stopped, state.phase)
        // pending characters stay pending while stopped
        assertEquals(2, state.pendingChars)
        // resume goes back to Revealing because there's still buffer left
        state.resume()
        assertEquals(TypewriterPhase.Revealing, state.phase)
    }

    @Test
    fun resume_whenNotStopped_isNoOp() {
        val state = StreamingTypewriterState()
        state.appendToken("a")
        // currently Revealing — resume shouldn't change anything.
        state.resume()
        assertEquals(TypewriterPhase.Revealing, state.phase)
    }

    @Test
    fun reset_clearsEverything() {
        val state = StreamingTypewriterState()
        state.appendToken("hello")
        state.revealNext()
        state.completeSource()
        state.reset()
        assertEquals("", state.revealed)
        assertEquals(0, state.pendingChars)
        assertEquals(false, state.sourceComplete)
        assertEquals(TypewriterPhase.Idle, state.phase)
    }

    @Test
    fun appendAfterDone_resumesRevealing() {
        val state = StreamingTypewriterState()
        state.appendToken("a")
        state.revealNext()
        state.completeSource()
        assertEquals(TypewriterPhase.Done, state.phase)
        // Adding more text after completion should restart the reveal loop. (The state machine
        // doesn't enforce "no append after complete" — callers can stream additional content.)
        state.appendToken("b")
        // sourceComplete stays true, but phase moves back to Revealing because the buffer grew.
        assertEquals(TypewriterPhase.Done, state.phase, "Done sticks until the new char is revealed")
        // After revealing, it goes to Done again because completion was set.
        state.revealNext()
        assertEquals(TypewriterPhase.Done, state.phase)
    }

    @Test
    fun isStreaming_isTrueWhileRevealingOrWaiting() {
        val state = StreamingTypewriterState()
        assertEquals(false, state.isStreaming)
        state.appendToken("a")
        assertEquals(true, state.isStreaming)
        state.revealNext()
        // After draining, still streaming until source completes.
        assertEquals(true, state.isStreaming)
        state.completeSource()
        assertEquals(false, state.isStreaming)
    }

    @Test
    fun revealedTokens_wordStreamedMarkdownImageInsideListItem_eventuallyBecomesImageToken() {
        val state = StreamingTypewriterState()
        val markdown = "- Images are handed to the configurable image renderer: ![Earth from Artemis II](https://upload.wikimedia.org/wikipedia/commons/6/63/Earth_From_the_Perspective_of_Artemis_II.jpg)"

        val pieces = mutableListOf<String>()
        val builder = StringBuilder()
        for (ch in markdown) {
            builder.append(ch)
            if (ch == ' ' || ch == '\n') {
                pieces += builder.toString()
                builder.clear()
            }
        }
        if (builder.isNotEmpty()) pieces += builder.toString()

        for (piece in pieces) {
            state.appendToken(piece)
            state.skipToEnd()
        }

        val item = state.revealedTokens().single() as MdToken.ListItem
        assertTrue(item.inline.any { it is MdToken.Image })
    }
}
