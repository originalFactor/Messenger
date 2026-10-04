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

package cc.ptoe.messenger.renderer

import org.junit.Test
import kotlin.system.measureNanoTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RendererBenchmarkTest {

    @Test
    fun benchmark10000CharDocumentParsing() {
        val sb = StringBuilder()
        sb.append("[\n")
        val blockCount = 100
        for (i in 1..blockCount) {
            val comma = if (i == blockCount) "" else ","
            if (i % 5 == 0) {
                sb.append("""
                    {
                      "kind": "code_block",
                      "id": $i,
                      "language": "kotlin",
                      "code": "fun test$i() { val x = 42; println(x) }",
                      "status": "finalized"
                    }$comma
                """.trimIndent())
            } else if (i % 7 == 0) {
                sb.append("""
                    {
                      "kind": "tool_call",
                      "id": $i,
                      "call_id": "call_$i",
                      "name": "terminal",
                      "arguments": "{\"cmd\":\"ls\"}",
                      "output": "line 1\nline 2\nline 3",
                      "is_error": false,
                      "status": "finalized"
                    }$comma
                """.trimIndent())
            } else if (i % 11 == 0) {
                sb.append("""
                    {
                      "kind": "math",
                      "id": $i,
                      "formula": "\\sum_{i=1}^{$i} x_i = \\Omega",
                      "status": "finalized"
                    }$comma
                """.trimIndent())
            } else {
                sb.append("""
                    {
                      "kind": "paragraph",
                      "id": $i,
                      "inlines": [
                        {"type": "text", "text": "Paragraph item $i detailing architecture performance metrics. "},
                        {"type": "bold", "text": "Token batching and incremental parsing."}
                      ],
                      "status": "finalized"
                    }$comma
                """.trimIndent())
            }
        }
        sb.append("\n]")

        val json = sb.toString()
        assertTrue(json.length > 10000, "Document length must be > 10,000 chars: was ${json.length}")

        // Warm up
        DocumentParser.parseBlocksJson(json)

        val elapsedNanos = measureNanoTime {
            val blocks = DocumentParser.parseBlocksJson(json)
            assertEquals(blockCount, blocks.size)
        }

        val elapsedMs = elapsedNanos / 1_000_000.0
        println("Android Renderer Benchmark: 10,000+ char document (100 blocks) parsed in $elapsedMs ms")

        // 10,000+ chars parsed in under 50ms on JVM test runner
        assertTrue(elapsedMs < 50.0, "10k char parsing took too long: $elapsedMs ms")
    }

    @Test
    fun benchmark50ToolCallsParsing() {
        val sb = StringBuilder()
        sb.append("[\n")
        val count = 60
        for (i in 1..count) {
            val comma = if (i == count) "" else ","
            sb.append("""
                {
                  "kind": "tool_call",
                  "id": $i,
                  "call_id": "call_$i",
                  "name": "workspace_read",
                  "arguments": "{\"path\":\"src/lib.rs\"}",
                  "output": "read 120 lines from file successfully",
                  "is_error": false,
                  "status": "finalized"
                }$comma
            """.trimIndent())
        }
        sb.append("\n]")

        val json = sb.toString()
        val elapsedNanos = measureNanoTime {
            val blocks = DocumentParser.parseBlocksJson(json)
            assertEquals(count, blocks.size)
        }

        val elapsedMs = elapsedNanos / 1_000_000.0
        println("Android Renderer Benchmark: 60 tool calls parsed in $elapsedMs ms")
        assertTrue(elapsedMs < 30.0, "60 tool calls parsing took too long: $elapsedMs ms")
    }

    @Test
    fun benchmarkIncrementalDiffBatchJsonParsing() {
        // Test parsing 200 sequential DiffBatches simulating rapid SSE token batch arrivals
        val iterations = 200
        val elapsedNanos = measureNanoTime {
            for (i in 1..iterations) {
                val batchJson = """
                    {
                      "diffs": [
                        {
                          "diff": "update",
                          "block": {
                            "kind": "paragraph",
                            "id": 1,
                            "inlines": [
                              {"type": "text", "text": "Streaming delta token chunk $i "}
                            ],
                            "status": "streaming"
                          }
                        }
                      ]
                    }
                """.trimIndent()

                val root = kotlinx.serialization.json.Json.parseToJsonElement(batchJson) as? kotlinx.serialization.json.JsonObject
                val diffs = root?.get("diffs") as? kotlinx.serialization.json.JsonArray
                assertEquals(1, diffs?.size)
            }
        }

        val elapsedMs = elapsedNanos / 1_000_000.0
        val avgPerBatchMs = elapsedMs / iterations
        println("Android Renderer Benchmark: $iterations DiffBatches parsed in $elapsedMs ms (avg $avgPerBatchMs ms/batch)")

        // Each diff batch must parse in < 0.5ms on average
        assertTrue(avgPerBatchMs < 0.5, "Average diff batch latency too high: $avgPerBatchMs ms")
    }
}
