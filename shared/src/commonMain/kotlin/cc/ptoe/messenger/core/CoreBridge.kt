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

package cc.ptoe.messenger.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Common Kotlin bridge to the Rust Agent Core ([CoreHandle]).
 * Platform targets (Android, Desktop) supply their actual native handle.
 */
interface CoreBridge {
    fun subscribe(listener: (kind: String, ids: List<String>) -> Unit)

    // Store CRUD
    fun listProvidersJson(): String
    fun getProviderJson(id: String): String?
    fun upsertProviderJson(json: String)
    fun deleteProvider(id: String)

    fun listModelsJson(): String
    fun listModelsByProviderJson(providerId: String): String
    fun getModelJson(id: String): String?
    fun upsertModelJson(json: String)
    fun setModelEnabled(id: String, enabled: Boolean)
    fun deleteModel(id: String)

    fun listAgentsJson(): String
    fun getAgentJson(id: String): String?
    fun getDefaultAgentJson(): String?
    fun getTitleAgentJson(): String?
    fun upsertAgentJson(json: String)
    fun deleteAgent(id: String)

    fun listConversationsJson(): String
    fun listConversationsByAgentJson(agentId: String): String
    fun getConversationJson(id: String): String?
    fun upsertConversationJson(json: String)
    fun updateConversationLastMessage(id: String, lastMessage: String?, updatedAt: Long)
    fun deleteConversation(id: String)

    fun listMessagesByConversationJson(conversationId: String): String
    fun getMessageJson(id: String): String?
    fun upsertMessageJson(json: String)
    fun deleteMessage(id: String)
    fun deleteMessagesByConversation(conversationId: String)

    fun kvGet(key: String): String?
    fun kvSet(key: String, value: String)
    fun kvDelete(key: String)

    // Cloud & Sync
    suspend fun cloudLogin(email: String, password: String): String
    suspend fun cloudRegister(email: String, password: String): String
    suspend fun cloudLogout()
    suspend fun cloudRefreshUser(): String
    fun cloudCurrentUserJson(): String?
    fun cloudSetServerUrl(url: String)
    fun cloudGetServerUrl(): String
    fun cloudHasLocalData(): Boolean
    fun cloudMarkChange(kind: String, id: String, deleted: Boolean)
    suspend fun cloudSync(replaceLocal: Boolean): String
    suspend fun cloudPushPending(): String
    suspend fun cloudPreviewCard(code: String): String
    suspend fun cloudRedeemCard(code: String): String
    suspend fun cloudSyncBuiltinModels(force: Boolean): Long
    suspend fun cloudListMarketAgents(query: String, cursor: String?): String
    suspend fun cloudGetMarketAgent(id: String): String
    suspend fun cloudPublishMarketAgent(agentId: String): String
    suspend fun cloudImportMarketAgent(marketId: String): String
    suspend fun cloudCacheAvatar(scope: String, accountId: String, id: String, url: String, version: String?, destDir: String): String

    // Agent turn loop
    fun cancelTurn()
    suspend fun runTurn(
        config: TurnConfigBridge,
        toolExecutor: (name: String, argumentsJson: String) -> Pair<String, Boolean>,
        onEventJson: (String) -> Unit
    )
}

data class TurnConfigBridge(
    val conversationId: String,
    val modelId: String,
    val baseUrl: String,
    val apiKey: String,
    val systemPrompt: String,
    val temperature: Double?,
    val topP: Double?,
    val maxTokens: Long?,
    val reasoningEffort: String?,
    val toolNames: List<String>,
    val writable: Boolean,
    val contextWindow: Long,
    val summarizePrompt: String,
    val titleAgentId: String?,
    val titleAgentSystemPrompt: String?,
    val titleAgentModelId: String?
)

object CoreBridgeRegistry {
    @Volatile
    var bridge: CoreBridge? = null
}

// ---------------------------------------------------------------------------
// Rust Store JSON DTOs
// ---------------------------------------------------------------------------

@Serializable
data class StoredProviderDto(
    val id: String,
    val name: String,
    @SerialName("base_url") val baseUrl: String,
    @SerialName("api_key") val apiKey: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class StoredModelDto(
    val id: String,
    @SerialName("provider_id") val providerId: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_enabled") val isEnabled: Boolean,
    @SerialName("context_window") val contextWindow: Long = 0,
    @SerialName("input_rate") val inputRate: Double? = null,
    @SerialName("output_rate") val outputRate: Double? = null,
    @SerialName("input_modalities") val inputModalities: String = "text",
    @SerialName("output_modalities") val outputModalities: String = "text",
    @SerialName("supports_tool_calling") val supportsToolCalling: Boolean = false,
    @SerialName("supports_thinking") val supportsThinking: Boolean = false,
    @SerialName("supports_json_output") val supportsJsonOutput: Boolean = false,
    @SerialName("supports_temperature") val supportsTemperature: Boolean = false,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class StoredAgentDto(
    val id: String,
    val name: String,
    val avatar: String? = null,
    @SerialName("system_prompt") val systemPrompt: String,
    val description: String = "",
    @SerialName("default_model_id") val defaultModelId: String? = null,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("follow_default_system_prompt") val followDefaultSystemPrompt: Boolean = false,
    @SerialName("follow_default_model") val followDefaultModel: Boolean = false,
    @SerialName("follow_default_temperature") val followDefaultTemperature: Boolean = false,
    @SerialName("follow_default_top_p") val followDefaultTopP: Boolean = false,
    @SerialName("follow_default_max_tokens") val followDefaultMaxTokens: Boolean = false,
    @SerialName("follow_default_reasoning_effort") val followDefaultReasoningEffort: Boolean = false,
    @SerialName("market_agent_id") val marketAgentId: String? = null,
    @SerialName("market_agent_version") val marketAgentVersion: Long? = null,
    @SerialName("market_agent_role") val marketAgentRole: String? = null,
    val role: String = "chat",
    @SerialName("tools_enabled") val toolsEnabled: Boolean = false,
    @SerialName("tools_follow_default") val toolsFollowDefault: Boolean = false,
    @SerialName("tools_config") val toolsConfig: String = "",
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class StoredConversationDto(
    val id: String,
    val title: String,
    @SerialName("provider_id") val providerId: String,
    @SerialName("agent_id") val agentId: String,
    @SerialName("override_model_id") val overrideModelId: String? = null,
    @SerialName("override_temperature") val overrideTemperature: Float? = null,
    @SerialName("override_top_p") val overrideTopP: Float? = null,
    @SerialName("override_max_tokens") val overrideMaxTokens: Int? = null,
    @SerialName("override_reasoning_effort") val overrideReasoningEffort: String? = null,
    @SerialName("override_tools_enabled") val overrideToolsEnabled: Boolean? = null,
    @SerialName("override_tools_config") val overrideToolsConfig: String? = null,
    val writable: Boolean = false,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("last_message") val lastMessage: String? = null,
    @SerialName("reasoning_format") val reasoningFormat: String? = null,
    @SerialName("context_summary") val contextSummary: String? = null,
    @SerialName("context_summary_until") val contextSummaryUntil: Long = 0,
    @SerialName("context_tokens") val contextTokens: Long = 0,
    @SerialName("context_tokens_at") val contextTokensAt: Long = 0,
)

@Serializable
data class StoredMessageDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val role: String,
    val content: String,
    @SerialName("parts_json") val partsJson: String? = null,
    val timestamp: Long,
    val status: String,
    @SerialName("error_message") val errorMessage: String? = null,
)
