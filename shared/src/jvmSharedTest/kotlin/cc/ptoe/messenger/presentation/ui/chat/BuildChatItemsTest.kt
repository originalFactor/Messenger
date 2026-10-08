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

package cc.ptoe.messenger.presentation.ui.chat

import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Message
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildChatItemsTest {

    private fun message(
        id: String,
        role: MessageRole,
        content: String = "",
        parts: List<ContentPart> = emptyList(),
        timestamp: Long,
        status: MessageStatus = MessageStatus.SENT,
        errorMessage: String? = null
    ) = Message(
        id = id,
        conversationId = "conv",
        role = role,
        content = content,
        parts = parts,
        timestamp = timestamp,
        status = status,
        errorMessage = errorMessage
    )

    private fun toolCallPart(callId: String = "call_1") = ContentPart.ToolCall(
        callId = callId,
        name = "terminal",
        arguments = "{\"command\":\"ls\"}"
    )

    private fun toolResultPart(callId: String = "call_1", output: String = "ok") =
        ContentPart.ToolResult(
            callId = callId,
            name = "terminal",
            output = output,
            isError = false
        )

    @Test
    fun completedTurnCollapsesWithFinalTextAfterRounds() {
        val messages = listOf(
            message("u1", MessageRole.USER, "hi", timestamp = 1),
            message("r1", MessageRole.ASSISTANT, "thinking", parts = listOf(toolCallPart()), timestamp = 2),
            message("t1", MessageRole.TOOL, "ok", parts = listOf(toolResultPart()), timestamp = 3),
            message("p1", MessageRole.ASSISTANT, "final text", timestamp = 4)
        )
        val items = buildChatItems(messages)
        assertEquals(3, items.size) // date separator + user row + group
        val group = items[2] as ChatListItem.ToolGroupItem
        assertEquals(listOf("r1"), group.rounds.map { it.id })
        assertEquals(listOf("t1"), group.toolMessages.map { it.id })
        assertEquals("p1", group.finalMessage?.id)
        assertTrue(group.isLastInGroup)
    }

    @Test
    fun inFlightTurnPlaceholderBeforeRoundsCollapsesIntoSameGroup() {
        // Rust 循环在回合开始就落库占位行（时间戳早于工具轮行）；
        // 流式期间必须与工具轮行收编为同一条消息，否则拆成两个气泡。
        val messages = listOf(
            message("u1", MessageRole.USER, "hi", timestamp = 1),
            message(
                "p1", MessageRole.ASSISTANT, "", timestamp = 2,
                status = MessageStatus.SENDING
            ),
            message("r1", MessageRole.ASSISTANT, "", parts = listOf(toolCallPart()), timestamp = 3),
            message(
                "t1", MessageRole.TOOL, "", parts = listOf(toolResultPart(output = "")),
                timestamp = 4, status = MessageStatus.SENDING
            )
        )
        val items = buildChatItems(messages)
        assertEquals(3, items.size) // date separator + user row + group
        val group = items[2] as ChatListItem.ToolGroupItem
        assertEquals(listOf("r1"), group.rounds.map { it.id })
        assertEquals(listOf("t1"), group.toolMessages.map { it.id })
        assertEquals("p1", group.finalMessage?.id)
        assertTrue(group.isLastInGroup)
    }

    @Test
    fun multiRoundInFlightTurnConsumesRoundsAfterPlaceholder() {
        val messages = listOf(
            message("u1", MessageRole.USER, "hi", timestamp = 1),
            message("p1", MessageRole.ASSISTANT, "", timestamp = 2, status = MessageStatus.SENDING),
            message("r1", MessageRole.ASSISTANT, "one", parts = listOf(toolCallPart("c1")), timestamp = 3),
            message("t1", MessageRole.TOOL, "out1", parts = listOf(toolResultPart("c1")), timestamp = 4),
            message("r2", MessageRole.ASSISTANT, "two", parts = listOf(toolCallPart("c2")), timestamp = 5),
            message("t2", MessageRole.TOOL, "out2", parts = listOf(toolResultPart("c2")), timestamp = 6)
        )
        val items = buildChatItems(messages)
        val group = items[2] as ChatListItem.ToolGroupItem
        assertEquals(listOf("r1", "r2"), group.rounds.map { it.id })
        assertEquals(listOf("t1", "t2"), group.toolMessages.map { it.id })
        assertEquals("p1", group.finalMessage?.id)
    }

    @Test
    fun cancelledTurnPlaceholderPromotedBeforeRoundsStillCollapses() {
        // 取消的回合：占位行保留部分文本并提升为 SENT，行序不变。
        val messages = listOf(
            message("u1", MessageRole.USER, "hi", timestamp = 1),
            message("p1", MessageRole.ASSISTANT, "partial", timestamp = 2),
            message("r1", MessageRole.ASSISTANT, "", parts = listOf(toolCallPart()), timestamp = 3),
            message("t1", MessageRole.TOOL, "out", parts = listOf(toolResultPart()), timestamp = 4)
        )
        val items = buildChatItems(messages)
        val group = items[2] as ChatListItem.ToolGroupItem
        assertEquals("p1", group.finalMessage?.id)
        assertEquals(listOf("r1"), group.rounds.map { it.id })
    }

    @Test
    fun errorPlaceholderStaysOutOfGroup() {
        val messages = listOf(
            message("u1", MessageRole.USER, "hi", timestamp = 1),
            message("r1", MessageRole.ASSISTANT, "", parts = listOf(toolCallPart()), timestamp = 2),
            message("t1", MessageRole.TOOL, "out", parts = listOf(toolResultPart()), timestamp = 3),
            message(
                "p1", MessageRole.ASSISTANT, "", timestamp = 4,
                status = MessageStatus.ERROR, errorMessage = "boom"
            )
        )
        val items = buildChatItems(messages)
        val group = items[2] as ChatListItem.ToolGroupItem
        assertEquals(listOf("r1"), group.rounds.map { it.id })
        assertEquals(null, group.finalMessage)
        // ERROR 占位行独立渲染错误气泡（保留重试入口）。
        val errorItem = items[3] as ChatListItem.MessageItem
        assertEquals("p1", errorItem.message.id)
    }

    @Test
    fun plainAssistantRowFollowedByUserStaysPlain() {
        val messages = listOf(
            message("u1", MessageRole.USER, "hi", timestamp = 1),
            message("a1", MessageRole.ASSISTANT, "hello", timestamp = 2),
            message("u2", MessageRole.USER, "again", timestamp = 3)
        )
        val items = buildChatItems(messages)
        assertTrue(items[1] is ChatListItem.MessageItem)
        assertTrue(items[2] is ChatListItem.MessageItem)
    }

    @Test
    fun orphanToolRowFallsBackToToolGroup() {
        val messages = listOf(
            message("u1", MessageRole.USER, "hi", timestamp = 1),
            message("t1", MessageRole.TOOL, "out", parts = listOf(toolResultPart()), timestamp = 2)
        )
        val items = buildChatItems(messages)
        val group = items[2] as ChatListItem.ToolGroupItem
        assertEquals(emptyList<Message>(), group.rounds)
        assertEquals(listOf("t1"), group.toolMessages.map { it.id })
        assertEquals(null, group.finalMessage)
    }
}
