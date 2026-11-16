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

package cc.ptoe.messenger.domain.mcp

import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.domain.tool.ChatTool
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val delegationMutex = Mutex()
    private val shutdownMutex = Mutex()
    @Volatile private var stopped = false
    private val closingClients = mutableSetOf<McpClient>()

    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()
    private val _configurationError = MutableStateFlow(false)
    /** True when the persisted MCP configuration could not be decoded. */
    val configurationError: StateFlow<Boolean> = _configurationError.asStateFlow()
    private val clients = mutableMapOf<String, McpClient>()
    private val pendingClients = mutableSetOf<String>()
    private val _activeTools = MutableStateFlow<List<ChatTool>>(emptyList())
    val activeTools: StateFlow<List<ChatTool>> = _activeTools.asStateFlow()
    private val _toolInventoryReady = MutableStateFlow(false)
    /** True after all enabled servers have finished their initial connection attempt. */
    val toolInventoryReady: StateFlow<Boolean> = _toolInventoryReady.asStateFlow()
    private val _agentDelegationEnabled = MutableStateFlow(false)
    val agentDelegationEnabled: StateFlow<Boolean> = _agentDelegationEnabled.asStateFlow()

    init {
        scope.launch {
            loadServers()
            delegationMutex.withLock {
                cc.ptoe.messenger.core.CoreBridgeRegistry.bridge?.let {
                    _agentDelegationEnabled.value = it.agentDelegationEnabled()
                }
            }
        }
    }

    suspend fun setAgentDelegationEnabled(enabled: Boolean) = delegationMutex.withLock {
        check(!stopped) { "MCP manager is shut down" }
        requireNotNull(cc.ptoe.messenger.core.CoreBridgeRegistry.bridge).setAgentDelegationEnabled(enabled)
        _agentDelegationEnabled.value = enabled
    }

    suspend fun loadServers(): List<McpServerConfig> = mutex.withLock {
        check(!stopped) { "MCP manager is shut down" }
        val rawJson = appPreferences.mcpServersJson.first()
        val list = if (!rawJson.isNullOrBlank()) {
            val decoded = runCatching {
                json.decodeFromString(ListSerializer(McpServerConfig.serializer()), rawJson)
            }
            decoded.getOrElse {
                // Keep the last known-good in-memory configuration. Clearing
                // it here would make a corrupted preference look like the
                // user deleted every MCP server.
                _configurationError.value = true
                return@withLock _servers.value
            }
        } else {
            emptyList()
        }
        _configurationError.value = false
        _servers.value = list
        syncClientsLocked(list)
        list
    }

    suspend fun saveServers(newServers: List<McpServerConfig>) = mutex.withLock {
        check(!stopped) { "MCP manager is shut down" }
        _configurationError.value = false
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
        // A prior close failure is not evidence that the process stopped. Keep it
        // owned and retry it before allowing replacement clients to start.
        for (client in closingClients.toList()) {
            client.close()
            closingClients.remove(client)
        }
        val activeIds = configs.filter { it.isEnabled }.map { it.id }.toSet()

        // Close removed or disabled clients
        val toRemove = clients.keys.filter { it !in activeIds }
        for (id in toRemove) {
            pendingClients.remove(id)
            clients.remove(id)?.let { client ->
                closingClients.add(client)
                // Do not keep publishing this client's tools if close fails.
                recomputeActiveTools()
                _toolInventoryReady.value = pendingClients.isEmpty()
                client.close()
                closingClients.remove(client)
            }
        }

        // Start/connect newly enabled clients
        for (config in configs) {
            if (config.isEnabled && !clients.containsKey(config.id)) {
                val client = McpClient(config, bridgeFactory)
                clients[config.id] = client
                pendingClients.add(config.id)
                scope.launch {
                    try {
                        client.connect()
                    } finally {
                        mutex.withLock {
                            if (clients[config.id] === client) {
                                pendingClients.remove(config.id)
                                recomputeActiveTools()
                                _toolInventoryReady.value = pendingClients.isEmpty()
                            }
                        }
                    }
                }
            }
        }

        recomputeActiveTools()
        _toolInventoryReady.value = pendingClients.isEmpty()
    }

    private fun recomputeActiveTools() {
        val toolsList = mutableListOf<ChatTool>()
        for (client in clients.values) {
            toolsList.addAll(client.tools.value)
        }
        _activeTools.value = toolsList
    }

    suspend fun getActiveTools(): List<ChatTool> = mutex.withLock {
        recomputeActiveTools()
        _activeTools.value
    }

    /** Stop admission, close every connection and wait for in-flight connection work. */
    suspend fun shutdown(): Unit = withContext(NonCancellable) {
        shutdownMutex.withLock {
            val job = scope.coroutineContext[Job]
            job?.cancel()
            val closing = delegationMutex.withLock {
                mutex.withLock {
                    stopped = true
                    closingClients.addAll(clients.values)
                    clients.clear()
                    pendingClients.clear()
                    _activeTools.value = emptyList()
                    _toolInventoryReady.value = false
                    closingClients.toList()
                }
            }
            var failure: Throwable? = null
            for (client in closing) {
                try {
                    client.close()
                    mutex.withLock { closingClients.remove(client) }
                } catch (error: Throwable) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
            job?.join()
            failure?.let { throw it }
        }
    }
}
