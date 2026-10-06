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

sealed class RenderBlock {
    abstract val id: Long
    abstract val isFinalized: Boolean

    data class Paragraph(
        override val id: Long,
        val text: CharSequence,
        override val isFinalized: Boolean
    ) : RenderBlock()

    data class Heading(
        override val id: Long,
        val level: Int,
        val text: String,
        override val isFinalized: Boolean
    ) : RenderBlock()

    data class Code(
        override val id: Long,
        val language: String?,
        val code: String,
        override val isFinalized: Boolean
    ) : RenderBlock()

    data class Math(
        override val id: Long,
        val formula: String,
        override val isFinalized: Boolean
    ) : RenderBlock()

    data class Quote(
        override val id: Long,
        val text: String,
        override val isFinalized: Boolean
    ) : RenderBlock()

    data class Think(
        override val id: Long,
        val content: String,
        override val isFinalized: Boolean
    ) : RenderBlock()

    data class ToolCall(
        override val id: Long,
        val callId: String,
        val name: String,
        val arguments: String,
        val output: String?,
        val isError: Boolean,
        override val isFinalized: Boolean
    ) : RenderBlock()

    data class Table(
        override val id: Long,
        val head: List<String>,
        val rows: List<List<String>>,
        override val isFinalized: Boolean
    ) : RenderBlock()

    /** One rendered list entry (marker kind + text). */
    data class ListItemData(
        val indent: Int,
        val ordered: Boolean,
        val number: Int,
        val text: CharSequence
    )

    data class ListBlock(
        override val id: Long,
        val items: List<ListItemData>,
        override val isFinalized: Boolean
    ) : RenderBlock()

    data class Divider(
        override val id: Long
    ) : RenderBlock() {
        override val isFinalized: Boolean get() = true
    }
}
