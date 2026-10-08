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

class AndroidCoreBridge(
    private val core: CoreHandleInterface
) : CoreBridge {
    override fun subscribe(listener: (kind: String, ids: List<String>) -> Unit) {
        core.subscribe(object : StoreChangeListener {
            override fun onChange(event: StoreChangeEvent) {
                listener(event.kind, event.ids)
            }
        })
    }

    override fun listProvidersJson(): String = core.listProvidersJson()
    override fun getProviderJson(id: String): String? = core.getProviderJson(id)
    override fun upsertProviderJson(json: String) = core.upsertProviderJson(json)
    override fun deleteProvider(id: String) = core.deleteProvider(id)

    override fun listModelsJson(): String = core.listModelsJson()
    override fun listModelsByProviderJson(providerId: String): String = core.listModelsByProviderJson(providerId)
    override fun getModelJson(id: String): String? = core.getModelJson(id)
    override fun upsertModelJson(json: String) = core.upsertModelJson(json)
    override fun setModelEnabled(id: String, enabled: Boolean) = core.setModelEnabled(id, enabled)
    override fun deleteModel(id: String) = core.deleteModel(id)

    override fun listAgentsJson(): String = core.listAgentsJson()
    override fun getAgentJson(id: String): String? = core.getAgentJson(id)
    override fun getDefaultAgentJson(): String? = core.getDefaultAgentJson()
    override fun getTitleAgentJson(): String? = core.getTitleAgentJson()
    override fun upsertAgentJson(json: String) = core.upsertAgentJson(json)
    override fun deleteAgent(id: String) = core.deleteAgent(id)

    override fun listConversationsJson(): String = core.listConversationsJson()
    override fun listConversationsByAgentJson(agentId: String): String = core.listConversationsByAgentJson(agentId)
    override fun getConversationJson(id: String): String? = core.getConversationJson(id)
    override fun upsertConversationJson(json: String) = core.upsertConversationJson(json)
    override fun updateConversationLastMessage(id: String, lastMessage: String?, updatedAt: Long) =
        core.updateConversationLastMessage(id, lastMessage, updatedAt)
    override fun deleteConversation(id: String) = core.deleteConversation(id)

    override fun listMessagesByConversationJson(conversationId: String): String =
        core.listMessagesByConversationJson(conversationId)
    override fun getMessageJson(id: String): String? = core.getMessageJson(id)
    override fun upsertMessageJson(json: String) = core.upsertMessageJson(json)
    override fun deleteMessage(id: String) = core.deleteMessage(id)
    override fun deleteMessagesByConversation(conversationId: String) =
        core.deleteMessagesByConversation(conversationId)

    override fun kvGet(key: String): String? = core.kvGet(key)
    override fun kvSet(key: String, value: String) = core.kvSet(key, value)
    override fun kvDelete(key: String) = core.kvDelete(key)

    override suspend fun cloudLogin(email: String, password: String): String = core.cloudLogin(email, password)
    override suspend fun cloudRegister(email: String, password: String): String = core.cloudRegister(email, password)
    override suspend fun cloudLogout() = core.cloudLogout()
    override suspend fun cloudRefreshUser(): String = core.cloudRefreshUser()
    override fun cloudCurrentUserJson(): String? = core.cloudCurrentUserJson()
    override fun cloudSetServerUrl(url: String) = core.cloudSetServerUrl(url)
    override fun cloudGetServerUrl(): String = core.cloudGetServerUrl()
    override fun cloudHasLocalData(): Boolean = core.cloudHasLocalData()
    override fun cloudMarkChange(kind: String, id: String, deleted: Boolean) =
        core.cloudMarkChange(kind, id, deleted)
    override suspend fun cloudSync(replaceLocal: Boolean): String = core.cloudSync(replaceLocal)
    override suspend fun cloudPushPending(): String = core.cloudPushPending()
    override suspend fun cloudPreviewCard(code: String): String = core.cloudPreviewCard(code)
    override suspend fun cloudRedeemCard(code: String): String = core.cloudRedeemCard(code)
    override suspend fun cloudSyncBuiltinModels(force: Boolean): Long = core.cloudSyncBuiltinModels(force).toLong()
    override suspend fun cloudListMarketAgents(query: String, cursor: String?): String =
        core.cloudListMarketAgents(query, cursor)
    override suspend fun cloudGetMarketAgent(id: String): String = core.cloudGetMarketAgent(id)
    override suspend fun cloudPublishMarketAgent(agentId: String): String = core.cloudPublishMarketAgent(agentId)
    override suspend fun cloudImportMarketAgent(marketId: String): String = core.cloudImportMarketAgent(marketId)
    override suspend fun cloudCacheAvatar(
        scope: String,
        accountId: String,
        id: String,
        url: String,
        version: String?,
        destDir: String
    ): String = core.cloudCacheAvatar(scope, accountId, id, url, version, destDir)

    override suspend fun cloudPushSnapshot(): Long = core.cloudPushSnapshot().toLong()
    override suspend fun cloudSyncWith(
        since: Long?,
        replaceLocal: Boolean,
        expectedServerVersion: Long?
    ): String = core.cloudSyncWith(since, replaceLocal, expectedServerVersion)
    override suspend fun cloudReplaceCloudWithLocal(): String = core.cloudReplaceCloudWithLocal()
    override suspend fun cloudChangePassword(currentPassword: String, newPassword: String) =
        core.cloudChangePassword(currentPassword, newPassword)
    override suspend fun cloudDeleteAccount(currentPassword: String) =
        core.cloudDeleteAccount(currentPassword)
    override suspend fun cloudEnsureBuiltinTitleAgent() = core.cloudEnsureBuiltinTitleAgent()
    override suspend fun cloudUploadUserAvatar(bytes: ByteArray, filename: String, mime: String): String =
        core.cloudUploadUserAvatar(bytes, filename, mime)
    override suspend fun cloudDeleteUserAvatar(): String = core.cloudDeleteUserAvatar()
    override suspend fun cloudUploadAgentAvatar(
        agentId: String,
        bytes: ByteArray,
        filename: String,
        mime: String
    ): String = core.cloudUploadAgentAvatar(agentId, bytes, filename, mime)
    override suspend fun cloudDeleteAgentAvatar(agentId: String): String =
        core.cloudDeleteAgentAvatar(agentId)
    override suspend fun cloudPushMarketAgentUpdate(agentId: String): String =
        core.cloudPushMarketAgentUpdate(agentId)
    override suspend fun cloudRemoveMarketAgent(agentId: String) = core.cloudRemoveMarketAgent(agentId)
    override suspend fun cloudCheckMarketAgentUpdate(agentId: String): String =
        core.cloudCheckMarketAgentUpdate(agentId)
    override suspend fun cloudApplyMarketAgentUpdate(agentId: String, marketJson: String): String =
        core.cloudApplyMarketAgentUpdate(agentId, marketJson)
    override suspend fun cloudImportMarketAgentWithAvatar(marketId: String): String =
        core.cloudImportMarketAgentWithAvatar(marketId)
    override suspend fun cloudClearMarketLinksAndBuiltinProvider() =
        core.cloudClearMarketLinksAndBuiltinProvider()
    override fun clearLocalDataForReinit() = core.clearLocalDataForReinit()

    override fun cancelTurn() = core.cancelTurn()

    override suspend fun runTurn(
        config: TurnConfigBridge,
        toolExecutor: (name: String, argumentsJson: String) -> Pair<String, Boolean>,
        onEventJson: (String) -> Unit
    ) {
        val ffiConfig = TurnConfig(
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
        val toolHost = object : PlatformToolHost {
            override fun execute(name: String, argumentsJson: String): ToolResultFfi {
                val (output, isError) = toolExecutor(name, argumentsJson)
                return ToolResultFfi(output = output, isError = isError)
            }
        }
        val sink = object : TurnEventSink {
            override fun onEvent(eventJson: String) {
                onEventJson(eventJson)
            }
        }
        core.runTurn(ffiConfig, toolHost, sink)
    }

    override fun createDocumentSession(): DocumentSessionBridge {
        val handle = DocumentHandle()
        return object : DocumentSessionBridge {
            override fun feed(text: String): String = handle.feed(text)
            override fun finish(): String = handle.finish()
            override fun getDocumentJson(): String = handle.getDocumentJson()
        }
    }

    override fun parseMarkdownToBlocksJson(markdown: String): String =
        cc.ptoe.messenger.core.parseMarkdownToBlocksJson(markdown)
}
