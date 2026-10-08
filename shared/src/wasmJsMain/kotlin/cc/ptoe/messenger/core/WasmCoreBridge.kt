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

@file:OptIn(ExperimentalWasmJsInterop::class)

package cc.ptoe.messenger.core

import cc.ptoe.messenger.data.remote.NetworkClient
import kotlinx.serialization.Serializable

/**
 * [CoreBridge] over the `messenger-wasm` module.
 *
 * Every method is a JSON-string passthrough, matching `AndroidCoreBridge`
 * one-for-one; the wasm boundary is `wasm-bindgen` instead of UniFFI only
 * because UniFFI has no wasm generator (gobley emits stubs for wasmJs).
 *
 * The two wasm memories are separate, so nothing but strings, numbers,
 * booleans, arrays and functions may cross. Async exports resolve a
 * `Promise<JsAny>`; `awaitJs` adapts it into the coroutine.
 */
class WasmCoreBridge(private val core: WasmCoreJs) : CoreBridge {

    override fun subscribe(listener: (kind: String, ids: List<String>) -> Unit) {
        // The wasm store has no push channel: Rust's `Store::subscribe` takes
        // a Rust closure, which cannot be handed a JS callback across the
        // boundary. Repository version flows are re-read on demand instead,
        // and `RustCloudFacade` refreshes its own state after each call.
    }

    // -- store CRUD --

    override fun listProvidersJson(): String = core.list_providers_json()
    override fun getProviderJson(id: String): String? = core.get_provider_json(id)
    override fun upsertProviderJson(json: String) = core.upsert_provider_json(json)
    override fun deleteProvider(id: String) = core.delete_provider(id)

    override fun listModelsJson(): String = core.list_models_json()
    override fun listModelsByProviderJson(providerId: String): String =
        core.list_models_by_provider_json(providerId)

    override fun getModelJson(id: String): String? = core.get_model_json(id)
    override fun upsertModelJson(json: String) = core.upsert_model_json(json)
    override fun setModelEnabled(id: String, enabled: Boolean) =
        core.set_model_enabled(id, enabled)

    override fun deleteModel(id: String) = core.delete_model(id)

    override fun listAgentsJson(): String = core.list_agents_json()
    override fun getAgentJson(id: String): String? = core.get_agent_json(id)
    override fun getDefaultAgentJson(): String? = core.get_default_agent_json()
    override fun getTitleAgentJson(): String? = core.get_title_agent_json()
    override fun upsertAgentJson(json: String) = core.upsert_agent_json(json)
    override fun deleteAgent(id: String) = core.delete_agent(id)

    override fun listConversationsJson(): String = core.list_conversations_json()
    override fun listConversationsByAgentJson(agentId: String): String =
        core.list_conversations_by_agent_json(agentId)

    override fun getConversationJson(id: String): String? = core.get_conversation_json(id)
    override fun upsertConversationJson(json: String) = core.upsert_conversation_json(json)
    override fun updateConversationLastMessage(id: String, lastMessage: String?, updatedAt: Long) =
        core.update_conversation_last_message(id, lastMessage, updatedAt.toDouble())

    override fun deleteConversation(id: String) = core.delete_conversation(id)

    override fun listMessagesByConversationJson(conversationId: String): String =
        core.list_messages_by_conversation_json(conversationId)

    override fun getMessageJson(id: String): String? = core.get_message_json(id)
    override fun upsertMessageJson(json: String) = core.upsert_message_json(json)
    override fun deleteMessage(id: String) = core.delete_message(id)
    override fun deleteMessagesByConversation(conversationId: String) =
        core.delete_messages_by_conversation(conversationId)

    override fun kvGet(key: String): String? = core.kv_get(key)
    override fun kvSet(key: String, value: String) = core.kv_set(key, value)
    override fun kvDelete(key: String) = core.kv_delete(key)

    override fun clearLocalDataForReinit() = core.clear_local_data_for_reinit()

    // -- cloud --

    override suspend fun cloudLogin(email: String, password: String): String =
        core.cloud_login(email, password).let { awaitJsJson(it) }

    override suspend fun cloudRegister(email: String, password: String): String =
        core.cloud_register(email, password).let { awaitJsJson(it) }

    override suspend fun cloudLogout() = core.cloud_logout().let { awaitJsUnit(it) }

    override suspend fun cloudRefreshUser(): String =
        core.cloud_refresh_user().let { awaitJsJson(it) }

