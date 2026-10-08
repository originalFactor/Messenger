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

package cc.ptoe.messenger.domain.mcp

import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.domain.tool.ChatTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

import cc.ptoe.messenger.data.util.ioDispatcher

class McpManager(
    private val appPreferences: AppPreferences,
    private val bridgeFactory: () -> McpProcessBridge = { createPlatformMcpBridge() }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val scope = CoroutineScope(ioDispatcher + Job())
    private val mutex = Mutex()

    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()

    private val clients = mutableMapOf<String, McpClient>()
    private val _activeTools = MutableStateFlow<List<ChatTool>>(emptyList())
    val activeTools: StateFlow<List<ChatTool>> = _activeTools.asStateFlow()

    init {
        scope.launch {
            loadServers()
        }
    }

    suspend fun loadServers(): List<McpServerConfig> = mutex.withLock {
        val rawJson = appPreferences.mcpServersJson.first()
        val list = if (!rawJson.isNullOrBlank()) {
            runCatching {
                json.decodeFromString(ListSerializer(McpServerConfig.serializer()), rawJson)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        _servers.value = list
        syncClientsLocked(list)
        list
    }

    suspend fun saveServers(newServers: List<McpServerConfig>) = mutex.withLock {
        _servers.value = newServers
        val rawJson = json.encodeToString(ListSerializer(McpServerConfig.serializer()), newServers)
        appPreferences.setMcpServersJson(rawJson)
        syncClientsLocked(newServers)
    }

    suspend fun addServer(config: McpServerConfig) {
        val current = _servers.value.filter { it.id != config.id } + config
        saveServers(current)
    }

    suspend fun updateServer(config: McpServerConfig) {
        val current = _servers.value.map { if (it.id == config.id) config else it }
        saveServers(current)
    }

    suspend fun removeServer(serverId: String) {
        val current = _servers.value.filter { it.id != serverId }
        saveServers(current)
    }

    suspend fun toggleServer(serverId: String, enabled: Boolean) {
        val current = _servers.value.map {
            if (it.id == serverId) it.copy(isEnabled = enabled) else it
        }
        saveServers(current)
    }

    private suspend fun syncClientsLocked(configs: List<McpServerConfig>) {
        val activeIds = configs.filter { it.isEnabled }.map { it.id }.toSet()

        // Close removed or disabled clients
        val toRemove = clients.keys.filter { it !in activeIds }
        for (id in toRemove) {
            clients.remove(id)?.close()
        }

        // Start/connect newly enabled clients
        for (config in configs) {
            if (config.isEnabled && !clients.containsKey(config.id)) {
                val client = McpClient(config, bridgeFactory)
                clients[config.id] = client
                scope.launch {
                    val connected = client.connect()
                    if (connected) {
                        recomputeActiveTools()
                    }
                }
            }
        }

        recomputeActiveTools()
    }

    private fun recomputeActiveTools() {
        val toolsList = mutableListOf<ChatTool>()
        for (client in clients.values) {
            toolsList.addAll(client.tools.value)
        }
        _activeTools.value = toolsList
    }

    suspend fun getActiveTools(): List<ChatTool> {
        recomputeActiveTools()
        return _activeTools.value
    }
}
