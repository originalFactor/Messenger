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

package cc.ptoe.messenger.presentation.navigation

sealed class Screen(val route: String) {
    data object Conversations : Screen("conversations")
    data object Chat : Screen("chat/{conversationId}") {
        fun createRoute(conversationId: String) = "chat/$conversationId"
    }
    data object Agents : Screen("agents")
    data object AgentMarket : Screen("agent_market")
    data object AgentMarketDetail : Screen("agent_market_detail/{marketAgentId}") {
        fun createRoute(marketAgentId: String) = "agent_market_detail/$marketAgentId"
    }
    data object Providers : Screen("providers")
    data object Settings : Screen("settings")
    data object CloudSettings : Screen("cloud_settings")

    data object ProviderEdit : Screen("provider_edit?providerId={providerId}") {
        fun createRoute(providerId: String? = null) =
            if (providerId != null) "provider_edit?providerId=$providerId" else "provider_edit"
    }

    data object ProviderDetail : Screen("provider_detail/{providerId}") {
        fun createRoute(providerId: String) = "provider_detail/$providerId"
    }

    data object ModelDetail : Screen("model_detail/{modelId}") {
        fun createRoute(modelId: String) = "model_detail/$modelId"
    }

    data object AgentEdit : Screen("agent_edit?agentId={agentId}") {
        fun createRoute(agentId: String? = null) =
            if (agentId != null) "agent_edit?agentId=$agentId" else "agent_edit"
    }

    data object ConversationRename : Screen("conversation_rename/{conversationId}") {
        fun createRoute(conversationId: String) = "conversation_rename/$conversationId"
    }

    data object ConversationSettings : Screen("conversation_settings/{conversationId}") {
        fun createRoute(conversationId: String) = "conversation_settings/$conversationId"
    }

    data object ProviderPicker : Screen("provider_picker?selectedProviderId={selectedProviderId}") {
        fun createRoute(selectedProviderId: String? = null) =
            if (selectedProviderId != null) "provider_picker?selectedProviderId=$selectedProviderId" else "provider_picker"
    }

    data object ModelPicker : Screen("model_picker/{providerId}?selectedModelId={selectedModelId}") {
        fun createRoute(providerId: String, selectedModelId: String? = null) =
            if (selectedModelId != null) "model_picker/$providerId?selectedModelId=$selectedModelId" else "model_picker/$providerId"
    }

    data object Licenses : Screen("licenses")
}
