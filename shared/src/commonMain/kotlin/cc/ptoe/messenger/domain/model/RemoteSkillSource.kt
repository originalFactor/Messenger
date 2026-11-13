package cc.ptoe.messenger.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RemoteSkillCandidate(
    val name: String,
    val description: String,
    val path: String,
)

@Serializable
data class RemoteSkillWarning(val path: String, val message: String)

/** Discovery pins a commit so preview reads exactly the revision the user chose. */
@Serializable
data class RemoteSkillCatalog(
    val source: String,
    @SerialName("ref") val revision: String,
    val items: List<RemoteSkillCandidate>,
    val warnings: List<RemoteSkillWarning> = emptyList(),
)

@Serializable
internal data class RemoteSkillRequest(
    val source: String,
    val path: String? = null,
    @SerialName("ref") val revision: String? = null,
)