    override fun cloudCurrentUserJson(): String? = core.cloud_current_user_json()
    override fun cloudSetServerUrl(url: String) = core.cloud_set_server_url(url)
    override fun cloudGetServerUrl(): String = core.cloud_get_server_url()
    override fun cloudHasLocalData(): Boolean = core.cloud_has_local_data()
    override fun cloudMarkChange(kind: String, id: String, deleted: Boolean) =
        core.cloud_mark_change(kind, id, deleted)

    override suspend fun cloudSync(replaceLocal: Boolean): String =
        core.cloud_sync(replaceLocal).let { awaitJsJson(it) }

    override suspend fun cloudSyncWith(
        since: Long?,
        replaceLocal: Boolean,
        expectedServerVersion: Long?
    ): String = core.cloud_sync_with(
        since?.toDouble(),
        replaceLocal,
        expectedServerVersion?.toDouble()
    ).let { awaitJsJson(it) }

    override suspend fun cloudPushPending(): String =
        core.cloud_push_pending().let { awaitJsJson(it) }

    override suspend fun cloudPushSnapshot(): Long =
        core.cloud_push_snapshot().let { awaitJsLong(it) }

    override suspend fun cloudReplaceCloudWithLocal(): String =
        core.cloud_replace_cloud_with_local().let { awaitJsJson(it) }

    override suspend fun cloudChangePassword(currentPassword: String, newPassword: String) =
        core.cloud_change_password(currentPassword, newPassword).let { awaitJsUnit(it) }

    override suspend fun cloudDeleteAccount(currentPassword: String) =
        core.cloud_delete_account(currentPassword).let { awaitJsUnit(it) }

    override suspend fun cloudEnsureBuiltinTitleAgent() =
        core.cloud_ensure_builtin_title_agent()

    override suspend fun cloudClearMarketLinksAndBuiltinProvider() =
        core.cloud_clear_market_links_and_builtin_provider().let { awaitJsUnit(it) }

    override suspend fun cloudPreviewCard(code: String): String =
        core.cloud_preview_card(code).let { awaitJsJson(it) }

    override suspend fun cloudRedeemCard(code: String): String =
        core.cloud_redeem_card(code).let { awaitJsJson(it) }

    override suspend fun cloudSyncBuiltinModels(force: Boolean): Long =
        core.cloud_sync_builtin_models(force).let { awaitJsLong(it) }

    override suspend fun cloudListMarketAgents(query: String, cursor: String?): String =
        core.cloud_list_market_agents(query, cursor).let { awaitJsJson(it) }

    override suspend fun cloudGetMarketAgent(id: String): String =
        core.cloud_get_market_agent(id).let { awaitJsJson(it) }

    override suspend fun cloudPublishMarketAgent(agentId: String): String =
        core.cloud_publish_market_agent(agentId).let { awaitJsJson(it) }

    override suspend fun cloudImportMarketAgent(marketId: String): String =
        core.cloud_import_market_agent(marketId).let { awaitJsJson(it) }

    override suspend fun cloudImportMarketAgentWithAvatar(marketId: String): String =
        core.cloud_import_market_agent_with_avatar(marketId).let { awaitJsJson(it) }

    override suspend fun cloudPushMarketAgentUpdate(agentId: String): String =
        core.cloud_push_market_agent_update(agentId).let { awaitJsJson(it) }

    override suspend fun cloudRemoveMarketAgent(agentId: String) =
        core.cloud_remove_market_agent(agentId).let { awaitJsUnit(it) }

    override suspend fun cloudCheckMarketAgentUpdate(agentId: String): String =
        core.cloud_check_market_agent_update(agentId).let { awaitJsJson(it) }

    override suspend fun cloudApplyMarketAgentUpdate(agentId: String, marketJson: String): String =
        core.cloud_apply_market_agent_update(agentId, marketJson).let { awaitJsJson(it) }

    override suspend fun cloudCacheAvatar(
        scope: String,
        accountId: String,
        id: String,
        url: String,
        version: String?,
        destDir: String
    ): String = core.cloud_cache_avatar(scope, accountId, id, url, version, destDir)
        .let { awaitJsJson(it) }

    override suspend fun cloudUploadUserAvatar(bytes: ByteArray, filename: String, mime: String): String =
        core.cloud_upload_user_avatar(bytes.toJsUint8(), filename, mime).let { awaitJsJson(it) }

