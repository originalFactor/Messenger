/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.ContentPartCodec
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.model.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KotlinProviderRequestPreparerTest {
    @Test fun unrelatedErrorDoesNotJoinAnInterruptedToolExchange() {
        val conversation = ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)
        val call = MessageRow("call", "c", "assistant", "", ContentPartCodec.encode(listOf(
            ContentPart.ToolCall("a1", "lookup", "{}"), ContentPart.ToolCall("a2", "lookup", "{}"))), 1, "sent", null)
        val result = MessageRow("a1", "c", "tool", "ok", ContentPartCodec.encode(listOf(
            ContentPart.ToolResult("a1", "lookup", "ok", false))), 2, "sent", null)
        val failure = MessageRow("failed", "c", "assistant", "Automatic approval failed: interrupted", null, 3, "error", "Automatic approval failed: interrupted")
        val unrelated = MessageRow("a2", "c", "tool", "different failure", ContentPartCodec.encode(listOf(
            ContentPart.ToolResult("a2", "lookup", "different failure", true))), 4, "sent", null)
        val user = MessageRow("user", "c", "user", "continue", null, 5, "sent", null)
        val request = KotlinProviderRequestPreparer().rebuildContext(conversation, listOf(call, result, failure, unrelated, user), "")
        assertEquals(listOf("user"), request.map { it.role })
        assertEquals("continue", request.single().content.jsonPrimitive.content)
    }

    @Test fun interruptedLaterToolGroupKeepsEarlierCompleteExchange() {
        val conversation = ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)
        fun row(id: String, role: String, parts: List<ContentPart>, time: Long, status: String = "sent") =
            MessageRow(id, "c", role, "", ContentPartCodec.encode(parts), time, status, null)
        val first = row("first", "assistant", listOf(
            ContentPart.ToolCall("a1", "lookup", "{}"), ContentPart.ToolCall("a2", "lookup", "{}"),
        ), 1)
        val firstResults = listOf(
            row("a1", "tool", listOf(ContentPart.ToolResult("a1", "lookup", "first result", false)), 2),
            row("a2", "tool", listOf(ContentPart.ToolResult("a2", "lookup", "second result", false)), 3),
        )
        val failed = row("second", "assistant", listOf(
            ContentPart.ToolCall("b1", "lookup", "{}"), ContentPart.ToolCall("b2", "lookup", "{}"),
        ), 4, "error")
        val orphan = row("b1", "tool", listOf(ContentPart.ToolResult("b1", "lookup", "partial result", false)), 5)
        val user = MessageRow("user", "c", "user", "retry", null, 6, "sent", null)
        val preparer = KotlinProviderRequestPreparer()

        // Manual retry can remove the failed assistant row, leaving its result behind.
        for (boundary in listOf(listOf(failed), emptyList())) {
            val request = preparer.rebuildContext(conversation, listOf(first) + firstResults + boundary + orphan + user, "")
            assertEquals(listOf("assistant", "tool", "tool", "user"), request.map { it.role })
            assertEquals(listOf("a1", "a2"), request.first().toolCalls!!.map { it.id })
            assertEquals(listOf("a1", "a2"), request.drop(1).take(2).map { it.toolCallId })
            assertTrue(request.none { it.toolCallId == "b1" })
        }
    }

    @Test fun artifactHistoryCompactsOnlyCommittedWritesAndOldReadResults() {
        val conversation = ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)
        val source = "<html>private source</html>"
        val arguments = buildJsonObject { put("action", "create"); put("files", buildJsonObject { put("index.html", source) }) }.toString()
        val call = ContentPart.ToolCall("id", "messenger_artifacts", arguments)
        val ref = buildJsonObject { put("type", "artifact"); put("id", "demo"); put("revision", 2) }
        fun row(id: String, role: String, part: ContentPart, timestamp: Long) =
            MessageRow(id, "c", role, "", ContentPartCodec.encode(listOf(part)), timestamp, "sent", null)
        val assistant = row("call", "assistant", call, 1)
        fun result(error: Boolean = false, reference: JsonElement? = ref) =
            row("result", "tool", ContentPart.ToolResult("id", "messenger_artifacts", source, error, reference), 2)
        val user = MessageRow("user", "c", "user", "next", null, 3, "sent", null)
        val preparer = KotlinProviderRequestPreparer()
        fun request(rows: List<MessageRow>) = preparer.rebuildContext(conversation, rows, "")
        val fresh = request(listOf(assistant, result()))
        val compacted = Json.parseToJsonElement(checkNotNull(fresh[0].toolCalls!!.single().function!!.arguments)).jsonObject
        assertEquals(setOf("action", "id", "revision", "contentStored"), compacted.keys)
        assertEquals("demo", compacted.getValue("id").jsonPrimitive.content)
        assertEquals(2, compacted.getValue("revision").jsonPrimitive.int)
        assertEquals(source, fresh[1].content.jsonPrimitive.content)
        val historical = request(listOf(assistant, result(), user))
        assertTrue(historical[1].content.jsonPrimitive.content.contains("Artifact \"demo\" revision 2"))
        assertTrue(!historical[1].content.jsonPrimitive.content.contains(source))
        assertEquals(arguments, (ContentPartCodec.decode(assistant.partsJson).single() as ContentPart.ToolCall).arguments)
        for (uncommitted in listOf(result(error = true), result(reference = buildJsonObject { put("type", "artifact"); put("id", "demo"); put("revision", 0) }))) {
            val wire = request(listOf(assistant, uncommitted, user))
            assertEquals(arguments, wire[0].toolCalls!!.single().function!!.arguments)
            assertEquals(source, wire[1].content.jsonPrimitive.content)
        }
        assertTrue(request(listOf(assistant)).isEmpty())
        val unrelated = row("other", "tool", ContentPart.ToolResult("other", "messenger_artifacts", source, false, ref), 2)
        assertTrue(request(listOf(assistant, unrelated)).isEmpty())
    }

    @Test fun buildsImagesReasoningAndToolHistoryAndFailsClosed() = runBlocking {
        val dir = Files.createTempDirectory("turn-preparer")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val rows = db.rows()
            val default = Agent("a", "Agent", systemPrompt = "system", defaultModelId = "m", temperature = 0.6f, topP = 0.9f, maxTokens = 500, isDefault = true, createdAt = 1, updatedAt = 1)
            RoomAgentRepository(db).ensureDefaultAgent(default)
            val agent = checkNotNull(rows.agent("a"))
            val provider = ProviderRow("p", "provider", "https://example.test", "key", "chat", false, 1, 1)
            val model = ModelRow("m", "p", "wire-model", "Model", true, 10000, null, null, null, null, "text,image", "text", true, true, false, true, 1, null, null)
            val conversation = ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, "reasoning_summary", null, 0, 0, 0)
            val image = ContentPart.Image(MessageImage("data:image/png;base64,AA==", "/tmp/image"))
            val messages = listOf(
                MessageRow("u", "c", "user", "look", ContentPartCodec.encode(listOf(ContentPart.Text("look"), image)), 1, "SENT", null),
                MessageRow("a1", "c", "assistant", "<think>secret</think>answer", null, 2, "sent", null),
                MessageRow("call", "c", "assistant", "", ContentPartCodec.encode(listOf(ContentPart.ToolCall("id", "lookup", "{}"))), 3, "sent", null),
                MessageRow("result", "c", "tool", "ok", ContentPartCodec.encode(listOf(ContentPart.ToolResult("id", "lookup", "ok", false))), 4, "sent", null),
            )
            val prepare = KotlinProviderRequestPreparer()
            val request = prepare.prepare(conversation, agent, agent, model, provider, messages)
            assertEquals(5, request.messages.size)
            assertEquals("wire-model", request.model)
            assertEquals("system", request.messages[0].content.jsonPrimitive.content)
            assertEquals("image_url", request.messages[1].content.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("answer", request.messages[2].content.jsonPrimitive.content)
            assertEquals("lookup", request.messages[3].toolCalls!!.single().function!!.name)
            assertEquals("id", request.messages[4].toolCallId)
            assertFailsWith<IllegalArgumentException> { prepare.prepare(conversation, agent.copy(toolsEnabled = true), agent, model, provider, messages) }
            assertFailsWith<IllegalArgumentException> { prepare.prepare(conversation, agent, agent, model, provider, messages + MessageRow("bad", "c", "user", "", """[{"type":"future"}]""", 5, "sent", null)) }
            assertEquals(5, prepare.prepare(conversation.copy(contextTokens = 9000), agent, agent, model, provider, messages).messages.size)
            assertTrue(request.stream)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
