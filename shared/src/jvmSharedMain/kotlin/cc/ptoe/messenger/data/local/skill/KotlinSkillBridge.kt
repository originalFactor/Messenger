/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.skill

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.domain.model.RemoteSkillRequest
import cc.ptoe.messenger.domain.model.SkillPackage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Install only with the bridge and Room owner used by the other application services. */
internal class KotlinSkillBridge(
    private val delegate: CoreBridge,
    private val store: RoomSkillStore,
    private val remote: RemoteSkillImport? = RemoteSkillImport(),
) : CoreBridge by delegate {
    private val json = Json { encodeDefaults = true }

    @Serializable
    private data class Write(
        val files: Map<String, String>,
        val enabled: Boolean = true,
        val binaryFiles: Map<String, String> = emptyMap(),
        val name: String? = null,
    ) {
        fun stored() = StoredSkill(files, binaryFiles, enabled)
    }

    @Serializable
    private data class Catalog(val items: List<SkillPackage>)

    private fun SkillSummary.toPackage(files: Map<String, String> = emptyMap(), binary: Map<String, String> = emptyMap()) =
        SkillPackage(metadata.name, metadata.description, enabled, files, binary, metadata.license,
            metadata.compatibility, metadata.metadata, metadata.allowedTools)

    private fun SkillDetail.toPackage() = summary.toPackage(packageData.files, packageData.binaryFiles)

    override suspend fun listSkills(): String = json.encodeToString(Catalog(store.catalog().map { it.toPackage() }))
    override suspend fun getSkill(name: String): String = json.encodeToString(store.get(name).toPackage())

    override suspend fun validateSkill(filesJson: String): String {
        val write = json.decodeFromString<Write>(filesJson)
        val packageData = write.stored()
        val name = write.name ?: parseSkillFrontmatter(packageData.files["SKILL.md"] ?: error("SKILL.md is required")).name
        val metadata = validateSkill(name, packageData)
        return json.encodeToString(SkillSummary(metadata, packageData.enabled).toPackage(packageData.files, packageData.binaryFiles))
    }

    override suspend fun saveSkill(name: String, packageJson: String) {
        val write = json.decodeFromString<Write>(packageJson)
        require(write.name == null || write.name == name) { "Skill name does not match" }
        store.put(name, write.stored())
    }

    override suspend fun deleteSkill(name: String) { store.delete(name) }
    override suspend fun discoverRemoteSkills(requestJson: String): String =
        json.encodeToString(checkNotNull(remote) { "Remote Skill import is unavailable" }.discover(json.decodeFromString<RemoteSkillRequest>(requestJson)))
    override suspend fun previewRemoteSkill(requestJson: String): String =
        json.encodeToString(checkNotNull(remote) { "Remote Skill import is unavailable" }.preview(json.decodeFromString<RemoteSkillRequest>(requestJson)).detail.toPackage())
}
