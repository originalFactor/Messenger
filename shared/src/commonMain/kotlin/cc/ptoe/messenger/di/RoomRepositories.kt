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

package cc.ptoe.messenger.di

import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import cc.ptoe.messenger.domain.repository.ModelRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository

/**
 * The Room-backed repository bundle. Constructed only on Android/Desktop:
 * the web target has no Room artifact, and always runs on the Rust core.
 */
class RoomRepositories(
    val providers: ProviderRepository,
    val models: ModelRepository,
    val agents: AgentRepository,
    val conversations: ConversationRepository,
    val messages: MessageRepository,
)

/** Callbacks the Room repositories use to notify the cloud sync facade. */
class LocalChangeHooks(
    val onProviderChanged: (id: String, deleted: Boolean) -> Unit,
    val onModelChanged: (providerId: String) -> Unit,
    val onAgentChanged: (previous: Agent?, current: Agent?) -> Unit,
    val onConversationChanged: (id: String, deleted: Boolean) -> Unit,
    val onMessagesChanged: (conversationId: String) -> Unit,
)

/** Only Android/Desktop call this; wasmJs always has a CoreBridge. */
expect fun createRoomRepositories(
    stores: LocalStores,
    chatImageStore: ChatImageStore,
    hooks: LocalChangeHooks,
): RoomRepositories
