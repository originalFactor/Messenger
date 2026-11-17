/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningSource
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LearningBundleValidationTest {
    private val json = Json { encodeDefaults = true }
    private val data = "bytes".toByteArray()
    private val id = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val asset = PortableAsset(CoreAssetMetadata(id, id, "text/plain", data.size.toLong(), createdAt = 1, references = emptyList()), Base64.getEncoder().encodeToString(data))
    private val record = LearningRecord("note", version = 1, title = "Note", source = LearningSource(snapshot = "source", assetIds = listOf(id)), content = buildJsonObject { put("kind", "item"); put("itemKind", "note") })
    private val bundle = PortableLearningBundle(1, PortableLearning(1, listOf(record), listOf(record), emptyList(), emptyList(), false), listOf(asset), emptyList())
    private fun validate(value: PortableLearningBundle) = LearningBundleValidation.validate(json.encodeToString(value))

    @Test fun validatesCompleteBundleAndRejectsMissingOrTamperedResources() {
        val result = validate(bundle)
        assertContentEquals(data, result.bytes[id])
        assertEquals(record, result.bundle.learning.records.single())
        assertFailsWith<IllegalArgumentException> { validate(bundle.copy(assets = emptyList())) }
        assertFailsWith<IllegalArgumentException> { validate(bundle.copy(assets = listOf(asset.copy(dataBase64 = Base64.getEncoder().encodeToString("wrong".toByteArray()))))) }
        assertFailsWith<IllegalArgumentException> { validate(bundle.copy(learning = bundle.learning.copy(revisions = emptyList()))) }
        assertFailsWith<IllegalArgumentException> { validate(bundle.copy(assets = listOf(asset, asset))) }
    }

    @Test fun rejectsForeignNetworkGrantsAndUnknownProvenance() {
        val artifact = json.parseToJsonElement("""{"id":"00000000-0000-0000-0000-000000000001","title":"App","revisions":[{"revision":1,"files":{"index.html":"<p>Hi</p>"},"entry":"index.html","createdAt":1}],"approvedDomains":["example.com"],"createdAt":1,"updatedAt":1}""").jsonObject
        assertFailsWith<IllegalArgumentException> { validate(bundle.copy(artifacts = listOf(artifact))) }
        val bad = json.parseToJsonElement("""{"prompt":"p","modelId":"m","parameters":{},"revisedPrompt":null,"createdAt":1,"completedAt":2,"usage":{"secret":"do not export"}}""").jsonObject
        assertFailsWith<IllegalArgumentException> { validate(bundle.copy(generationProvenance = mapOf(id to listOf(bad)))) }
    }
}
