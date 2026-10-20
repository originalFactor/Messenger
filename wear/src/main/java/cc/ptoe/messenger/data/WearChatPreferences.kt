/*
 * Copyright 2026 originalFactor
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

package cc.ptoe.messenger.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.wearChatDataStore by preferencesDataStore(name = "wear_chat")

class WearChatPreferences(private val context: Context) {

    private object Keys {
        val AGENTS = stringPreferencesKey("agents")
        val CONVERSATIONS = stringPreferencesKey("conversations")
        val SELECTED_CONVERSATION_ID = stringPreferencesKey("selected_conversation_id")
        val MESSAGE_HISTORY = stringPreferencesKey("message_history")
        val USER_AVATAR_PATH = stringPreferencesKey("user_avatar_path")
    }

    val agents: Flow<List<WearAgent>> = context.wearChatDataStore.data.map { preferences ->
        WearChatJsonCodec.decodeAgents(preferences[Keys.AGENTS])
    }

    val conversations: Flow<List<WearConversation>> = context.wearChatDataStore.data.map { preferences ->
        WearChatJsonCodec.decodeConversations(preferences[Keys.CONVERSATIONS])
    }

    val selectedConversationId: Flow<String?> = context.wearChatDataStore.data.map { preferences ->
        preferences[Keys.SELECTED_CONVERSATION_ID]
    }

    val messageHistory: Flow<Map<String, List<WearChatMessage>>> =
        context.wearChatDataStore.data.map { preferences ->
            WearChatJsonCodec.decodeMessages(preferences[Keys.MESSAGE_HISTORY])
        }

    val userAvatarPath: Flow<String?> = context.wearChatDataStore.data.map { preferences ->
        preferences[Keys.USER_AVATAR_PATH]
    }

    suspend fun setAgents(agents: List<WearAgent>) {
        context.wearChatDataStore.edit { preferences ->
            preferences[Keys.AGENTS] = WearChatJsonCodec.encodeAgents(agents)
        }
    }

    suspend fun setConversations(conversations: List<WearConversation>) {
        context.wearChatDataStore.edit { preferences ->
            preferences[Keys.CONVERSATIONS] = WearChatJsonCodec.encodeConversations(conversations)
        }
    }

    suspend fun setSelectedConversationId(conversationId: String?) {
        context.wearChatDataStore.edit { preferences ->
            if (conversationId.isNullOrBlank()) {
                preferences.remove(Keys.SELECTED_CONVERSATION_ID)
            } else {
                preferences[Keys.SELECTED_CONVERSATION_ID] = conversationId
            }
        }
    }

    suspend fun setMessageHistory(history: Map<String, List<WearChatMessage>>) {
        context.wearChatDataStore.edit { preferences ->
            preferences[Keys.MESSAGE_HISTORY] = WearChatJsonCodec.encodeMessages(history)
        }
    }

    suspend fun setUserAvatarPath(path: String?) {
        context.wearChatDataStore.edit { preferences ->
            if (path.isNullOrBlank()) {
                preferences.remove(Keys.USER_AVATAR_PATH)
            } else {
                preferences[Keys.USER_AVATAR_PATH] = path
            }
        }
    }

    suspend fun applySnapshot(
        agents: List<WearAgent>,
        conversations: List<WearConversation>,
        messages: Map<String, List<WearChatMessage>>,
        userAvatarPath: String?,
        selectedConversationId: String?
    ) {
        context.wearChatDataStore.edit { preferences ->
            preferences[Keys.AGENTS] = WearChatJsonCodec.encodeAgents(agents)
            preferences[Keys.CONVERSATIONS] = WearChatJsonCodec.encodeConversations(conversations)
            preferences[Keys.MESSAGE_HISTORY] = WearChatJsonCodec.encodeMessages(messages)
            if (userAvatarPath.isNullOrBlank()) {
                preferences.remove(Keys.USER_AVATAR_PATH)
            } else {
                preferences[Keys.USER_AVATAR_PATH] = userAvatarPath
            }
            if (selectedConversationId.isNullOrBlank()) {
                preferences.remove(Keys.SELECTED_CONVERSATION_ID)
            } else {
                preferences[Keys.SELECTED_CONVERSATION_ID] = selectedConversationId
            }
        }
    }
}
