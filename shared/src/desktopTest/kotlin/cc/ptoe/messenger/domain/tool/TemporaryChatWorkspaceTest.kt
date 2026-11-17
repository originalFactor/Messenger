package cc.ptoe.messenger.domain.tool

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryChatWorkspaceTest {
    @Test
    fun conversationWorkspaceSurvivesLaterTurnsAndIsIsolated() = runBlocking {
        val id = UUID.randomUUID().toString()
        val first = File(requireNotNull(temporaryChatWorkspace(id)))
        val other = File(requireNotNull(temporaryChatWorkspace("$id/other")))
        try {
            File(first, "note.txt").writeText("retained")
            val again = File(requireNotNull(temporaryChatWorkspace(id)))
            assertEquals(first, again)
            assertEquals("retained", File(again, "note.txt").readText())
            assertNotEquals(first, other)
            assertTrue(other.isDirectory)
        } finally {
            first.deleteRecursively()
            other.deleteRecursively()
        }
    }
}
