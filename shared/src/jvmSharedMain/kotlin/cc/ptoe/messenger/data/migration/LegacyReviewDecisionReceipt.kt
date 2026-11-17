/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/** Local audit evidence only; this is not a reconciliation, worker-stop or startup proof. */
internal const val LEGACY_REVIEW_DECISION_KEY = "__messenger_legacy_review_decision_v1"

@Serializable
internal data class LegacyReviewDecisionReceipt(
    val legacyIdentity: String,
    val coreIdentity: String,
    val roomPublicationId: String,
    val reviewDigest: String,
    val projects: List<String>,
    val associations: List<String>,
    val providerFormats: List<String>,
    val newProjectDeviceId: String?,
    val recordedAt: Long,
)

/** Length-prefixed values avoid ambiguous fingerprints and never materialize credentials. */
internal class LegacyDecisionDigest {
    private val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: Any?) {
        if (value == null) { digest.update(0.toByte()); return }
        digest.update(1.toByte())
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    fun finish(): String = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun legacyReviewDigest(review: LegacyRecoveryReview): String {
    val digest = LegacyDecisionDigest()
    fun project(value: LegacyRecoveryProject?) {
        digest.add(value != null)
        if (value != null) {
            digest.add(value.name); digest.add(value.workspace); digest.add(value.createdAt); digest.add(value.updatedAt)
            digest.add(value.deviceId); digest.add(value.deviceIdKnown)
        }
    }
    fun conversation(value: LegacyRecoveryConversation?) {
        digest.add(value != null)
        if (value != null) { digest.add(value.title); digest.add(value.projectId) }
    }
    fun provider(value: LegacyRecoveryProvider?) {
        digest.add(value != null)
        if (value != null) { digest.add(value.name); digest.add(value.apiFormat) }
    }
    digest.add("legacy-review-comparison-v1")
    digest.add(review.source.legacyVersion); digest.add(review.source.projectsAvailable); digest.add(review.roomDeviceId)
    digest.add(review.source.projects.size)
    for (pair in review.source.projects.sortedBy { it.id }) {
        digest.add(pair.id); project(pair.legacy); project(pair.core)
        digest.add(review.roomProjects.containsKey(pair.id)); project(review.roomProjects[pair.id])
    }
    digest.add(review.source.conversations.size)
    for (pair in review.source.conversations.sortedBy { it.id }) {
        digest.add(pair.id); conversation(pair.legacy); conversation(pair.core)
        digest.add(review.roomConversations.containsKey(pair.id)); conversation(review.roomConversations[pair.id])
    }
    digest.add(review.source.providers.size)
    for (pair in review.source.providers.sortedBy { it.id }) {
        digest.add(pair.id); provider(pair.legacy); provider(pair.core)
        digest.add(review.roomProviders.containsKey(pair.id)); provider(review.roomProviders[pair.id])
    }
    return digest.finish()
}
