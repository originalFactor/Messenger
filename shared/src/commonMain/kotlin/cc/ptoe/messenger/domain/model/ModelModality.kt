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

package cc.ptoe.messenger.domain.model

/**
 * 模型输入/输出模态。空集合 = 未知/不限制（服务商未提供元数据）。
 */
enum class ModelModality(val code: String) {
    TEXT("text"),
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video");

    companion object {
        fun fromCode(code: String): ModelModality? = entries.firstOrNull { it.code == code }

        /** 以逗号分隔的 code 字符串解析为模态集合。空串/未知 code 会被跳过。 */
        fun fromCsv(csv: String?): Set<ModelModality> =
            csv.orEmpty().split(",")
                .map { it.trim() }
                .mapNotNull(::fromCode)
                .toSet()

        fun toCsv(modalities: Set<ModelModality>): String =
            modalities.sortedBy { it.name }.joinToString(",") { it.code }
    }
}