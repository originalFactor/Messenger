/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

/** Validation for a review UI. The apply transaction repeats every authorization and identity check. */
internal class LegacyRecoveryChoices(private val review: LegacyRecoveryReview) {
    private val projects = review.source.projects.associateBy { it.id }
    private val conversations = review.source.conversations.associateBy { it.id }
    private val providers = review.source.providers.associateBy { it.id }
    private val formats = setOf("chat", "responses", "messages", "interactions")

    fun canRestoreProject(id: String): Boolean {
        val pair = projects[id] ?: return false
        if (pair.legacy == null || !review.roomProjects.containsKey(id)) return false
        val current = review.roomProjects[id]
        if (current != pair.core) return false
        return if (current == null) !review.roomDeviceId.isNullOrBlank() else owned(current)
    }

    fun canRestoreAssociation(id: String, selectedProjects: Set<String>): Boolean {
        val pair = conversations[id] ?: return false
        val projectId = pair.legacy?.projectId ?: return false
        if (pair.core == null || !review.roomConversations.containsKey(id) || review.roomConversations[id] != pair.core) return false
        val project = projects[projectId] ?: return false
        if (project.legacy == null || !review.roomProjects.containsKey(projectId)) return false
        val current = review.roomProjects[projectId]
        return if (projectId in selectedProjects) canRestoreProject(projectId)
        else current != null && current == project.core && owned(current)
    }

    fun canRestoreProviderFormat(id: String): Boolean {
        val pair = providers[id] ?: return false
        val format = pair.legacy?.apiFormat ?: return false
        return format in formats && pair.core != null &&
            review.roomProviders.containsKey(id) && review.roomProviders[id] == pair.core
    }

    fun selection(projectIds: Set<String>, conversationIds: Set<String>, providerIds: Set<String>, allowEmpty: Boolean = false): LegacyRecoverySelection? {
        val count = projectIds.size.toLong() + conversationIds.size + providerIds.size
        if (count > 1_000 || count == 0L && !allowEmpty || projectIds.any { !canRestoreProject(it) } ||
            conversationIds.any { !canRestoreAssociation(it, projectIds) } ||
            providerIds.any { !canRestoreProviderFormat(it) }) return null
        val needsDevice = projectIds.any { review.roomProjects[it] == null }
        return LegacyRecoverySelection(projectIds.toSet(), conversationIds.toSet(), providerIds.toSet(),
            review.roomDeviceId.takeIf { needsDevice })
    }

    private fun owned(project: LegacyRecoveryProject): Boolean =
        project.deviceIdKnown && (project.deviceId == null ||
            !review.roomDeviceId.isNullOrBlank() && project.deviceId == review.roomDeviceId)
}
