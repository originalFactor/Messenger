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

package cc.ptoe.messenger.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class AppPreferences(private val dataStore: DataStore<Preferences>) {

    private object PreferencesKeys {
        val DEFAULT_AGENT_INITIALIZED = booleanPreferencesKey("default_agent_initialized")
        val CURRENT_AGENT_ID = stringPreferencesKey("current_agent_id")
        val USER_AVATAR = stringPreferencesKey("user_avatar")
        val CLOUD_SERVER_URL = stringPreferencesKey("cloud_server_url")
        val CLOUD_SESSION = stringPreferencesKey("cloud_session")
        val CLOUD_SESSION_HOST = stringPreferencesKey("cloud_session_host")
        val CLOUD_USER = stringPreferencesKey("cloud_user")
        val CLOUD_AI_API_KEY = stringPreferencesKey("cloud_ai_api_key")
        /** MCP 服务器配置列表 JSON。 */
        val MCP_SERVERS_JSON = stringPreferencesKey("mcp_servers_json")
    }

    val defaultAgentInitialized: Flow<Boolean> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.DEFAULT_AGENT_INITIALIZED] ?: false
        }

    val mcpServersJson: Flow<String?> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.MCP_SERVERS_JSON]
        }

    suspend fun setMcpServersJson(json: String?) {
        dataStore.edit { preferences ->
            if (json != null) {
                preferences[PreferencesKeys.MCP_SERVERS_JSON] = json
            } else {
                preferences.remove(PreferencesKeys.MCP_SERVERS_JSON)
            }
        }
    }

    suspend fun setDefaultAgentInitialized(initialized: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_AGENT_INITIALIZED] = initialized
        }
    }

    val currentAgentId: Flow<String?> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.CURRENT_AGENT_ID]
        }

    suspend fun setCurrentAgentId(agentId: String?) {
        dataStore.edit { preferences ->
            if (agentId != null) {
                preferences[PreferencesKeys.CURRENT_AGENT_ID] = agentId
            } else {
                preferences.remove(PreferencesKeys.CURRENT_AGENT_ID)
            }
        }
    }

    val userAvatar: Flow<String?> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.USER_AVATAR]
        }

    suspend fun setUserAvatar(avatar: String?) {
        dataStore.edit { preferences ->
            if (avatar != null) {
                preferences[PreferencesKeys.USER_AVATAR] = avatar
            } else {
                preferences.remove(PreferencesKeys.USER_AVATAR)
            }
        }
    }

    val cloudServerUrl: Flow<String?> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.CLOUD_SERVER_URL] }

    suspend fun setCloudServerUrl(url: String?) {
        dataStore.edit { preferences ->
            if (url.isNullOrBlank()) {
                preferences.remove(PreferencesKeys.CLOUD_SERVER_URL)
            } else {
                preferences[PreferencesKeys.CLOUD_SERVER_URL] = url.trim().trimEnd('/')
            }
        }
    }

    val cloudSession: Flow<String?> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.CLOUD_SESSION] }

    suspend fun setCloudSession(session: String?) {
        dataStore.edit { preferences ->
            if (session.isNullOrBlank()) preferences.remove(PreferencesKeys.CLOUD_SESSION)
            else preferences[PreferencesKeys.CLOUD_SESSION] = session
        }
    }

    val cloudSessionHost: Flow<String?> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.CLOUD_SESSION_HOST] }

    suspend fun setCloudSessionHost(host: String?) {
        dataStore.edit { preferences ->
            if (host.isNullOrBlank()) preferences.remove(PreferencesKeys.CLOUD_SESSION_HOST)
            else preferences[PreferencesKeys.CLOUD_SESSION_HOST] = host
        }
    }

    val cloudUser: Flow<String?> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.CLOUD_USER] }

    suspend fun setCloudUser(user: String?) {
        dataStore.edit { preferences ->
            if (user.isNullOrBlank()) preferences.remove(PreferencesKeys.CLOUD_USER)
            else preferences[PreferencesKeys.CLOUD_USER] = user
        }
    }

    val cloudAiApiKey: Flow<String?> = dataStore.data
        .map { preferences -> preferences[PreferencesKeys.CLOUD_AI_API_KEY] }

    suspend fun setCloudAiApiKey(key: String?) {
        dataStore.edit { preferences ->
            if (key.isNullOrBlank()) preferences.remove(PreferencesKeys.CLOUD_AI_API_KEY)
            else preferences[PreferencesKeys.CLOUD_AI_API_KEY] = key
        }
    }

    suspend fun clearAll() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    suspend fun clearCloudAccount(accountId: String?) {
        dataStore.edit { preferences ->
            preferences.remove(PreferencesKeys.CLOUD_SESSION)
            preferences.remove(PreferencesKeys.CLOUD_SESSION_HOST)
            preferences.remove(PreferencesKeys.CLOUD_USER)
            preferences.remove(PreferencesKeys.CLOUD_AI_API_KEY)
            if (accountId != null) {
                preferences.remove(syncVersionKey(accountId))
                preferences.remove(pendingDeletesKey(accountId))
                preferences.remove(pendingUpsertsKey(accountId))
            }
        }
    }

    suspend fun cloudSyncVersion(accountId: String): Long {
        return dataStore.data
            .map { preferences -> preferences[syncVersionKey(accountId)]?.toLongOrNull() ?: 0L }
            .first()
    }

    suspend fun setCloudSyncVersion(accountId: String, version: Long) {
        dataStore.edit { preferences ->
            preferences[syncVersionKey(accountId)] = version.toString()
        }
    }

    suspend fun clearCloudSyncVersion(accountId: String) {
        dataStore.edit { preferences ->
            preferences.remove(syncVersionKey(accountId))
            preferences.remove(pendingDeletesKey(accountId))
            preferences.remove(pendingUpsertsKey(accountId))
        }
    }

    suspend fun addCloudPendingDelete(accountId: String, type: String, id: String) {
        dataStore.edit { preferences ->
            val key = pendingDeletesKey(accountId)
            val values = preferences[key].orEmpty().toMutableSet()
            values += "$type:$id"
            preferences[key] = values
        }
    }

    suspend fun addCloudPendingUpsert(accountId: String, type: String, id: String) {
        dataStore.edit { preferences ->
            val key = pendingUpsertsKey(accountId)
            val values = preferences[key].orEmpty().toMutableSet()
            values += "$type:$id"
            preferences[key] = values
        }
    }

    suspend fun cloudPendingUpserts(accountId: String): Set<String> {
        return dataStore.data
            .map { preferences -> preferences[pendingUpsertsKey(accountId)].orEmpty() }
            .first()
    }

    suspend fun removeCloudPendingUpsert(accountId: String, type: String, id: String) {
        dataStore.edit { preferences ->
            val key = pendingUpsertsKey(accountId)
            val values = preferences[key].orEmpty().toMutableSet()
            values.remove("$type:$id")
            if (values.isEmpty()) preferences.remove(key) else preferences[key] = values
        }
    }

    suspend fun cloudPendingDeletes(accountId: String): Set<String> {
        return dataStore.data
            .map { preferences -> preferences[pendingDeletesKey(accountId)].orEmpty() }
            .first()
    }

    suspend fun removeCloudPendingDelete(accountId: String, type: String, id: String) {
        dataStore.edit { preferences ->
            val key = pendingDeletesKey(accountId)
            val values = preferences[key].orEmpty().toMutableSet()
            values.remove("$type:$id")
            if (values.isEmpty()) preferences.remove(key) else preferences[key] = values
        }
    }

    private fun syncVersionKey(accountId: String) =
        stringPreferencesKey("cloud_sync_version_$accountId")

    private fun pendingDeletesKey(accountId: String) =
        stringSetPreferencesKey("cloud_pending_deletes_$accountId")

    private fun pendingUpsertsKey(accountId: String) =
        stringSetPreferencesKey("cloud_pending_upserts_$accountId")
}
