package cc.ptoe.messenger.presentation.ui.acp

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.util.randomUuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class AcpProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val cwd: String? = null,
    val enabled: Boolean = true,
    val providerTakeover: Boolean = false,
)

internal class AcpCommandException(val code: String, message: String) : IllegalStateException(message)

internal suspend fun requestAcp(
    core: CoreBridge, action: String,
    fields: JsonObjectBuilder.() -> Unit = {},
): JsonObject {
    var result: JsonObject? = null
    core.controlCommand(randomUuid(), buildJsonObject { put("action", action); fields() }) { event -> result = event }
    return checkNotNull(result) { "ACP command ended without a result" }.also {
        if (it.text("type") == "Error") throw AcpCommandException(it.text("code"), it.errorDetail())
    }
}

internal fun JsonObject.text(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
internal fun JsonObject.objects(key: String): List<JsonObject> = get(key)?.jsonArray?.map { it.jsonObject }.orEmpty()
internal fun JsonObject.errorDetail(): String = listOf(text("message"), text("detail")).filter { it.isNotBlank() }.joinToString(" ")
