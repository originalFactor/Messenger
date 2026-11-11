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

/**
 * The cadence at which the typewriter reveals queued characters.
 *
 * The internal reveal loop reads [delayForNext] before each reveal step. Each curve gets the
 * character that's about to be revealed, plus the character that was just revealed (or `null` at
 * the start), so it can decide whether to pause longer on punctuation, speed through whitespace,
 * etc.
 */
fun interface SpeedCurve {

    /**
     * @param baseDelayMs the configured baseline delay between two reveals (the "fastest" tick).
     * @param previous the most recently revealed character, or `null` before the first reveal.
     * @param next the character that is about to be revealed.
     * @return the delay (in milliseconds) the reveal loop should wait before revealing [next].
     */
    fun delayForNext(baseDelayMs: Long, previous: Char?, next: Char): Long

    companion object {

        /** Constant cadence — every character takes the same time. */
        val Linear: SpeedCurve = SpeedCurve { base, _, _ -> base }

        /**
         * Starts fast, slows toward the end of long bursts — kept for parity with common animation
         * tooling. Implemented as a slight stretch on whitespace boundaries so the reader has time
         * to catch up at the end of a word.
         */
        val EaseOut: SpeedCurve = SpeedCurve { base, _, next ->
            if (next == ' ' || next == '\n') (base * 1.4f).toLong() else base
        }

        /**
         * Simulates a human typist:
         *  - Sentence-ending punctuation (`.`, `!`, `?`) pauses for ~6× the base delay.
         *  - Other punctuation (`,`, `;`, `:`) pauses for ~3×.
         *  - Newlines pause for ~5×.
         *  - Word-end whitespace gets a tiny breather (~1.4×).
         *  - The first reveal of a new paragraph (previous is `\n`) is slightly slower (~1.8×).
         *  - Everything else is the base delay.
         */
        val Natural: SpeedCurve = SpeedCurve { base, prev, next ->
            naturalDelay(base, prev, next)
        }
    }
}

internal fun naturalDelay(base: Long, prev: Char?, next: Char): Long {
    if (prev == '\n' && next != '\n') return (base * 1.8f).toLong()
    return when (next) {
        '.', '!', '?' -> base * 6
        ',', ';', ':' -> base * 3
        '\n' -> base * 5
        ' ', '\t' -> (base * 1.4f).toLong()
        else -> base
    }
}
