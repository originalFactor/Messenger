/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.cloud.createRoomCloudHttpClient
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.remote.api.describeProviderError
import cc.ptoe.messenger.data.remote.api.sanitizeProviderError
import cc.ptoe.messenger.data.remote.format.ApiFormatRequests
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.domain.model.ApiFormat
import cc.ptoe.messenger.domain.model.DiscoveredModelDto
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString

/** Direct Kotlin equivalent of provider model discovery and the optional models.dev cache. */
internal class KotlinModelDiscovery(
    private val db: LocalDatabase,
    private val catalogUrl: String = "https://models.dev/models.json",
    private val now: () -> Long = ::currentTimeMillis,
    private val requestTimeoutMillis: Long = 30_000,
) : AutoCloseable {
    private val http = createRoomCloudHttpClient()
    private val cacheMutex = Mutex()
    init { require(requestTimeoutMillis > 0) }

    suspend fun discover(baseUrl: String, apiKey: String, format: ApiFormat): List<DiscoveredModelDto> {
        val response = try {
            withTimeoutOrNull(requestTimeoutMillis) {
                http.prepareGet(baseUrl.trimEnd('/') + "/models") {
                    if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                    ApiFormatRequests.authHeaders(format, apiKey).forEach { (name, value) -> header(name, value) }
                }.execute { response ->
                    if (!response.status.isSuccess()) error(describeProviderError(response, apiKey))
                    readBody(response.bodyAsChannel(), 8 * 1024 * 1024)
                }
            } ?: error("Model discovery timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw IllegalStateException(sanitizeProviderError(failure.message ?: "Model discovery failed", apiKey))
        }
        val entries = KotlinModelDiscoveryCodec.providerModels(response)
        val catalog = catalog()
        return if (catalog == null) entries else entries.map { KotlinModelDiscoveryCodec.enrich(it, catalog) }
    }

    suspend fun metadata(modelId: String): DiscoveredModelDto? {
        val catalog = catalog() ?: return null
        return KotlinModelDiscoveryCodec.lookup(modelId, catalog)?.let { record ->
            KotlinModelDiscoveryCodec.enrichRecord(DiscoveredModelDto(modelId = modelId, displayName = modelId), record)
        }
    }

    private suspend fun catalog(): JsonObject? = cacheMutex.withLock {
        val rows = db.rows()
        val cached = rows.getValue("models_dev_body")?.value
        val fetched = rows.getValue("models_dev_fetched_at")?.value?.toLongOrNull()
        val current = now()
        if (fetched != null && current >= fetched && current - fetched < 86_400_000L) {
            cached?.let(KotlinModelDiscoveryCodec::catalog)?.let { return@withLock it }
        }
        try {
            val body = withTimeoutOrNull(requestTimeoutMillis) {
                http.prepareGet(catalogUrl).execute { response ->
                    check(response.status.isSuccess()) { "models.dev returned HTTP ${response.status.value}" }
                    readBody(response.bodyAsChannel(), 16 * 1024 * 1024)
                }
            } ?: error("models.dev timed out")
            val parsed = KotlinModelDiscoveryCodec.catalog(body) ?: error("Invalid models.dev catalog")
            db.useWriterConnection { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    rows.upsertValues(listOf(KeyValueRow("models_dev_body", body), KeyValueRow("models_dev_fetched_at", current.toString())))
                }
            }
            parsed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            cached?.let(KotlinModelDiscoveryCodec::catalog)
        }
    }

    override fun close() = http.close()
}

/** Install only around a bridge whose remaining methods use the same Room owner. */
internal class KotlinModelDiscoveryBridge(
    private val delegate: CoreBridge,
    private val discovery: KotlinModelDiscovery,
    private val syncBuiltinModels: suspend (Boolean) -> Long,
) : CoreBridge by delegate {
    override suspend fun discoverProviderModelsJson(baseUrl: String, apiKey: String, apiFormat: String?): String =
        Json.encodeToString(discovery.discover(baseUrl, apiKey, ApiFormat.fromWireName(apiFormat).requestFormat))

    override suspend fun modelsDevMetadataJson(modelId: String): String? =
        discovery.metadata(modelId)?.let { Json.encodeToString(it) }

    override suspend fun cloudSyncBuiltinModels(force: Boolean): Long = syncBuiltinModels(force)
}

