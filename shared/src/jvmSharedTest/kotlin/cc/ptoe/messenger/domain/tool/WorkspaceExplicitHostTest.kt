/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.tool

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceExplicitHostTest {
    @Test fun validatesAndBoundsArgumentsBeforeDispatchingToTheSelectedHost() = runBlocking {
        val tool = WorkspaceTool(WorkspaceTool.READ, "/workspace")
        var calls = 0
        val result = tool.executeWith("""{"path":"a.txt","startLine":0,"maxLines":5000}""") { operation, root ->
            calls++
            assertEquals("/workspace", root)
            val read = assertIs<WorkspaceOperation.Read>(operation)
            assertEquals("a.txt", read.path)
            assertEquals(1, read.startLine)
            assertEquals(2000, read.maxLines)
            ToolExecutionResult("done")
        }
        assertEquals("done", result.output)
        val invalid = tool.executeWith("""{"path":123}""") { _, _ -> calls++; ToolExecutionResult("unreachable") }
        assertTrue(invalid.isError)
        assertEquals(1, calls)
    }
}
