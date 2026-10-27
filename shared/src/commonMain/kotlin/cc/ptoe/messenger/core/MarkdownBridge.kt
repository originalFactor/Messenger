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

package cc.ptoe.messenger.core

/**
 * Parse Markdown into the Rust core's Document AST JSON (`Block` array).
 *
 * The Rust core's incremental parser is the ONLY Markdown parser in the
 * product: Android renders its `DiffBatch` stream through `DocumentSession`,
 * the TUI renders the same `Block` tree, and the Compose renderers (Desktop /
 * Web) map this static-text entry point. There is deliberately no Kotlin
 * Markdown implementation to drift from it.
 *
 * It is an `expect`/`actual` over the boundary's `parse_markdown_to_blocks_json`
 * export rather than a `CoreBridge` method because parsing is a pure function:
 * it must work in previews and tests, which have no store handle to attach.
 */
expect fun parseMarkdownToDocumentJson(markdown: String): String
