/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.data.remote.NetworkClient
import cc.ptoe.messenger.data.util.FileKit
import cc.ptoe.messenger.domain.model.ModelModality
import cc.ptoe.messenger.domain.model.ModelsDevModel
import cc.ptoe.messenger.domain.repository.ModelsDevRepository
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.Path

/**
 * models.dev (https://models.dev) 元数据查询实现。
 *
 * 拉取 provider-agnostic 的 `models.json` 全量目录并按模型 ID 索引，
 * 结果缓存到本地文件（24h TTL）。目录较大（约 MB 级），因此：
 * - 文件缓存未过期时优先读文件，不发网络请求；
 * - 网络失败时回退过期缓存；两者皆无则返回 null。
 * 所有失败路径均静默降级（返回 null），不影响主流程（模型同步/添加）。
 *
 * 匹配策略：先按完整 ID（如 "deepseek/deepseek-chat"）精确匹配，
 * 再按后缀匹配裸模型 ID（如 "deepseek-chat" → "deepseek/deepseek-chat"）。
 */
class ModelsDevRepositoryImpl(
    private val filesDir: Path
) : ModelsDevRepository {

    private val client = NetworkClient.clientFor(MODELS_DEV_BASE_URL, "")
    private val mutex = Mutex()

    private var cachedIndex: Map<String, ModelsDevModelRecord>? = null

    override suspend fun getMetadata(modelId: String): ModelsDevModel? {
        val index = loadIndex() ?: return null
        return index.findModelsDevRecord(modelId.trim())?.toModelsDevModel()
    }

    private suspend fun loadIndex(): Map<String, ModelsDevModelRecord>? = mutex.withLock {
        cachedIndex?.let { return it }
        val cachePath = filesDir.resolve(CACHE_FILE).toString()
        val cacheTsPath = filesDir.resolve(CACHE_TS_FILE).toString()

        // 文件缓存未过期 → 直接用
        val cacheAgeMs = if (FileKit.isUsableFile(cacheTsPath)) {
            System.currentTimeMillis() - (FileKit.readText(cacheTsPath).toLongOrNull() ?: 0L)
        } else Long.MAX_VALUE
        if (FileKit.isUsableFile(cachePath) && cacheAgeMs < TTL_MILLIS) {
            parseSafely(FileKit.readText(cachePath))?.let {
                cachedIndex = it
                return it
            }
        }

        // 拉取最新目录
        try {
            val text = client.get("${MODELS_DEV_BASE_URL}models.json").body<String>()
            val index = parseSafely(text)
            if (index != null) {
                FileKit.writeText(cachePath, text)
                FileKit.writeText(cacheTsPath, System.currentTimeMillis().toString())
                cachedIndex = index
                return index
            }
        } catch (_: Exception) {
            // 网络失败：回退过期缓存
        }

        if (FileKit.isUsableFile(cachePath)) {
            parseSafely(FileKit.readText(cachePath))?.let {
                cachedIndex = it
                return it
            }
        }
        null
    }

    private fun parseSafely(text: String): Map<String, ModelsDevModelRecord>? = runCatching {
        NetworkClient.json.decodeFromString<Map<String, ModelsDevModelRecord>>(text)
    }.getOrNull()

    companion object {
        private const val MODELS_DEV_BASE_URL = "https://models.dev/"
        private const val CACHE_FILE = "models_dev.json"
        private const val CACHE_TS_FILE = "models_dev.fetched"
        private const val TTL_MILLIS = 24 * 60 * 60 * 1000L
    }
}

/**
 * models.json 中单条 canonical 模型记录。
 *
 * 兼容 2026 版 schema（`tool_call`/`modalities`/`limit.context`/布尔型
 * `reasoning`/`temperature`）与旧版字段（`functionCall`/`vision`/
 * `contextWindow`/`input`/`output`）。未知键被忽略，形状不定的字段用
 * [JsonElement] 承接。
 */
