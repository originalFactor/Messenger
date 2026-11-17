package cc.ptoe.messenger.presentation.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.MessageStatus
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AcpChatMessagesTest {
    @Test
    fun remoteOmissionIsVisibleForBothRolesAndLegacyTruncation() {
        val rows = Json.parseToJsonElement("""[
            {"id":"user","role":"user","text":"","detailsOmitted":true},
            {"id":"assistant","role":"assistant","text":"Answer","detailsOmitted":true},
            {"id":"legacy","role":"assistant","text":"Old","eventsTruncated":true},
            {"id":"plain","role":"assistant","text":"Plain"}
        ]""").jsonArray.map { it.jsonObject }
        val labels = AcpContentLabels("image", "audio", "attachment", "<omitted>")
        val messages = acpChatMessages("acp:session", rows, labels = labels)
        assertEquals(listOf("<omitted>", "Answer\n<omitted>\n", "Old\n<omitted>\n", "Plain"), messages.map { it.content })
    }

    @Test
    fun mediaIsVisibleAndKeepsItsPositionAroundToolEvents() {
        val rows = Json.parseToJsonElement("""[
            {"id":"user","role":"user","text":"Question","timestamp":1,"status":"sent","content":[
                {"type":"image","data":"aW1hZ2U=","mimeType":"image/png"},
                {"type":"audio","data":"YQ==","mimeType":"audio/wav"},
                {"type":"resource","resource":{"uri":"file:///note","text":"note"}}
            ]},
            {"id":"agent","role":"assistant","text":"BeforeAfter","timestamp":2,"status":"sent","content":[
                {"type":"text","text":"Before"}, {"type":"image","data":"aW1hZ2U=","mimeType":"image/png"},
                {"type":"audio","data":"YQ==","mimeType":"audio/wav"}, {"type":"resource_link","name":"file","uri":"file:///file"}
            ],"events":[
                {"type":"TextDelta","text":"Before"}, {"type":"TextDelta","text":"","contentIndex":1},
                {"type":"ToolUpdate","update":{"toolCallId":"tool","title":"Tool","status":"completed"}},
                {"type":"TextDelta","text":"After"}, {"type":"TextDelta","text":"","contentIndex":2},
                {"type":"TextDelta","text":"","contentIndex":3}
            ]},
            {"id":"old","role":"assistant","text":"Old","timestamp":3,"status":"sent",
                "content":[{"type":"audio","data":"YQ==","mimeType":"audio/wav"}],"events":[{"type":"TextDelta","text":"Old"}]}
        ]""").jsonArray.map { it.jsonObject }
        val labels = AcpContentLabels("<image>", "<audio>", "<attachment>")
        val messages = acpChatMessages("acp:session", rows, labels = labels)
        assertEquals("Question\n<audio>\n<attachment>", messages.first().content)
        assertEquals(1, messages.first().parts.filterIsInstance<ContentPart.Image>().size)
        val group = buildChatItems(messages).filterIsInstance<ChatListItem.ToolGroupItem>().single()
        assertEquals("Before\n<image>\n", group.rounds.single().content)
        assertEquals("After\n<audio>\n<attachment>\n", group.finalMessage?.content)
        assertEquals("Old\n<audio>\n", messages.last().content)
    }

    @Test
    fun textAndIncrementalToolOutputRetainTheirOrderAndStatus() {
        val row = Json.parseToJsonElement("""{
            "id":"reply","role":"assistant","text":"BeforeBetweenAfter","timestamp":123,"status":"sending","events":[
                {"type":"TextDelta","text":"Before"},
                {"type":"ToolUpdate","update":{"toolCallId":"one","title":"First","status":"completed"}},
                {"type":"TextDelta","text":"Between"},
                {"type":"ToolUpdate","update":{"toolCallId":"two","title":"Second","status":"in_progress","content":[{"type":"content","content":{"type":"text","text":"Partial output"}}]}},
                {"type":"TextDelta","text":"After"}
            ]
        }""").jsonObject
        val group = buildChatItems(acpChatMessages("acp:session", listOf(row)))
            .filterIsInstance<ChatListItem.ToolGroupItem>().single()
        assertEquals(listOf("Before", "Between"), group.rounds.map { it.content })
        assertEquals("After", group.finalMessage?.content)
        assertEquals(listOf(MessageStatus.SENT, MessageStatus.SENDING), group.toolMessages.map { it.status })
        assertEquals(listOf("", "Partial output"), group.toolMessages.map { it.content })
    }

    @Test
    fun toolCardsShowEmptyCompletionAndExpandableOutput() = runComposeUiTest {
        val rows = Json.parseToJsonElement("""[
            {"id":"reply","role":"assistant","text":"","timestamp":123,"status":"sent","events":[
                {"type":"ToolUpdate","update":{"toolCallId":"one","title":"Empty tool","status":"completed"}},
                {"type":"ToolUpdate","update":{"toolCallId":"two","title":"Output tool","status":"completed","content":[{"type":"content","content":{"type":"text","text":"Tool output"}}]}}
            ]}
        ]""").jsonArray.map { it.jsonObject }
        val items = buildChatItems(acpChatMessages("acp:session", rows))
        setContent { MaterialTheme {
            Box(Modifier.size(800.dp, 600.dp)) {
                ChatMessageList(items, false, null, null, null, null, null, false, "Copied",
                    {}, {}, {}, {}, {}, allowMessageMutation = false)
            }
        } }
        onAllNodesWithText("Done").assertCountEquals(2)
        onNodeWithText("Output tool").performClick()
        onNodeWithText("Tool output").assertExists()
    }

    @Test
    fun incrementalToolUpdatesKeepInputTitleAndThoughtInTheOrdinaryChatModel() {
        val row = Json.parseToJsonElement("""{
            "id":"reply","role":"assistant","text":"File inspected","timestamp":123,"status":"sent","events":[
                {"type":"ThoughtDelta","text":"Inspect it first"},
                {"type":"ToolUpdate","update":{"toolCallId":"read","title":"Read file","rawInput":{"path":"test.txt"},"status":"in_progress"}},
                {"type":"ToolUpdate","update":{"toolCallId":"read","title":null,"status":"completed","content":[{"type":"content","content":{"type":"text","text":"Hello file"}}]}}
            ]
        }""").jsonObject
        val messages = acpChatMessages("acp:session", listOf(row))
        val group = buildChatItems(messages).filterIsInstance<ChatListItem.ToolGroupItem>().single()
        val call = group.rounds.single().parts.filterIsInstance<ContentPart.ToolCall>().single()
        assertEquals("Read file", call.name)
        assertTrue(call.arguments.contains("test.txt"))
        assertTrue(group.rounds.single().content.contains("<think>Inspect it first</think>"))
        assertEquals("Hello file", group.toolMessages.single().content)
        assertEquals("File inspected", group.finalMessage?.content)
    }

    @Test
    fun acpRepliesRenderThroughTheNormalChatBubble() = runComposeUiTest {
        val rows = Json.parseToJsonElement("""[
            {"id":"user","role":"user","text":"Hello","timestamp":123,"status":"sent"},
            {"id":"agent","role":"assistant","text":"Same Messenger bubble","timestamp":124,"status":"sent"}
        ]""").jsonArray.map { it.jsonObject }
        val items = buildChatItems(acpChatMessages("acp:session", rows))
        setContent { MaterialTheme {
            Box(Modifier.size(800.dp, 600.dp)) {
                ChatMessageList(items, false, null, null, null, null, null, false, "Copied",
                    {}, {}, {}, {}, {}, allowMessageMutation = false)
            }
        } }
        onNodeWithTag(CHAT_BUBBLE_TEST_TAG, useUnmergedTree = true).assertExists()
        onNodeWithText("Same Messenger bubble", substring = true).assertExists()
        onNodeWithText("Hello", substring = true).assertExists()
    }
}
