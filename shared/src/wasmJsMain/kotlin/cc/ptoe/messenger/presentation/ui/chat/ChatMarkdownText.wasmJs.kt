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

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle

@Composable
internal actual fun ChatMarkdownText(
    markdown: String,
    isStreaming: Boolean,
    modifier: Modifier,
) {
    // Keep the browser on a lightweight renderer until llm-typewriter publishes
    // a wasmJs variant. The Rust document parser remains wasm-only.
    val blocks = remember(markdown) { ChatDocumentParser.parseMarkdown(markdown) }
    if (blocks.isEmpty()) {
        Text(markdown, modifier = modifier, style = MaterialTheme.typography.bodyMedium)
    } else {
        ChatDocumentView(blocks, modifier)
    }
}