@Serializable
internal data class ModelsDevModelRecord(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    /** 旧版：上下文窗口（tokens），0 = 缺失/未知。 */
    val contextWindow: Long = 0,
    /** 思考/推理。 */
    val reasoning: Boolean? = null,
    /** 新版：工具调用。 */
    @SerialName("tool_call")
    val toolCall: Boolean? = null,
    /** 旧版：工具调用。 */
    val functionCall: Boolean? = null,
    /** JSON 输出（Structured Output）。 */
    @SerialName("structured_output")
    val structuredOutput: Boolean? = null,
    /** temperature 支持。 */
    val temperature: Boolean? = null,
    /** 旧版：视觉输入能力（"supported"/"combined"/"unsupported"）。 */
    val vision: String? = null,
    /** 旧版：输入/输出模态（对象键为模态 code）。 */
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    /** 新版：{ "input": [...], "output": [...] } 模态数组。 */
    val modalities: JsonElement? = null,
    /** 新版：{ "context": N, "output": N }。 */
    val limit: JsonElement? = null,
)

/**
 * 按模型 ID 查找 canonical 记录：先精确匹配完整 ID
 * （如 "deepseek/deepseek-chat"），再按裸 ID 后缀匹配。
 */
internal fun Map<String, ModelsDevModelRecord>.findModelsDevRecord(modelId: String): ModelsDevModelRecord? {
    if (modelId.isBlank()) return null
    return this[modelId] ?: entries.firstOrNull { it.key.endsWith("/$modelId") }?.value
}

internal fun ModelsDevModelRecord.toModelsDevModel(): ModelsDevModel {
    val inputModalities = mutableSetOf(ModelModality.TEXT)
    val outputModalities = mutableSetOf(ModelModality.TEXT)

    // 新版 modalities: { "input": [...], "output": [...] }
    (modalities as? JsonObject)?.let { mod ->
        extractModalityCodes(mod["input"])?.let(inputModalities::addAll)
        extractModalityCodes(mod["output"])?.let(outputModalities::addAll)
    }
    // 旧版 input/output：对象键为模态 code / { "type": ... } 等形状
    collectLegacyModalities(input, inputModalities)
    collectLegacyModalities(output, outputModalities)
    // 旧版 vision 字段表示视觉输入
    if (vision == "supported" || vision == "combined") {
        inputModalities += ModelModality.IMAGE
    }

    // 上下文窗口：新版 limit.context，回退旧版 contextWindow
    val contextFromLimit = (limit as? JsonObject)?.get("context")?.jsonPrimitive?.longOrNull
    val contextWindowValue = contextFromLimit ?: contextWindow.takeIf { it > 0 }

    return ModelsDevModel(
        contextWindow = contextWindowValue?.takeIf { it > 0 },
        inputModalities = inputModalities.ifEmpty { setOf(ModelModality.TEXT) },
        outputModalities = outputModalities.ifEmpty { setOf(ModelModality.TEXT) },
        supportsToolCalling = toolCall ?: functionCall,
        supportsThinking = reasoning,
        supportsJsonOutput = structuredOutput,
        supportsTemperature = temperature,
    )
}

/**
 * 从模态数组（新版 `modalities.input/output`）提取模态 code。
 * 非数组形状返回 null。
 */
private fun extractModalityCodes(element: JsonElement?): Set<ModelModality>? {
    val array = element as? JsonArray ?: return null
    val result = mutableSetOf<ModelModality>()
    array.forEach { item ->
        item.jsonPrimitive.contentOrNull?.let { code ->
            ModelModality.fromCode(code)?.let(result::add)
        }
    }
    return result.ifEmpty { null }
}

/**
 * 兼容旧版 input/output 字段的多种形状：对象键为模态 code /
 * "types" 数组 / 单个 "type"，或元素为字符串 / { "type": ... } 的数组。
 */
private fun collectLegacyModalities(element: JsonElement?, into: MutableSet<ModelModality>) {
    when (element) {
        is JsonObject -> {
            element.keys.forEach { key ->
                ModelModality.fromCode(key)?.let(into::add)
            }
            (element["types"] as? JsonArray)?.forEach { item ->
                item.jsonPrimitive.contentOrNull?.let { code ->
                    ModelModality.fromCode(code)?.let(into::add)
                }
            }
            (element["type"] as? JsonPrimitive)?.contentOrNull?.let { code ->
                ModelModality.fromCode(code)?.let(into::add)
            }
        }
        is JsonArray -> element.forEach { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.let { code ->
                    ModelModality.fromCode(code)?.let(into::add)
                }
                is JsonObject -> (item["type"] as? JsonPrimitive)?.contentOrNull?.let { code ->
                    ModelModality.fromCode(code)?.let(into::add)
                }
                else -> Unit
            }
        }
        else -> Unit
    }
}