    override suspend fun cloudDeleteUserAvatar(): String =
        core.cloud_delete_user_avatar().let { awaitJsJson(it) }

    override suspend fun cloudUploadAgentAvatar(
        agentId: String,
        bytes: ByteArray,
        filename: String,
        mime: String
    ): String = core.cloud_upload_agent_avatar(agentId, bytes.toJsUint8(), filename, mime)
        .let { awaitJsJson(it) }

    override suspend fun cloudDeleteAgentAvatar(agentId: String): String =
        core.cloud_delete_agent_avatar(agentId).let { awaitJsJson(it) }

    // -- turn loop --

    override fun cancelTurn() = core.cancel_turn()

    override suspend fun runTurn(
        config: TurnConfigBridge,
        toolExecutor: (name: String, argumentsJson: String) -> Pair<String, Boolean>,
        onEventJson: (String) -> Unit
    ) {
        val configJson = NetworkClient.json.encodeToString(TurnConfigJson.of(config))
        val promise = core.run_turn(
            configJson,
            { name, argumentsJson ->
                val (output, isError) = toolExecutor(name, argumentsJson)
                // Rust reads the result as a two-element JS array.
                jsArrayOf(output, isError)
            },
            onEventJson
        )
        promise.let { awaitJsUnit(it) }
    }

    override fun createDocumentSession(): DocumentSessionBridge {
        val document = MessengerWasm.new_wasm_document()
        return object : DocumentSessionBridge {
            override fun feed(text: String): String = document.feed(text)
            override fun finish(): String = document.finish()
            override fun getDocumentJson(): String = document.get_document_json()
        }
    }

    override fun parseMarkdownToBlocksJson(markdown: String): String =
        MessengerWasm.parse_markdown_to_blocks_json(markdown)
}

/**
 * Wire shape of `TurnConfigBridge` for the Rust turn entry point. The field
 * names must match `TurnConfig`'s `serde(rename)`s in messenger-wasm.
 */
@Serializable
internal data class TurnConfigJson(
    val conversationId: String,
    val modelId: String,
    val baseUrl: String,
    val apiKey: String,
    val systemPrompt: String,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Long? = null,
    val reasoningEffort: String? = null,
    val toolNames: List<String> = emptyList(),
    val writable: Boolean = false,
    val contextWindow: Long = 0,
    val summarizePrompt: String = "",
    val titleAgentId: String? = null,
    val titleAgentSystemPrompt: String? = null,
    val titleAgentModelId: String? = null,
) {
    companion object {
        fun of(config: TurnConfigBridge) = TurnConfigJson(
            conversationId = config.conversationId,
            modelId = config.modelId,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            systemPrompt = config.systemPrompt,
            temperature = config.temperature,
            topP = config.topP,
            maxTokens = config.maxTokens,
            reasoningEffort = config.reasoningEffort,
            toolNames = config.toolNames,
            writable = config.writable,
            contextWindow = config.contextWindow,
            summarizePrompt = config.summarizePrompt,
            titleAgentId = config.titleAgentId,
            titleAgentSystemPrompt = config.titleAgentSystemPrompt,
            titleAgentModelId = config.titleAgentModelId,
        )
    }
}

/** Builds the `[string, boolean]` array the Rust tool host expects. */
private fun jsArrayOf(output: String, isError: Boolean): JsAny = buildJsArray(output, isError)

@JsFun("(a, b) => [a, b]")
private external fun buildJsArray(a: String, b: Boolean): JsAny

/** Copies Kotlin bytes into a JS `Uint8Array` for the upload calls. */
@JsFun("(bytes) => Uint8Array.from(bytes)")
private external fun bytesToJs(bytes: JsAny): JsAny

@JsFun("(array, i) => array[i]")
private external fun jsNumberAt(array: JsAny, i: Int): Int

private fun ByteArray.toJsUint8(): JsAny = bytesToJs(jsIntArray(this))

@JsFun("(length) => new Array(length)")
private external fun newNumberArray(length: Int): JsAny

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setNumberAt(array: JsAny, index: Int, value: Int)

private fun jsIntArray(bytes: ByteArray): JsAny {
    val array = newNumberArray(bytes.size)
    for (index in bytes.indices) {
        setNumberAt(array, index, bytes[index].toInt() and 0xFF)
    }
    return array
}
