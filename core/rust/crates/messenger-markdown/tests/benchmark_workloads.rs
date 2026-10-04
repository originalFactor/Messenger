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

use messenger_markdown::StreamingSession;
use std::time::Instant;

/// TARGET.md §19 Workload 1 & 2: 100 tokens/s & 200 tokens/s streaming throughput.
#[test]
fn benchmark_streaming_100_and_200_tokens_per_sec() {
    let mut session = StreamingSession::new();
    let tokens: Vec<&str> = vec![
        "The ", "quick ", "brown ", "fox ", "jumps ", "over ", "the ", "lazy ", "dog.\n\n",
        "## Section 1\n", "Here ", "is ", "some **bold** ", "and *italic* ", "markdown.\n\n",
        "```rust\n", "fn ", "main() ", "{\n", "    println!(\"hi\");\n", "}\n```\n\n",
        "$$\n", "\\frac{a}{b} ", "+ ", "\\sqrt{c}\n", "$$\n\n"
    ];

    let start = Instant::now();
    let mut total_diffs = 0;

    for &token in tokens.iter().cycle().take(400) {
        session.feed(token);
        let batch = session.drain_batch();
        total_diffs += batch.diffs.len();
    }
    let final_batch = session.finish();
    total_diffs += final_batch.diffs.len();

    let elapsed = start.elapsed();
    let tokens_processed = 400;
    let tokens_per_sec = (tokens_processed as f64) / elapsed.as_secs_f64();

    println!(
        "Workload 1&2 (Streaming): 400 tokens in {:?} ({:.0} tokens/sec), diffs: {}",
        elapsed, tokens_per_sec, total_diffs
    );

    // Document engine must easily exceed 5,000 tokens/sec on modern CPUs
    assert!(tokens_per_sec > 5000.0, "Streaming throughput too low: {:.0} tokens/sec", tokens_per_sec);
    assert!(total_diffs > 0, "Expected generated diffs");
}

/// TARGET.md §19 Workload 3 & 4: 10,000 token long conversation incremental document parsing.
#[test]
fn benchmark_10000_token_conversation_incremental() {
    let mut session = StreamingSession::new();

    let paragraph_template = "This is a detailed paragraph of the long conversation discussing architecture, \
        incremental parsing, performance benchmarking, memory allocation, and zero-recomposition UI design. \
        Notice that `BlockId` remains stable across cycles while tokens are batched.\n\n";

    let num_paragraphs = 250; // 250 * ~40 tokens ≈ 10,000 tokens
    let mut total_diffs = 0;

    let start = Instant::now();
    for i in 0..num_paragraphs {
        if i % 25 == 0 {
            session.feed(&format!("# Section {}\n\n", i / 25));
        }
        // Feed in 10-char chunks simulating network streaming
        for chunk in paragraph_template.as_bytes().chunks(20) {
            let chunk_str = std::str::from_utf8(chunk).unwrap();
            session.feed(chunk_str);
            let batch = session.drain_batch();
            total_diffs += batch.diffs.len();
        }
    }
    let final_batch = session.finish();
    total_diffs += final_batch.diffs.len();

    let elapsed = start.elapsed();
    let total_chars = num_paragraphs * paragraph_template.len();
    let approx_tokens = total_chars / 4;

    println!(
        "Workload 3&4 (10k tokens): ~{} tokens ({} chars) parsed in {:?}, total diffs: {}, final blocks: {}",
        approx_tokens, total_chars, elapsed, total_diffs, session.document().blocks().len()
    );

    // Parsing 10,000 tokens incrementally should complete in < 150ms
    assert!(elapsed.as_millis() < 150, "10k token parsing took too long: {:?}", elapsed);
    assert_eq!(session.document().blocks().len(), num_paragraphs + 10);
}

/// TARGET.md §19 Workload 8: 50+ sequential tool outputs streaming.
#[test]
fn benchmark_50_tool_calls_sequential() {
    let mut session = StreamingSession::new();
    let start = Instant::now();
    let mut total_diffs = 0;

    for i in 1..=60 {
        session.feed(&format!("Initiating step {}...\n\n", i));
        let call_json = format!(
            "<tool_call>{{\"call_id\":\"call_{}\",\"name\":\"terminal\",\"arguments\":\
            \"{{\\\"cmd\\\":\\\"cargo test --test {}\\\"}}\",\"output\":\"test result ok for {}\",\
            \"is_error\":false}}</tool_call>\n\n",
            i, i, i
        );
        session.feed(&call_json);
        let batch = session.drain_batch();
        total_diffs += batch.diffs.len();
    }

    let final_batch = session.finish();
    total_diffs += final_batch.diffs.len();
    let elapsed = start.elapsed();

    let tool_count = session.document().blocks().iter().filter(|b| matches!(b, messenger_document::Block::ToolCall { .. })).count();

    println!(
        "Workload 8 (50+ Tool Calls): 60 tool calls processed in {:?}, tool blocks: {}, total diffs: {}",
        elapsed, tool_count, total_diffs
    );

    assert_eq!(tool_count, 60, "Expected 60 tool blocks");
    assert!(elapsed.as_millis() < 50, "50+ tool calls parsing took too long: {:?}", elapsed);
}

/// TARGET.md §19 Workload 5, 6, 7: Large code blocks and complex LaTeX formulas.
#[test]
fn benchmark_large_code_and_latex_blocks() {
    let mut session = StreamingSession::new();
    let start = Instant::now();

    // Large code block
    session.feed("```kotlin\n");
    for i in 0..300 {
        session.feed(&format!("    val item{} = computeItem({})\n", i, i));
    }
    session.feed("```\n\n");

    // Complex LaTeX formula
    session.feed("$$\n\\sum_{i=1}^{N} \\int_{0}^{\\infty} \\frac{\\partial^2 \\psi}{\\partial x^2} dx = \\Omega\n$$\n\n");

    let _final_batch = session.finish();
    let elapsed = start.elapsed();

    println!(
        "Workload 5-7 (Code & Math): 300-line code block + LaTeX parsed in {:?}, final blocks: {}",
        elapsed, session.document().blocks().len()
    );

    assert_eq!(session.document().blocks().len(), 2);
    assert!(elapsed.as_millis() < 30, "Large code/math parsing took too long: {:?}", elapsed);
}
