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

import kotlin.js.JsName

/**
 * wasm-bindgen's `--target web` output must be initialized before any class
 * or free function is usable: the default export compiles and instantiates
 * the module and resolves once the instance is live.
 *
 * Called with no argument on purpose — passing an explicit `null` reaches
 * `Object.getPrototypeOf(null)` inside the generated initializer.
 */
@JsModule("messenger-wasm-bindings")
@JsName("default")
external fun initMessengerWasm(): JsAny

/**
 * Declarations for the wasm-bindgen output produced by `:webApp`'s
 * `generateWasmBindings` task.
 *
 * Only scalars, `String`, arrays and functions cross this boundary — the two
 * wasm memories are separate, so every payload is a JSON string (the same
 * convention UniFFI uses, which is what keeps this bridge and
 * `AndroidCoreBridge` method-for-method parallel).
 *
 * These declarations mirror the generated `messenger_wasm.d.ts` verbatim:
 * wasm-bindgen exports the Rust type as a class with snake_case methods and
 * free functions as plain module exports.
 */
@JsModule("messenger-wasm-bindings")
external object MessengerWasm {
    /** `open_wasm_core` / `prepare_wasm_store` are the module's free functions. */
    fun open_wasm_core(storePath: String, nowMs: Double): WasmCoreJs
    fun prepare_wasm_store(name: String): JsAny

    /** Document-engine free functions. */
    fun new_wasm_document(): WasmDocumentJs
    fun parse_markdown_to_blocks_json(markdown: String): String
    fun highlight_code_json(code: String, language: String, dark: Boolean): String
}

/** The `WasmCore` class from the wasm-bindgen module. */
@JsModule("messenger-wasm-bindings")
external class WasmCoreJs {
    fun list_providers_json(): String
    fun get_provider_json(id: String): String?
    fun upsert_provider_json(json: String)
    fun delete_provider(id: String)

    fun list_models_json(): String
    fun list_models_by_provider_json(providerId: String): String
    fun get_model_json(id: String): String?
    fun upsert_model_json(json: String)
    fun set_model_enabled(id: String, enabled: Boolean)
    fun delete_model(id: String)

    fun list_agents_json(): String
    fun get_agent_json(id: String): String?
    fun get_default_agent_json(): String?
    fun get_title_agent_json(): String?
    fun upsert_agent_json(json: String)
    fun delete_agent(id: String)

    fun list_conversations_json(): String
    fun list_conversations_by_agent_json(agentId: String): String
    fun get_conversation_json(id: String): String?
    fun upsert_conversation_json(json: String)
    fun update_conversation_last_message(id: String, lastMessage: String?, updatedAt: Double)
    fun delete_conversation(id: String)

    fun list_messages_by_conversation_json(conversationId: String): String
    fun get_message_json(id: String): String?
    fun upsert_message_json(json: String)
    fun delete_message(id: String)
    fun delete_messages_by_conversation(conversationId: String)

    fun kv_get(key: String): String?
    fun kv_set(key: String, value: String)
    fun kv_delete(key: String)
    fun clear_local_data_for_reinit()

    fun cloud_login(email: String, password: String): JsAny
    fun cloud_register(email: String, password: String): JsAny
    fun cloud_logout(): JsAny
    fun cloud_refresh_user(): JsAny
    fun cloud_current_user_json(): String?
    fun cloud_set_server_url(url: String)
    fun cloud_get_server_url(): String
    fun cloud_has_local_data(): Boolean
    fun cloud_mark_change(kind: String, id: String, deleted: Boolean)
    fun cloud_sync(replaceLocal: Boolean): JsAny
    fun cloud_sync_with(
        since: Double?,
        replaceLocal: Boolean,
        expectedServerVersion: Double?
    ): JsAny

    fun cloud_push_pending(): JsAny
    fun cloud_push_snapshot(): JsAny
    fun cloud_replace_cloud_with_local(): JsAny
    fun cloud_change_password(currentPassword: String, newPassword: String): JsAny
    fun cloud_delete_account(currentPassword: String): JsAny
    fun cloud_ensure_builtin_title_agent()
    fun cloud_clear_market_links_and_builtin_provider(): JsAny
    fun cloud_preview_card(code: String): JsAny
    fun cloud_redeem_card(code: String): JsAny
    fun cloud_sync_builtin_models(force: Boolean): JsAny

    fun cloud_list_market_agents(query: String, cursor: String?): JsAny
    fun cloud_get_market_agent(id: String): JsAny
    fun cloud_publish_market_agent(agentId: String): JsAny
    fun cloud_import_market_agent(marketId: String): JsAny
    fun cloud_import_market_agent_with_avatar(marketId: String): JsAny
    fun cloud_push_market_agent_update(agentId: String): JsAny
    fun cloud_remove_market_agent(agentId: String): JsAny
    fun cloud_check_market_agent_update(agentId: String): JsAny
    fun cloud_apply_market_agent_update(agentId: String, marketJson: String): JsAny
    fun cloud_cache_avatar(
        scope: String,
        accountId: String,
        id: String,
        url: String,
        version: String?,
        destDir: String
    ): JsAny

    fun cloud_upload_user_avatar(bytes: JsAny, filename: String, mime: String): JsAny
    fun cloud_delete_user_avatar(): JsAny
    fun cloud_upload_agent_avatar(
        agentId: String,
        bytes: JsAny,
        filename: String,
        mime: String
    ): JsAny

    fun cloud_delete_agent_avatar(agentId: String): JsAny

    fun cancel_turn()
    fun run_turn(
        configJson: String,
        toolHost: (String, String) -> JsAny,
        eventSink: (String) -> Unit
    ): JsAny
}

/** The `WasmDocument` class (incremental streaming session). */
@JsModule("messenger-wasm-bindings")
external class WasmDocumentJs {
    fun feed(text: String): String
    fun finish(): String
    fun get_document_json(): String
}