private suspend fun readBody(channel: io.ktor.utils.io.ByteReadChannel, maxBytes: Int): String {
    val bytes = channel.readRemaining(maxBytes.toLong() + 1).readByteArray()
    require(bytes.size <= maxBytes) { "Model catalog exceeds size limit" }
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

/** Provider extensions and both current and legacy models.dev schemas. */
internal object KotlinModelDiscoveryCodec {
    private fun JsonElement?.string() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.boolean() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonElement?.long() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun JsonElement?.double() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    fun providerModels(body: String): List<DiscoveredModelDto> {
        val root = parseObject(body) ?: error("Invalid provider model response")
        val entries = listOf("data", "models").flatMap { key -> (root[key] as? JsonArray).orEmpty() }
        return entries.mapNotNull { value ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val id = (obj["id"].string()?.takeIf(String::isNotEmpty) ?: obj["name"].string().orEmpty()).removePrefix("models/")
            if (id.isEmpty()) return@mapNotNull null
            val nested = obj["metadata"] as? JsonObject
            fun field(snake: String, camel: String): JsonElement? =
                listOf(obj[snake], obj[camel], nested?.get(camel), nested?.get(snake)).firstOrNull { it != null && it !is JsonNull }
            val modality = (field("input_modalities", "inputModalities") as? JsonArray)?.mapNotNull { it.string() }
            DiscoveredModelDto(
                modelId = id,
                displayName = field("display_name", "displayName").string()?.takeIf(String::isNotBlank) ?: id,
                contextWindow = field("context_window", "contextWindow").long()?.takeIf { it >= 0 } ?: 0,
                inputRate = obj["input_rate"].double(), outputRate = obj["output_rate"].double(),
                cacheReadRate = obj["cache_read_rate"].double(), cacheWriteRate = obj["cache_write_rate"].double(),
                inputModalities = modality?.joinToString(",") ?: "text",
                supportsThinking = field("reasoning", "reasoning").boolean() ?: false,
                supportsTemperature = field("supports_temperature", "supportsTemperature").boolean() ?: false,
            )
        }
    }

    fun catalog(body: String): JsonObject? = parseObject(body)?.takeIf { it.isNotEmpty() }

    private fun parseObject(body: String): JsonObject? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in body) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > 64) return null
                '}', ']' -> if (--depth < 0) return null
            }
        }
        if (quoted || depth != 0) return null
        return runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    }

    fun lookup(modelId: String, catalog: JsonObject): JsonObject? {
        val id = modelId.trim()
        if (id.isEmpty()) return null
        return (catalog[id] as? JsonObject) ?: catalog.keys.sorted().firstNotNullOfOrNull { key ->
            if (key.endsWith("/$id")) catalog[key] as? JsonObject else null
        }
    }

    fun enrich(model: DiscoveredModelDto, catalog: JsonObject): DiscoveredModelDto =
        lookup(model.modelId, catalog)?.let { enrichRecord(model, it) } ?: model

    fun enrichRecord(model: DiscoveredModelDto, record: JsonObject): DiscoveredModelDto {
        val limit = (record["limit"] as? JsonObject)?.get("context").long()?.takeIf { it > 0 }
            ?: record["contextWindow"].long()?.takeIf { it > 0 }
        val cost = (record["cost"] as? JsonObject)?.get("cache_read").double()
        val modalities = record["modalities"] as? JsonObject
        val input = modalities(modalities?.get("input")) + legacyModalities(record["input"]) +
            if (record["vision"].string() in setOf("supported", "combined")) setOf("image") else emptySet()
        val output = modalities(modalities?.get("output")) + legacyModalities(record["output"])
        return model.copy(
            contextWindow = if (model.contextWindow == 0L) limit ?: 0 else model.contextWindow,
            cacheReadRate = model.cacheReadRate ?: cost,
            inputModalities = input.sorted().joinToString(",").ifEmpty { model.inputModalities },
            outputModalities = output.sorted().joinToString(",").ifEmpty { model.outputModalities },
            supportsToolCalling = record["tool_call"].boolean() ?: record["functionCall"].boolean() ?: model.supportsToolCalling,
            supportsThinking = record["reasoning"].boolean() ?: model.supportsThinking,
            supportsJsonOutput = record["structured_output"].boolean() ?: model.supportsJsonOutput,
            supportsTemperature = record["temperature"].boolean() ?: model.supportsTemperature,
        )
    }

    private val supported = setOf("text", "image", "audio", "video")
    private fun modalities(value: JsonElement?): Set<String> =
        (value as? JsonArray).orEmpty().mapNotNull { it.string() }.filterTo(mutableSetOf()) { it in supported }
    private fun legacyModalities(value: JsonElement?): Set<String> = when (value) {
        is JsonObject -> (value.keys.filter { it in supported } + modalities(value["types"]) + listOfNotNull(value["type"].string()).filter { it in supported }).toSet()
        is JsonArray -> value.mapNotNull { it.string() ?: (it as? JsonObject)?.get("type").string() }.filterTo(mutableSetOf()) { it in supported }
        else -> emptySet()
    }
}
