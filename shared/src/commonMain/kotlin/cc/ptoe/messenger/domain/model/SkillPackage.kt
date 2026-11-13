package cc.ptoe.messenger.domain.model

import kotlinx.serialization.Serializable

/** Device-local Agent Skills package; resource paths are relative to the skill root. */
@Serializable
data class SkillPackage(
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    val files: Map<String, String> = emptyMap(),
    val binaryFiles: Map<String, String> = emptyMap(),
    val license: String? = null,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val allowedTools: String? = null,
)
