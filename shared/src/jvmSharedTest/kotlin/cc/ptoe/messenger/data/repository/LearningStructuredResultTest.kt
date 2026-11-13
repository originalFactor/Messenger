package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.data.local.ContentPartCodec
import cc.ptoe.messenger.domain.model.ContentPart
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LearningStructuredResultTest {
    @Test fun unknownStructuredResultsSurviveHistoryRoundtripWithReadableFallback() {
        val payload = Json.parseToJsonElement("""{"version":8,"type":"futureLesson","id":"lesson","nested":{"unknown":[1,true,"text"]}}""")
        val result = ContentPart.ToolResult("call", "future_tool", "Saved lesson", structuredContent = payload)
        val decoded = ContentPartCodec.decode(ContentPartCodec.encode(listOf(result))).single() as ContentPart.ToolResult
        assertEquals(payload, decoded.structuredContent)
        assertEquals("Saved lesson", decoded.output)
    }
    @Test fun legacyTextOnlyResultsRemainReadable() {
        val decoded = ContentPartCodec.decode("""[{"type":"tool_result","callId":"old","name":"read","output":"notes"}]""").single() as ContentPart.ToolResult
        assertNull(decoded.structuredContent)
        assertEquals("notes", decoded.output)
    }
}
