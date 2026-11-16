/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.ui.learning

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ArtifactRunConfirmationTest {
    @Test fun pendingConfirmationIsDismissedWhenSelectionOrContentChanges() = runComposeUiTest {
        val revision = mutableStateOf(1L)
        val content = mutableStateOf(Json.parseToJsonElement("""{"revision":1,"files":{"index.html":"old"}}""").jsonObject)
        val approved = mutableListOf<Triple<String, Long, String>>()
        setContent {
            ArtifactRunConfirmation("app", revision.value, content.value, true, 0, false) { id, number, shown ->
                approved.add(Triple(id, number, shown.toString()))
            }
        }
        onNodeWithText("Allow this version to run").performClick()
        onNodeWithText("Run this application?").assertExists()
        runOnIdle { content.value = Json.parseToJsonElement("""{"revision":1,"files":{"index.html":"replaced"}}""").jsonObject }
        onNodeWithText("Run this application?").assertDoesNotExist()
        onNodeWithText("Allow this version to run").performClick()
        onNodeWithText("Run this application?").assertExists()
        runOnIdle {
            revision.value = 2
            content.value = Json.parseToJsonElement("""{"revision":2,"files":{"index.html":"new"}}""").jsonObject
        }
        onNodeWithText("Run this application?").assertDoesNotExist()
        runOnIdle { assertTrue(approved.isEmpty()) }
        onNodeWithText("Allow this version to run").performClick()
        onAllNodesWithText("Allow this version to run")[1].performClick()
        runOnIdle { assertEquals(listOf(Triple("app", 2L, content.value.toString())), approved) }
    }
}
