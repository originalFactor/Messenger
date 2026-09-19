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

package cc.ptoe.messenger.domain.repository

import cc.ptoe.messenger.domain.model.ModelsDevModel

/**
 * models.dev（开源 AI 模型元数据库）查询入口。
 * 按模型 ID 获取 canonical 元数据，用于新增/拉取模型时默认填充。
 */
interface ModelsDevRepository {

    /**
     * 按模型 ID 查询元数据；未收录或网络/解析失败返回 null。
     * 内部带文件缓存（24h TTL），调用方无需关心网络细节。
     */
    suspend fun getMetadata(modelId: String): ModelsDevModel?
}