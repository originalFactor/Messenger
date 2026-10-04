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

package cc.ptoe.messenger.presentation.ui.components

import org.junit.Test
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WearTextFormatterTest {

    @Test
    fun testPlaintextFastPath() {
        val plain = "Hello from phone, this is a plain message."
        val formatted = WearTextFormatter.format(plain, isPending = false)
        assertEquals(plain, formatted)
    }

    @Test
    fun testLiveStreamingThinkBlockFolding() {
        val streamingThink = "<think>Analyzing user request step 1, retrieving models..."
        val formatted = WearTextFormatter.format(streamingThink, isPending = true)
        assertTrue(formatted.startsWith("💭 "), "Must format live think as thought bubble")
        assertTrue(formatted.contains("Analyzing user request"))
    }

    @Test
    fun testClosedThinkBlockStripping() {
        val content = "<think>Deep chain of reasoning finished.</think>The capital of France is Paris."
        val formatted = WearTextFormatter.format(content, isPending = false)
        assertEquals("The capital of France is Paris.", formatted)
    }

    @Test
    fun testToolCallCompacting() {
        val toolContent = "Starting step.\n<tool_call>{\"name\":\"terminal\",\"arguments\":\"{}\",\"is_error\":false}</tool_call>\nDone."
        val formatted = WearTextFormatter.format(toolContent, isPending = false)
        assertTrue(formatted.contains("🔧 [terminal]"), "Must compact tool call into badge")

        val errorTool = "<tool_call>{\"name\":\"fetch\",\"arguments\":\"{}\",\"is_error\":true}</tool_call>"
        val formattedError = WearTextFormatter.format(errorTool, isPending = false)
        assertTrue(formattedError.contains("⚠️ [fetch]"), "Must format error tool with warning badge")
    }

    @Test
    fun testCodeBlockCompacting() {
        val codeContent = "Here is the implementation:\n```kotlin\nval a = 1\nval b = 2\nval c = 3\nval d = 4\nval e = 5\n```\nEnjoy!"
        val formatted = WearTextFormatter.format(codeContent, isPending = false)
        assertTrue(formatted.contains("💻 [kotlin: 5 lines]"), "Must compact code lines count")
    }

    @Test
    fun testMathBlockCompacting() {
        val mathContent = "Result is: $$\n\\sum_{i=1}^n x_i\n$$"
        val formatted = WearTextFormatter.format(mathContent, isPending = false)
        assertTrue(formatted.contains("📐 \\sum_{i=1}^n x_i"), "Must compact math display formula")
    }

    @Test
    fun benchmarkWearFormattingPerformance() {
        val mixedContent = """
            <think>Analyzing performance on wearable CPU...</think>
            Here is the result:
            <tool_call>{"name":"benchmark","arguments":"{}","is_error":false}</tool_call>
            ```rust
            fn test() {
                println!("hello");
            }
            ```
            Formula: ${'$'}${'$'}E = mc^2${'$'}${'$'}
        """.trimIndent()

        // Warmup
        WearTextFormatter.format(mixedContent, isPending = false)

        val iterations = 500
        val elapsedNanos = measureNanoTime {
            for (i in 1..iterations) {
                WearTextFormatter.format(mixedContent, isPending = false)
            }
        }

        val elapsedMs = elapsedNanos / 1_000_000.0
        val avgPerCallMs = elapsedMs / iterations
        println("WearTextFormatter Benchmark: $iterations calls in $elapsedMs ms (avg $avgPerCallMs ms/call)")

        // On wearable CPU simulation, each call should be sub-millisecond (< 0.2ms)
        assertTrue(avgPerCallMs < 0.2, "Wear formatting took too long: $avgPerCallMs ms")
    }
}
