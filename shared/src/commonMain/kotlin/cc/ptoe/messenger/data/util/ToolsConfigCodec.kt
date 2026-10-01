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

package cc.ptoe.messenger.data.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Agent 每工具开关（`Map<String, Boolean>`，键为工具函数名）的 JSON 编解码，
 * 供 Room `toolsConfig` 列与云同步文档共用。空 / 损坏的输入一律回退为空配置
 * （缺失键视为开启，即「默认全开」）。
 */
object ToolsConfigCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String): Map<String, Boolean> =
        runCatching { json.decodeFromString<Map<String, Boolean>>(raw) }.getOrDefault(emptyMap())

    fun encode(config: Map<String, Boolean>): String = json.encodeToString(config)
}
