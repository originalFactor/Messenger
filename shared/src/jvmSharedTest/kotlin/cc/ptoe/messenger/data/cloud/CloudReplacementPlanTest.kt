/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CloudReplacementPlanTest {
    private fun doc(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private val remote = PulledCloudDelta(mapOf(
        SyncCollection.AGENT to listOf(doc("""{"_id":"cloud-default","isDefault":true}"""), doc("""{"_id":"old-agent"}"""), doc("""{"_id":"local-default"}"""), doc("""{"_id":"builtin-title-agent"}""")),
        SyncCollection.PROVIDER to listOf(doc("""{"_id":"builtin-messenger-cloud-ai"}""")),
        SyncCollection.PROJECT to listOf(doc("""{"_id":"old-project"}"""), doc("""{"_id":"local-project"}""")),
        SyncCollection.CONVERSATION to listOf(doc("""{"_id":"deleted","deleted":true}"""))
    ), 42)
    private val local = CloudLocalSnapshot(7, "local-default", mapOf(
        SyncCollection.AGENT to setOf("local-default"), SyncCollection.PROJECT to setOf("local-project")
    ))
    @Test fun determinesDeletionsAndPreservesCloudDefault() {
        val plan = planCloudReplacement(42, 42, remote, local)
        assertEquals("cloud-default", plan.cloudDefaultId)
        assertEquals(setOf("old-agent", "old-project", "local-default"), plan.deletions.map { it.id }.toSet())
        assertEquals(7, plan.localRevision)
    }
    @Test fun rejectsVersionDriftAndDefaultIdCollision() {
        assertFailsWith<IllegalArgumentException> { planCloudReplacement(42, 43, remote, local) }
        assertFailsWith<IllegalArgumentException> { planCloudReplacement(42, 42, remote.copy(latestVersion = 41), local) }
        assertFailsWith<IllegalArgumentException> {
            planCloudReplacement(42, 42, remote, local.copy(ids = mapOf(SyncCollection.AGENT to setOf("local-default", "cloud-default"))))
        }
    }
}
