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

package cc.ptoe.messenger.data.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CloudUser(
    val id: String = "",
    val email: String = "",
    // SaaS 字段：旧服务端响应不含这些键，全部带默认值以保持兼容。
    val role: String? = null,
    val aiApiKey: String? = null,
    val quotaBalance: Long? = null,
    val quotaExpiresAt: Long? = null,
    val avatarUrl: String? = null,
    val avatarVersion: Long? = null,
    val syncVersion: Long = 0,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val lastLoginAt: Long? = null
)

@Serializable
data class CloudSyncResult(
    val latestVersion: Long = 0,
    val agents: Int = 0,
    val conversations: Int = 0,
    val providers: Int = 0
)

@Serializable
data class CloudLoginOutcome(
    val user: CloudUser,
    val hasLocalData: Boolean = false,
    val cloudVersion: Long = 0
)

@Serializable
data class CloudAgentDocument(
    @SerialName("_id") val id: String = "",
    val name: String = "",
    val avatarUrl: String? = null,
    val avatarVersion: Long? = null,
    val systemPrompt: String = "",
    val defaultModelId: String? = null,
    val temperature: Double = 0.0,
    val topP: Double = 0.0,
    val maxTokens: Int? = null,
    val reasoningEffort: String? = null,
    val isDefault: Boolean = false,
    val followDefaultSystemPrompt: Boolean = false,
    val followDefaultModel: Boolean = false,
    val followDefaultTemperature: Boolean = false,
    val followDefaultTopP: Boolean = false,
    val followDefaultMaxTokens: Boolean = false,
    val followDefaultReasoningEffort: Boolean = false,
    val marketAgentId: String? = null,
    val marketAgentVersion: Long? = null,
    val marketAgentRole: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Long = 0,
    val deleted: Boolean = false
)

@Serializable
data class CloudMessageDocument(
    val id: String = "",
    val role: String = "",
    val content: String = "",
    /**
     * JSON-encoded [cc.ptoe.messenger.domain.model.ContentPart] list,
     * identical in shape to the local [cc.ptoe.messenger.data.local.entity.MessageEntity.partsJson]
     * column. Null for text-only messages and for documents from
     * pre-multimodal server builds.
     */
    val partsJson: String? = null,
    val timestamp: Long = 0,
    val status: String = "",
    val errorMessage: String? = null
)

@Serializable
data class CloudConversationDocument(
    @SerialName("_id") val id: String = "",
    val agentId: String = "",
    val title: String = "",
    val providerId: String = "",
    val overrideModelId: String? = null,
    val overrideTemperature: Double? = null,
    val overrideTopP: Double? = null,
    val overrideMaxTokens: Int? = null,
    val overrideReasoningEffort: String? = null,
    val reasoningFormat: String? = null,
    /** 80% 上下文自动摘要状态。旧服务端文档不含这些键。 */
    val contextSummary: String? = null,
    val contextSummaryUntil: Long = 0,
    val contextTokens: Long = 0,
    val contextTokensAt: Long = 0,
    val messages: List<CloudMessageDocument> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Long = 0,
    val deleted: Boolean = false
)

@Serializable
data class CloudModelDocument(
    val id: String = "",
    val modelId: String = "",
    val displayName: String = "",
    val isEnabled: Boolean = false,
    /** 上下文窗口（tokens），0 = 未知/不限。旧服务端文档不含该键。 */
    val contextWindow: Long = 0,
    val createdAt: Long = 0
)

@Serializable
data class CloudProviderDocument(
    @SerialName("_id") val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val models: List<CloudModelDocument> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Long = 0,
    val deleted: Boolean = false
)

@Serializable
data class CloudSyncResponse(
    val agents: List<CloudAgentDocument> = emptyList(),
    val conversations: List<CloudConversationDocument> = emptyList(),
    val providers: List<CloudProviderDocument> = emptyList(),
    val latestVersion: Long = 0
)

/**
 * /api/sync?collection=... 分页响应。documents 字段是异构数组,
 * 由三个具体类型 Page DTO(CloudSyncAgentsPage / CloudSyncConversationsPage /
 * CloudSyncProvidersPage)分别承接,Gson 按方法的泛型签名反序列化。
 */
@Serializable
data class CloudSyncAgentsPage(
    val collection: String = "agents",
    val documents: List<CloudAgentDocument> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val latestVersion: Long = 0
)

@Serializable
data class CloudSyncConversationsPage(
    val collection: String = "conversations",
    val documents: List<CloudConversationDocument> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val latestVersion: Long = 0
)

@Serializable
data class CloudSyncProvidersPage(
    val collection: String = "providers",
    val documents: List<CloudProviderDocument> = emptyList(),
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val latestVersion: Long = 0
)

@Serializable
data class CloudUpsertResponse(
    val id: String = "",
    val version: Long = 0
)

@Serializable
data class CloudAvatarResponse(
    val url: String?,
    val version: Long = 0,
    val avatarVersion: Long? = null
)

@Serializable
data class CloudMarketAgent(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String? = null,
    val avatarVersion: Long? = null,
    val systemPrompt: String = "",
    val temperature: Double = 0.0,
    val topP: Double = 0.0,
    val maxTokens: Int? = null,
    val reasoningEffort: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Long = 0
)

@Serializable
data class CloudMarketAgentListResponse(
    val agents: List<CloudMarketAgent> = emptyList(),
    val nextCursor: String? = null
)

@Serializable
data class CloudMarketAgentResponse(
    val agent: CloudMarketAgent,
    val isOwner: Boolean = false
)

@Serializable
data class CloudMarketAgentUpdate(
    val agent: CloudMarketAgent,
    val hasUpdate: Boolean = false
)
