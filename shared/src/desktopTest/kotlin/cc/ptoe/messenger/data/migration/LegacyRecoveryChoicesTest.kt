/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyRecoveryChoicesTest {
    private val oldProject = LegacyRecoveryProject("Project", "/old", 1, 2, null, false)
    private val oldConversation = LegacyRecoveryConversation("Chat", "p")
    private val coreConversation = LegacyRecoveryConversation("Chat", null)
    private val oldProvider = LegacyRecoveryProvider("Provider", "responses")
    private val coreProvider = LegacyRecoveryProvider("Provider", "chat")

    private fun review(roomProject: LegacyRecoveryProject? = null, coreProject: LegacyRecoveryProject? = null,
        device: String? = "device", roomConversation: LegacyRecoveryConversation? = coreConversation,
        roomProvider: LegacyRecoveryProvider? = coreProvider, oldFormat: String? = "responses") = LegacyRecoveryReview(
        LegacyRoomRecoveryPreview(22, true,
            listOf(LegacyRecoveryPair("p", oldProject, coreProject)),
            listOf(LegacyRecoveryPair("c", oldConversation, coreConversation)),
            listOf(LegacyRecoveryPair("provider", oldProvider.copy(apiFormat = oldFormat), coreProvider))),
        device, mapOf("p" to roomProject), mapOf("c" to roomConversation), mapOf("provider" to roomProvider))

    @Test fun restoringAMissingProjectRequiresExplicitChoiceBeforeItsAssociation() {
        val choices = LegacyRecoveryChoices(review())
        assertTrue(choices.canRestoreProject("p"))
        assertFalse(choices.canRestoreAssociation("c", emptySet()))
        assertNull(choices.selection(emptySet(), setOf("c"), emptySet()))
        assertEquals(LegacyRecoverySelection(setOf("p"), setOf("c"), setOf("provider"), "device"),
            choices.selection(setOf("p"), setOf("c"), setOf("provider")))
        assertNull(LegacyRecoveryChoices(review(device = null)).selection(setOf("p"), emptySet(), emptySet()))
    }

    @Test fun existingOwnedProjectCanBeAssociatedWithoutOverwritingIt() {
        val project = oldProject.copy(workspace = "/new", deviceId = "device", deviceIdKnown = true)
        val choices = LegacyRecoveryChoices(review(project, project))
        assertTrue(choices.canRestoreAssociation("c", emptySet()))
        assertEquals(LegacyRecoverySelection(associations = setOf("c")), choices.selection(emptySet(), setOf("c"), emptySet()))
    }

    @Test fun foreignOrChangedRowsAndUnsupportedProtocolsCannotBeSelected() {
        val remote = oldProject.copy(deviceId = "other", deviceIdKnown = true)
        val choices = LegacyRecoveryChoices(review(remote, remote, roomConversation = coreConversation.copy(title = "changed"),
            roomProvider = coreProvider.copy(name = "changed")))
        assertFalse(choices.canRestoreProject("p"))
        assertFalse(choices.canRestoreAssociation("c", emptySet()))
        assertFalse(choices.canRestoreProviderFormat("provider"))
        assertNull(choices.selection(setOf("p"), setOf("c"), setOf("provider")))
        assertFalse(LegacyRecoveryChoices(review(oldFormat = null)).canRestoreProviderFormat("provider"))
        assertFalse(LegacyRecoveryChoices(review(oldFormat = "unrecognized")).canRestoreProviderFormat("provider"))
        assertNull(choices.selection(emptySet(), emptySet(), emptySet()))
    }
}
