/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.asset.AssetReferenceAudit
import cc.ptoe.messenger.data.local.asset.CoreAssetMetadata
import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.asset.CoreAssetReader
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.retainOwner
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LearningContentWire
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.domain.model.LearningArtifactReference
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** All learning resource ownership mutations share the same gate and one Room transaction. */
internal class LearningOwnership(private val db: LocalDatabase, private val assets: CoreAssetService) {
    private val json = Json { encodeDefaults = true; classDiscriminator = "kind" }

    suspend fun save(input: LearningRecord, now: Long, authorize: suspend () -> Unit = {}): LearningRecord = assets.withExclusive {
        val content = json.decodeFromJsonElement<LearningContentWire>(input.content)
        val ids = (input.source?.assetIds.orEmpty() + listOfNotNull((content as? LearningContentWire.Material)?.assetId)).toSet()
        require(ids.size <= 256 && ids.all(CoreAssetReader::validAssetId)) { "Invalid learning asset references" }
        val references = ids.associateWith { id ->
            val raw = db.assetMetadata().metadata("local_asset:$id") ?: error("Referenced asset not found")
            val metadata = json.decodeFromString<CoreAssetMetadata>(raw)
            require(metadata.id == id && metadata.sha256 == id) { "Invalid asset metadata" }
            assets.verifyWithGateHeld(id, raw)
            raw
        }
        val artifactId = (content as? LearningContentWire.Material)?.artifactId
        if (artifactId != null) {
            java.util.UUID.fromString(artifactId)
            check(db.learningBundle().value("artifact:$artifactId") != null) { "Artifact not found" }
        }
        db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                authorize()
                val saved = db.learningReview().saveRecord(input, now, assetOwnershipManaged = true)
                for ((id, raw) in references) {
                    check(db.assetMetadata().metadata("local_asset:$id") == raw) { "Asset changed during learning save" }
                    val prior = json.decodeFromString<CoreAssetMetadata>(raw)
                    val next = prior.retainOwner(CoreAssetOwner("learning", input.id)).copy(unreferencedAt = null)
                    db.assetMetadata().writeMetadata(KeyValueRow("local_asset:$id", json.encodeToString(next)))
                }
                saved
            }
        }
    }

    suspend fun artifacts(): List<LearningArtifactReference> = assets.withExclusive {
        val snapshot = db.assetMetadata().referenceSnapshot()
        AssetReferenceAudit(snapshot)
        snapshot.artifacts.sortedBy { it.key }.map { row ->
            val artifact = json.parseToJsonElement(row.value).jsonObject
            LearningArtifactReference(artifact.getValue("id").jsonPrimitive.content, artifact.getValue("title").jsonPrimitive.content)
        }
    }

    suspend fun delete(record: LearningRecord, now: Long) = assets.withExclusive {
        db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                db.learningReview().removeRecord(record.id, record.version, assetOwnershipManaged = true)
                val snapshot = db.assetMetadata().referenceSnapshot()
                val audit = AssetReferenceAudit(snapshot)
                val owner = CoreAssetOwner("learning", record.id)
                for (row in db.assetMetadata().allMetadataEntries()) {
                    val asset = json.decodeFromString<CoreAssetMetadata>(row.value)
                    require(CoreAssetReader.validAssetId(asset.id) && row.key == "local_asset:${asset.id}" && asset.id == asset.sha256 &&
                        asset.references.all(CoreAssetReader::validOwner)) { "Invalid asset metadata" }
                    if (owner !in asset.references) continue
                    val stillBound = try { audit.requireUnbound(asset.id, owner); false } catch (_: IllegalStateException) { true }
                    if (stillBound) continue
                    val remaining = asset.references.filterNot { it == owner }
                    val next = asset.copy(references = remaining, unreferencedAt = if (remaining.isEmpty()) now else null)
                    db.assetMetadata().writeMetadata(KeyValueRow(row.key, json.encodeToString(next)))
                }
            }
        }
    }
}
