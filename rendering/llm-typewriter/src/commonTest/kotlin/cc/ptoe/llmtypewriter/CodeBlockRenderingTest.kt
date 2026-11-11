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

class CodeBlockRenderingTest {

    @Test
    fun codeBlockLanguageLabel_usesTrimmedLanguageOnlyWhenPresent() {
        assertEquals("kotlin", codeBlockLanguageLabel(" kotlin "))
        assertEquals(null, codeBlockLanguageLabel("   "))
        assertEquals(null, codeBlockLanguageLabel(null))
    }

    @Test
    fun closedCodeBlock_trimsFenceClosingNewlineForDisplay() {
        assertEquals("val answer = 42", codeBlockContentForDisplay("val answer = 42\n", closed = true))
    }

    @Test
    fun closedCodeBlock_preservesIntentionalBlankLineBeforeFence() {
        assertEquals("val answer = 42\n", codeBlockContentForDisplay("val answer = 42\n\n", closed = true))
    }

    @Test
    fun openCodeBlock_keepsTrailingNewlineWhileStreaming() {
        assertEquals("val answer = 42\n", codeBlockContentForDisplay("val answer = 42\n", closed = false))
    }
}
