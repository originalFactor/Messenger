package cc.ptoe.messenger.presentation.ui.acp

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AcpWorkspaceFilesTest {
    @Test
    fun searchesWorkspaceAndRejectsTraversal() = runBlocking {
        val workspace = Files.createTempDirectory("acp-completion").toFile()
        try {
            workspace.resolve("src").mkdirs()
            workspace.resolve("src/Hello.kt").writeText("")
            workspace.resolve("other.txt").writeText("")
            workspace.resolve(".git").mkdirs()
            workspace.resolve(".git/Hello").writeText("")
            assertEquals(listOf("src/Hello.kt"), searchAcpWorkspaceFiles(workspace.path, "Hello"))
            assertEquals(emptyList(), searchAcpWorkspaceFiles(workspace.path, "../"))
            assertEquals(emptyList(), searchAcpWorkspaceFiles(workspace.path, "*"))
            assertFalse(searchAcpWorkspaceFiles(workspace.path, "").any { it.startsWith(".git/") })
        } finally {
            workspace.deleteRecursively()
        }
    }
}
