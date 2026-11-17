/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.domain.model.MessageRole
import cc.ptoe.messenger.domain.model.MessageStatus
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AcpWearHistoryTest {
    @Test fun mixedNativeAndPagedAcpHistoryPreservesIdentityOrderAndStatus() = runBlocking {
        val requests = mutableListOf<String?>()
        val stub = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ -> null } as CoreBridge
        val bridge = object : CoreBridge by stub {
            override fun subscribe(listener: (String, List<String>) -> Unit) = Unit
            override fun listMessagesByConversationJson(conversationId: String): String {
                check(conversationId == "native")
                return """[{"id":"n","conversation_id":"native","role":"user","content":"native text","timestamp":1,"status":"sent"}]"""
            }
            override suspend fun localAcpHistory(sessionId: String, before: String?): JsonObject {
                assertEquals("acp:session", sessionId)
                requests.add(before)
                val page = if (before == null)
                    """{"type":"History","messages":[{"id":"a2","role":"assistant","text":"failed","timestamp":3,"status":"error"}],"before":"a2"}"""
                else """{"type":"History","messages":[{"id":"a1","role":"user","text":"hello","timestamp":2,"status":"sent"}],"before":null}"""
                return Json.parseToJsonElement(page).jsonObject
            }
        }
        val result = RustMessageRepository(bridge).getByConversationIds(listOf("native", "acp:session"))
        assertEquals(listOf("n", "a1", "a2"), result.map { it.id })
        assertEquals(listOf(null, "a2"), requests)
        assertEquals(listOf(MessageRole.USER, MessageRole.USER, MessageRole.ASSISTANT), result.map { it.role })
        assertEquals(MessageStatus.ERROR, result.last().status)
        assertEquals("failed", result.last().errorMessage)
        assertEquals(listOf(1L, 2L, 3L), result.map { it.timestamp })
    }

    @Test fun malformedOrRepeatedAcpPagesFailInsteadOfReturningPartialHistory(): Unit = runBlocking {
        val stub = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ -> null } as CoreBridge
        val bridge = object : CoreBridge by stub {
            override suspend fun localAcpHistory(sessionId: String, before: String?): JsonObject =
                Json.parseToJsonElement("""{"type":"History","messages":[{"id":"same","role":"user","text":"x","status":"sent","timestamp":1}],"before":"same"}""").jsonObject
        }
        assertFailsWith<IllegalStateException> { readAcpHistory(bridge, "acp:session") }
        val missingCursor = object : CoreBridge by stub {
            override suspend fun localAcpHistory(sessionId: String, before: String?): JsonObject =
                Json.parseToJsonElement("""{"type":"History","messages":[{"id":"m","role":"user","text":"x","status":"sent","timestamp":1}]}""").jsonObject
        }
        assertFailsWith<IllegalStateException> { readAcpHistory(missingCursor, "acp:session") }
        assertFailsWith<IllegalStateException> {
            projectAcpHistory("acp:session", Json.parseToJsonElement("""[{"id":"x","role":"assistant","text":"x","status":"sent"}]""") as kotlinx.serialization.json.JsonArray)
        }
    }

    @Test fun nonTextAndEventOnlyAcpMessagesRemainVisibleOnWatch() {
        val rows = Json.parseToJsonElement("""[{"id":"i","role":"user","text":"","status":"sent","timestamp":1,"content":[{"type":"image","data":"abc"}]},{"id":"r","role":"user","text":"","status":"sent","timestamp":2,"content":[{"type":"resource","resource":{"uri":"attachment:///a"}}]},{"id":"v","role":"user","text":"","status":"sent","timestamp":3,"content":[{"type":"audio"}]},{"id":"l","role":"user","text":"","status":"sent","timestamp":4,"content":[{"type":"resource_link"}]},{"id":"t","role":"assistant","text":"","status":"sent","timestamp":5,"events":[{"type":"ThoughtDelta","text":"thinking"},{"type":"ToolUpdate","update":{"status":"failed","title":"read"}}]}]""") as kotlinx.serialization.json.JsonArray
        assertEquals(listOf("[图片]", "[附件]", "[音频]", "[附件]", "[工具调用失败]"), projectAcpHistory("acp:session", rows).map { it.content })
        val unsupported = Json.parseToJsonElement("""[{"id":"x","role":"user","text":"","status":"sent","timestamp":1,"content":[{"type":"unknown"}]}]""") as kotlinx.serialization.json.JsonArray
        assertFailsWith<IllegalArgumentException> { projectAcpHistory("acp:session", unsupported) }
    }
}
