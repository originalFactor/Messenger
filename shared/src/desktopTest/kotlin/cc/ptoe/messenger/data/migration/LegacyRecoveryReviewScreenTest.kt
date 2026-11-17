/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class LegacyRecoveryReviewScreenTest {
    @Test fun explicitSelectionAndConfirmationPreserveProjectDependency() = runComposeUiTest {
        val project = LegacyRecoveryProject("Older project", "/workspace", 1, 2, null, false)
        val oldChat = LegacyRecoveryConversation("Chat", "p")
        val newChat = LegacyRecoveryConversation("Chat", null)
        val review = LegacyRecoveryReview(LegacyRoomRecoveryPreview(22,
            listOf(LegacyRecoveryPair("p", project, null)), listOf(LegacyRecoveryPair("c", oldChat, newChat)), emptyList()),
            "this-device", mapOf("p" to null), mapOf("c" to newChat), emptyMap())
        var submitted: LegacyRecoverySelection? = null
        setContent { MaterialTheme { LegacyRecoveryReviewScreen(review, {}, { submitted = it }) } }
        onNodeWithText("Keep current values").assertIsEnabled()
        onNodeWithTag("legacy-recovery-list").performScrollToNode(hasContentDescription("c"))
        onNodeWithContentDescription("c").assertIsNotEnabled()
        onNodeWithTag("legacy-recovery-list").performScrollToNode(hasContentDescription("p"))
        onNodeWithContentDescription("p").performClick()
        onNodeWithTag("legacy-recovery-list").performScrollToNode(hasContentDescription("c"))
        onNodeWithContentDescription("c").performClick()
        onNodeWithText("Restore selected changes").performClick()
        assertNull(submitted)
        onNodeWithText("New projects will be owned by this device: this-device").assertExists()
        onNodeWithText("OK").performClick()
        assertEquals(LegacyRecoverySelection(setOf("p"), setOf("c"), newProjectDeviceId = "this-device"), submitted)
    }

    @Test fun keepingAllCurrentValuesRequiresASeparateConfirmation() = runComposeUiTest {
        val review = LegacyRecoveryReview(LegacyRoomRecoveryPreview(22, emptyList(), emptyList(), emptyList()),
            "device", emptyMap(), emptyMap(), emptyMap())
        var submitted: LegacyRecoverySelection? = null
        setContent { MaterialTheme { LegacyRecoveryReviewScreen(review, {}, { submitted = it }) } }
        onNodeWithText("Keep current values").performClick()
        onNodeWithText("Keep the current values without restoring any of these older fields? The original databases will be kept.").assertExists()
        assertNull(submitted)
        onNodeWithText("OK").performClick()
        assertEquals(LegacyRecoverySelection(), submitted)
    }
}
