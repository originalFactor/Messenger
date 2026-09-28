/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package cc.ptoe.messenger.domain.tool

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceToolTest {
    @Test
    fun `workspace tools expose bounded schemas and confirmation policy`() {
        assertEquals(listOf("glob", "grep", "read", "edit", "create"), WorkspaceTool.all.map { it.name })
        assertFalse(WorkspaceTool(WorkspaceTool.GLOB).requiresUserConfirmation)
        assertFalse(WorkspaceTool(WorkspaceTool.READ).requiresUserConfirmation)
        assertTrue(WorkspaceTool(WorkspaceTool.EDIT).requiresUserConfirmation)
        assertTrue(WorkspaceTool(WorkspaceTool.CREATE).requiresUserConfirmation)
    }

    @Test
    fun `schemas are objects and invalid arguments are tool errors`() {
        WorkspaceTool.all.forEach { tool ->
            assertTrue(kotlinx.serialization.json.Json.parseToJsonElement(tool.parametersJson) is kotlinx.serialization.json.JsonObject)
        }
        val tool = WorkspaceTool(WorkspaceTool.READ)
        val result = runBlocking {
            tool.execute("{\"path\":123}")
        }
        assertTrue(result.isError)
        assertEquals("Invalid arguments for read.", result.output)
    }
}
