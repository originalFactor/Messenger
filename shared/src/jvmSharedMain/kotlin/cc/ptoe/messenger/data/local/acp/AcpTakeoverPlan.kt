/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import java.net.URI
import kotlinx.serialization.json.*

/** Public model metadata only: upstream URLs and credentials never enter this planner. */
internal data class AcpTakeoverModel(val id: String, val displayName: String, val apiFormat: String, val enabled: Boolean)

/** The result is process-scoped and contains the relay capability, so never log or persist it. */
internal class AcpTakeoverEnvironment(environment: Map<String, String>) {
    val environment = environment.toMap()
    override fun toString() = "AcpTakeoverEnvironment(redacted)"
}

/** Builds startup environment only; it does not start a relay or enable takeover.
 * Supplied user environment is preserved and may itself contain credentials; only Provider records
 * are excluded from the planner input. Callers must handle the entire result as sensitive.
 */
internal object AcpTakeoverPlan {
    private val supportedFormats = setOf("auto", "chat", "responses", "messages", "interactions")
    internal fun supportsFormat(format: String): Boolean = format in supportedFormats
    /** Parse bounded output from an explicitly run OpenCode version probe. */
    fun parseOpenCodeMajor(output: String): Int {
        require(output.length <= 4096) { "OpenCode version output exceeds limit" }
        val pattern = Regex("""^v*(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")
        val found = output.split(Regex("\\s+")).firstNotNullOfOrNull { pattern.matchEntire(it) }
            ?: error("Could not determine OpenCode version")
        val major = found.groupValues[1].toIntOrNull() ?: error("Invalid OpenCode version")
        require(major == 1 || major == 2) { "Only OpenCode versions 1 and 2 are supported" }
        return major
    }

    fun configure(profileId: String, environment: Map<String, String>, models: List<AcpTakeoverModel>,
        preferredModelId: String?, relayBaseUrl: String, relayToken: String, openCodeMajor: Int? = null): AcpTakeoverEnvironment {
        val requestedFormat = when (profileId) {
            "codex" -> "responses"
            "claude-code" -> "messages"
            "opencode" -> "chat"
            else -> error("Provider takeover is only available for built-in ACP agents")
        }
        require(environment.all { (key, value) -> key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value }) { "Invalid ACP environment" }
        val uri = try { URI(relayBaseUrl) } catch (_: Exception) { error("Invalid ACP relay address") }
        require(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in 1..65535 && uri.rawPath == "/v1" &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Invalid ACP relay address" }
        require(relayToken.length in 32..512 && relayToken.matches(Regex("[A-Za-z0-9._~+/\\-]+=*"))) { "Invalid ACP relay capability" }
        if (profileId == "opencode") require(openCodeMajor == 1 || openCodeMajor == 2) { "Only OpenCode versions 1 and 2 are supported" }
        require(models.map { it.id }.distinct().size == models.size && models.all { it.id.isNotBlank() && it.id.length <= 512 &&
            it.id.none { ch -> ch.code < 32 || ch == '/' } && it.displayName.length <= 4096 }) { "Invalid ACP model catalog" }
        val available = models.filter { it.enabled && supportsFormat(it.apiFormat) }
        val selected = available.firstOrNull { it.id == preferredModelId }
            ?: available.firstOrNull { it.apiFormat == requestedFormat }
            ?: available.firstOrNull() ?: error("Provider takeover requires an enabled compatible model")
        val selectedId = "messenger/${selected.id}"
        val result = environment.toMutableMap()
        when (profileId) {
            "claude-code" -> {
                result["ANTHROPIC_BASE_URL"] = relayBaseUrl.removeSuffix("/v1")
                result["ANTHROPIC_AUTH_TOKEN"] = relayToken
                result["ANTHROPIC_API_KEY"] = ""
                listOf("ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL")
                    .forEach { result[it] = selectedId }
            }
            "codex" -> {
                val config = inline(environment, "CODEX_CONFIG").toMutableMap()
                val providers = objectField(config, "model_providers")
                providers["messenger"] = buildJsonObject {
                    put("name", "Messenger"); put("base_url", relayBaseUrl); put("wire_api", "responses"); put("env_key", "MESSENGER_RELAY_TOKEN")
                }
                config["model_providers"] = JsonObject(providers)
                config["model_provider"] = JsonPrimitive("messenger"); config["model"] = JsonPrimitive(selectedId)
                result["CODEX_CONFIG"] = JsonObject(config).toString()
                result["MODEL_PROVIDER"] = "messenger"; result["MESSENGER_RELAY_TOKEN"] = relayToken
            }
            "opencode" -> {
                val v2 = openCodeMajor == 2
                val config = inline(environment, "OPENCODE_CONFIG_CONTENT").toMutableMap()
                val key = if (v2) "providers" else "provider"
                val providers = objectField(config, key)
                val catalog = linkedMapOf<String, JsonElement>()
                for (model in available) catalog[model.id] = buildJsonObject {
                    put("name", model.displayName)
                    put(if (v2) "modelID" else "id", "messenger/${model.id}")
                    put(if (v2) "package" else "npm", packageFor(model.apiFormat, v2))
                }
                var modelReference = selectedId
                if (!v2) for ((format, pkg) in listOf("responses" to "@ai-sdk/openai", "messages" to "@ai-sdk/anthropic")) {
                    val entries = available.filter { it.apiFormat == format }
                    if (entries.isNotEmpty()) {
                        val split = linkedMapOf<String, JsonElement>()
                        entries.forEach { model ->
                            catalog.remove(model.id)
                            split[model.id] = buildJsonObject { put("name", model.displayName); put("id", "messenger/${model.id}") }
                        }
                        val id = "messenger-$format"
                        providers[id] = provider("Messenger $format", pkg, relayBaseUrl, relayToken, JsonObject(split), false)
                        if (selected.apiFormat == format) modelReference = "$id/${selected.id}"
                    }
                }
                providers["messenger"] = provider("Messenger", if (v2) "@opencode/ai/providers/openai-compatible" else "@ai-sdk/openai-compatible", relayBaseUrl, relayToken, JsonObject(catalog), v2)
                config[key] = JsonObject(providers); config["model"] = JsonPrimitive(modelReference)
                result["OPENCODE_CONFIG_CONTENT"] = JsonObject(config).toString()
            }
        }
        return AcpTakeoverEnvironment(result)
    }

    private fun inline(environment: Map<String, String>, key: String): JsonObject {
        val raw = environment[key]?.takeIf { it.isNotBlank() } ?: return JsonObject(emptyMap())
        require(raw.length <= 1024 * 1024) { "$key exceeds limit" }
        val value = try { Json.parseToJsonElement(raw) } catch (_: Exception) { error("Invalid $key") }
        return value as? JsonObject ?: error("$key must be a JSON object")
    }
    private fun objectField(config: Map<String, JsonElement>, key: String): MutableMap<String, JsonElement> = when (val value = config[key]) {
        null -> linkedMapOf()
        is JsonObject -> value.toMutableMap()
        else -> error("$key must be a JSON object")
    }
    private fun provider(name: String, pkg: String, base: String, token: String, models: JsonObject, v2: Boolean) = buildJsonObject {
        put("name", name); put(if (v2) "package" else "npm", pkg)
        put(if (v2) "settings" else "options", buildJsonObject { put("baseURL", base); put("apiKey", token) })
        put("models", models)
    }
    private fun packageFor(format: String, v2: Boolean): String = if (v2) when (format) {
        "responses" -> "@opencode/ai/providers/openai-compatible/responses"
        "messages" -> "@opencode/ai/providers/anthropic-compatible"
        "interactions" -> "@opencode/ai/providers/google/interactions"
        else -> "@opencode/ai/providers/openai-compatible"
    } else when (format) {
        "responses" -> "@ai-sdk/openai"
        "messages" -> "@ai-sdk/anthropic"
        else -> "@ai-sdk/openai-compatible"
    }
}
