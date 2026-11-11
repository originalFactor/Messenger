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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Lifecycle phases of a [StreamingTypewriterState] from the reveal loop's perspective. */
enum class TypewriterPhase {
    /** Nothing scheduled, nothing revealing. Initial state and the state after [StreamingTypewriterState.reset]. */
    Idle,

    /** The reveal loop is actively pulling characters off the buffer and adding them to [StreamingTypewriterState.revealed]. */
    Revealing,

    /** Source flow has not completed, but the reveal loop has caught up to the buffer's tail. */
    Waiting,

    /** Source flow completed and every buffered character has been revealed. */
    Done,

    /** The user (or a stop control) interrupted the reveal — buffered characters stay buffered and stay unrevealed. */
    Stopped,
}

/**
 * Owns the reveal buffer + revealed text + lifecycle phase for [StreamingTypewriter]. Constructed
 * via [rememberStreamingTypewriterState] (the standard path) or directly for tests.
 *
 * The state is the single source of truth — the composable doesn't keep a parallel cache. The
 * `tokens` flow only pushes characters into [appendToken]; the reveal loop pulls them out at the
 * pace dictated by the active [SpeedCurve]. This separation is what lets the typewriter survive
 * recompositions and supports the headless API (no composition required for tests).
 */
class StreamingTypewriterState(
    initialBufferSoftCap: Int = LlmTypewriterDefaults.DefaultBufferSoftCap,
) {

    private val buffer = StringBuilder()

    /**
     * 缓存 [revealed] 的最后一次 substring。buffer 为 append-only，revealed 只随
     * revealedLengthState 变化，因此以长度为 key 的惰性缓存完备：appendToken 不改变
     * 已揭示长度时缓存仍有效；reset / deleteLastRevealed / skipToEnd / revealNext 改变
     * 长度后自然失效重算。避免渲染器每帧对全文做 O(N) 截取。
     */
    private var revealedCacheLen: Int = -1
    private var revealedCache: String? = null

    /**
     * Number of characters revealed so far. Tracked as an Int (not a String) so each
     * [revealNext] only writes a small snapshot value — the snapshot read+compare in the
     * recomposition machinery is O(1) instead of O(N) per character. [revealed] is computed
     * lazily from [buffer] + this length when the renderer reads it.
     */
    private var revealedLengthState by mutableStateOf(0)

    /** Text that has been revealed so far. Renderers consume this. */
    val revealed: String
        get() {
            val len = revealedLengthState
            if (revealedCacheLen != len) {
                revealedCacheLen = len
                revealedCache = if (len == 0) "" else buffer.substring(0, len)
            }
            return revealedCache ?: ""
        }

    private var phaseState by mutableStateOf(TypewriterPhase.Idle)

    /** Lifecycle phase of the reveal loop. */
    val phase: TypewriterPhase get() = phaseState

    private var sourceCompleteState by mutableStateOf(false)

    /** `true` once the source token flow has signalled completion. */
    val sourceComplete: Boolean get() = sourceCompleteState

    private var bufferSizeState by mutableStateOf(0)
    private var pendingCharsState by mutableStateOf(0)

    /** Number of characters that have arrived but have not yet been revealed. */
    val pendingChars: Int get() = pendingCharsState

    /**
     * `true` when the source has completed and the reveal loop has caught up — i.e. the user is
     * looking at the final text and the typewriter is finished.
     */
    @Suppress("unused")
    val isFinished: Boolean get() = phaseState == TypewriterPhase.Done

    /** Soft cap on the buffer — purely a memory-pressure guard. */
    var bufferSoftCap: Int = initialBufferSoftCap

    // ---- caller-driven inputs ----------------------------------------------------------------

    /**
     * Append a token (a string of zero or more characters) to the reveal buffer. Empty strings
     * are no-ops. Called by the source-collecting coroutine each time the upstream `Flow<String>`
     * emits.
     */
    fun appendToken(token: String) {
        if (token.isEmpty()) return
        buffer.append(token)
        bufferSizeState = buffer.length
        pendingCharsState = bufferSizeState - revealedLengthState
        if (phaseState == TypewriterPhase.Idle || phaseState == TypewriterPhase.Waiting) {
            phaseState = TypewriterPhase.Revealing
        }
    }

    /**
     * Signal that the source flow has completed — any remaining buffered text will still be
     * revealed at the configured cadence, then the phase moves to [TypewriterPhase.Done].
     */
    fun completeSource() {
        sourceCompleteState = true
        // If the reveal loop already caught up, flip immediately to Done.
        if (revealedLengthState >= buffer.length) phaseState = TypewriterPhase.Done
    }

    // ---- reveal-loop inputs ------------------------------------------------------------------

    /**
     * Reveal one more character from the buffer. Returns the character revealed, or `null` if the
     * buffer is empty (in which case the phase becomes [TypewriterPhase.Waiting] or
     * [TypewriterPhase.Done] depending on whether the source has completed).
     */
    internal fun revealNext(): Char? {
        val len = revealedLengthState
        if (len >= buffer.length) {
            phaseState = if (sourceCompleteState) TypewriterPhase.Done else TypewriterPhase.Waiting
            pendingCharsState = 0
            return null
        }
        val ch = buffer[len]
        // Only bump an Int — no String allocation. The renderer reads `revealed` lazily on its
        // own snapshot frame and pays the substring cost once per recomposition.
        revealedLengthState = len + 1
        pendingCharsState = buffer.length - revealedLengthState
        if (revealedLengthState >= buffer.length) {
            phaseState = if (sourceCompleteState) TypewriterPhase.Done else TypewriterPhase.Waiting
        } else {
            phaseState = TypewriterPhase.Revealing
        }
        return ch
    }

    /** The character that was most recently revealed (used by [SpeedCurve]). */
    internal val lastRevealed: Char?
        get() {
            val len = revealedLengthState
            return if (len == 0) null else buffer[len - 1]
        }

    // ---- control surface ---------------------------------------------------------------------

    /**
     * Reveal everything currently in the buffer without animation. Used by the tap-to-skip
     * gesture and by `controller.skipToEnd()`. If the source has completed, the phase becomes
     * [TypewriterPhase.Done]; otherwise [TypewriterPhase.Waiting].
     */
    fun skipToEnd() {
        if (revealedLengthState < buffer.length) {
            revealedLengthState = buffer.length
            pendingCharsState = 0
        }
        phaseState = if (sourceCompleteState) TypewriterPhase.Done else TypewriterPhase.Waiting
    }

    /**
     * Interrupt the reveal loop. Already-revealed text stays visible; pending buffered chars stay
     * pending. The reveal loop honors [TypewriterPhase.Stopped] by suspending, and consumers can
     * observe the "stopped" state to render a ghost indicator.
     */
    fun stop() {
        phaseState = TypewriterPhase.Stopped
    }

    /** Resume after a [stop] — picks up exactly where we left off. */
    fun resume() {
        if (phaseState != TypewriterPhase.Stopped) return
        phaseState = when {
            revealedLengthState < buffer.length -> TypewriterPhase.Revealing
            sourceCompleteState -> TypewriterPhase.Done
            else -> TypewriterPhase.Waiting
        }
    }

    /**
     * Erase everything — revealed text, buffer, and completion state. Useful when the host wants
     * to start a fresh streaming response on the same state object (e.g. after the user hits
     * "Regenerate").
     */
    fun reset() {
        buffer.clear()
        revealedLengthState = 0
        revealedCacheLen = -1
        revealedCache = null
        sourceCompleteState = false
        bufferSizeState = 0
        pendingCharsState = 0
        phaseState = TypewriterPhase.Idle
        invalidateTokensCache()
    }

    /**
     * Drops the last revealed character without touching the buffer. Used by
     * [CyclingTypewriterText]'s delete animation. O(1) — just decrements the length counter.
     */
    internal fun deleteLastRevealed() {
        if (revealedLengthState == 0) return
        revealedLengthState -= 1
        pendingCharsState = buffer.length - revealedLengthState
        // Make sure the reveal loop is running so the next append animates again — but only flip
        // up if we're not already mid-reveal. If phase is Stopped/Done, leave it; CyclingTypewriter
        // doesn't use those phases anyway.
        if (phaseState == TypewriterPhase.Waiting || phaseState == TypewriterPhase.Idle) {
            phaseState = if (buffer.length > revealedLengthState) TypewriterPhase.Revealing else phaseState
        }
        invalidateTokensCache()
    }

    // ---- incremental markdown parse cache -------------------------------------------------
    //
    // The renderer re-parses `revealed` on every recomposition. Without caching that's O(N) per
    // character → O(N²) total for an N-character stream. We exploit two facts:
    //   1. The buffer is append-only between [reset] / [deleteLastRevealed] calls, so once we've
    //      parsed length L, the prefix [0, L) is guaranteed to still match.
    //   2. [parseStreamingMarkdown] is prefix-stable: tokens before the last one never change.
    // So when the last token is a [MdToken.Plain] (the common streaming tail), we can drop only
    // that token and re-parse the small tail starting at its original buffer position. The prefix
    // check is O(1) (just a length comparison), and the tail is typically one plain word + the
    // newly revealed character — O(1) in practice.

    private var lastParsedLen: Int = -1
    private var lastParsedTokens: List<MdToken> = emptyList()

    private fun invalidateTokensCache() {
        lastParsedLen = -1
        lastParsedTokens = emptyList()
    }

    /**
     * Returns [parseStreamingMarkdown] applied to [revealed], incrementally cached. Callers that
     * re-render on every recomposition (the Markdown renderer) should prefer this over parsing
     * the string themselves — it avoids the O(N²) re-parse cost for an N-character stream.
     */
    fun revealedTokens(): List<MdToken> {
        val len = revealedLengthState
        // Cache hit — same length, buffer unchanged since last parse.
        if (len == lastParsedLen) return lastParsedTokens

        // Incremental path: buffer grew (append-only) and the trailing token is a Plain run.
        // Prefix-stability guarantees all tokens except the last are unchanged, so we drop the
        // last Plain token and re-parse only from its start position to the new tail.
        if (len > lastParsedLen && lastParsedLen >= 0 && lastParsedTokens.isNotEmpty()) {
            val lastToken = lastParsedTokens.last()
            if (lastToken is MdToken.Plain) {
                val lastTokenStart = lastParsedLen - lastToken.text.length
                val tailTokens = parseStreamingMarkdown(buffer.substring(lastTokenStart, len))
                val newTokens = concatAndMerge(
                    stable = lastParsedTokens, stableKeep = lastParsedTokens.size - 1,
                    tail = tailTokens,
                )
                lastParsedLen = len
                lastParsedTokens = newTokens
                return newTokens
            }
        }

        // Full parse — cache miss, buffer shrank, or last token wasn't Plain.
        val tokens = parseStreamingMarkdown(buffer.substring(0, len))
        lastParsedLen = len
        lastParsedTokens = tokens
        return tokens
    }

    /**
     * Concatenates the first [stableKeep] tokens of [stable] with all of [tail], merging any
     * adjacent Plain tokens across the boundary. Avoids re-traversing the stable prefix's
     * interior (the prefix is already merged from a prior pass).
     */
    private fun concatAndMerge(
        stable: List<MdToken>,
        stableKeep: Int,
        tail: List<MdToken>,
    ): List<MdToken> {
        if (stableKeep == 0 && tail.isEmpty()) return emptyList()
        if (stableKeep == 0) return mergeAdjacentPlain(tail)
        if (tail.isEmpty()) {
            return if (stableKeep == stable.size) stable else stable.subList(0, stableKeep)
        }
        val out = ArrayList<MdToken>(stableKeep + tail.size)
        for (i in 0 until stableKeep) out.add(stable[i])
        // Boundary merge: if the last kept token and the first tail token are both Plain, fuse them.
        val lastKept = out.last()
        val firstTail = tail.first()
        if (lastKept is MdToken.Plain && firstTail is MdToken.Plain) {
            out[out.lastIndex] = MdToken.Plain(lastKept.text + firstTail.text)
            for (i in 1 until tail.size) out.add(tail[i])
        } else {
            for (i in 0 until tail.size) out.add(tail[i])
        }
        return out
    }
}

/**
 * Creates and remembers a [StreamingTypewriterState].
 *
 * @param bufferSoftCap soft cap on the unrevealed buffer length — purely advisory; nothing is
 *   dropped, this is just a hint exposed via [StreamingTypewriterState.bufferSoftCap] that you
 *   can use to render a "model getting ahead of typewriter" indicator.
 */
@Composable
fun rememberStreamingTypewriterState(
    bufferSoftCap: Int = LlmTypewriterDefaults.DefaultBufferSoftCap,
): StreamingTypewriterState = remember { StreamingTypewriterState(bufferSoftCap) }

/** True if [phase] is one of the active reveal phases ([TypewriterPhase.Revealing] or [TypewriterPhase.Waiting]). */
val StreamingTypewriterState.isStreaming: Boolean
    get() = phase == TypewriterPhase.Revealing || phase == TypewriterPhase.Waiting

/**
 * Convenience: a read-only state derivation that flips `true` once at least one char is revealed
 * — useful for hiding a "thinking…" indicator the moment real text arrives.
 */
@Composable
fun StreamingTypewriterState.rememberHasRevealedText(): androidx.compose.runtime.State<Boolean> {
    return remember(this) { derivedStateOf { revealed.isNotEmpty() } }
}
