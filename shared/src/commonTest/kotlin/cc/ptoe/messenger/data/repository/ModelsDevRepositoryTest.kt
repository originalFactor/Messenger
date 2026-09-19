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
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.ModelModality
import cc.ptoe.messenger.domain.model.applyModelsDev
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * models.dev models.json 解析测试。
 * 样例取自 https://models.dev/models.json 的真实记录（2026-09 拉取）。
 */
class ModelsDevRepositoryTest {

    private val sampleModelsJson = """
        {
          "deepseek/deepseek-chat": {
            "id": "deepseek/deepseek-chat",
            "name": "DeepSeek Chat",
            "family": "deepseek",
            "attachment": true,
            "reasoning": false,
            "tool_call": true,
            "temperature": true,
            "knowledge": "2025-09",
            "release_date": "2025-12-01",
            "last_updated": "2026-02-28",
            "modalities": { "input": ["text"], "output": ["text"] },
            "open_weights": true,
            "limit": { "context": 1000000, "output": 384000 },
            "weights": [],
            "benchmarks": []
          },
          "anthropic/claude-opus-4-8": {
            "id": "anthropic/claude-opus-4-8",
            "name": "Claude Opus 4.8",
            "family": "claude-opus",
            "attachment": true,
            "reasoning": true,
            "tool_call": true,
            "temperature": false,
            "release_date": "2026-05-28",
            "last_updated": "2026-05-28",
            "modalities": { "input": ["text", "image", "pdf"], "output": ["text"] },
            "open_weights": false,
            "limit": { "context": 1000000, "output": 128000 },
            "weights": [],
            "benchmarks": []
          },
          "deepseek/deepseek-v4-flash": {
            "id": "deepseek/deepseek-v4-flash",
            "name": "DeepSeek V4 Flash",
            "family": "deepseek-flash",
            "attachment": false,
            "reasoning": true,
            "tool_call": true,
            "structured_output": true,
            "temperature": true,
            "last_updated": "2026-04-24",
            "modalities": { "input": ["text"], "output": ["text"] },
            "open_weights": true,
            "limit": { "context": 1000000, "output": 384000 },
            "weights": [],
            "benchmarks": []
          }
        }
    """.trimIndent()

    private fun parseIndex(): Map<String, ModelsDevModelRecord> =
        NetworkClient.json.decodeFromString<Map<String, ModelsDevModelRecord>>(sampleModelsJson)

    @Test
    fun parsesNewSchemaFields() {
        val index = parseIndex()
        val md = index.findModelsDevRecord("deepseek-chat")?.toModelsDevModel()
        assertNotNull("按裸 ID 后缀应命中", md)
        assertEquals(1_000_000L, md?.contextWindow)
        assertEquals(setOf(ModelModality.TEXT), md?.inputModalities)
        assertEquals(setOf(ModelModality.TEXT), md?.outputModalities)
        assertEquals(true, md?.supportsToolCalling)
        assertEquals(false, md?.supportsThinking)
        assertEquals(null, md?.supportsJsonOutput)
        assertEquals(true, md?.supportsTemperature)
    }

    @Test
    fun parsesMultimodalAndTemperatureUnsupported() {
        val index = parseIndex()
        val md = index.findModelsDevRecord("claude-opus-4-8")?.toModelsDevModel()
        assertNotNull("按完整 ID 应命中", md)
        assertEquals(setOf(ModelModality.TEXT, ModelModality.IMAGE), md?.inputModalities)
        assertEquals(setOf(ModelModality.TEXT), md?.outputModalities)
        assertEquals(1_000_000L, md?.contextWindow)
        assertEquals(false, md?.supportsTemperature)
        assertEquals(true, md?.supportsThinking)
    }

    @Test
    fun parsesStructuredOutput() {
        val index = parseIndex()
        val md = index.findModelsDevRecord("deepseek/deepseek-v4-flash")?.toModelsDevModel()
        assertNotNull(md)
        assertEquals(true, md?.supportsJsonOutput)
    }

    @Test
    fun unknownModelReturnsNull() {
        val index = parseIndex()
        assertNull(index.findModelsDevRecord("unknown-model-xyz"))
        assertNull(index.findModelsDevRecord(""))
    }

    @Test
    fun applyModelsDevFillsDefaultsOnly() {
        val md = parseIndex().findModelsDevRecord("deepseek-chat")?.toModelsDevModel()!!
        val base = ChatModel(
            id = "1",
            providerId = "p",
            modelId = "deepseek-chat",
            displayName = "deepseek-chat",
            isEnabled = true,
            createdAt = 0
        )
        val enriched = base.applyModelsDev(md)
        // 默认值被 models.dev 填充
        assertEquals(1_000_000L, enriched.contextWindow)
        assertEquals(setOf(ModelModality.TEXT), enriched.inputModalities)
        // 已有值不被覆盖
        val withExisting = base.copy(contextWindow = 200_000, supportsTemperature = true)
            .applyModelsDev(md)
        assertEquals(200_000L, withExisting.contextWindow)
        assertEquals(true, withExisting.supportsTemperature)
    }
}