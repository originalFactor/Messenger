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
import cc.ptoe.messenger.data.util.synchronizedBlock
import cc.ptoe.messenger.domain.tool.ChatTool
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

import cc.ptoe.messenger.data.util.ioDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** A descriptor only; connection handles and credentials remain private to the manager. */
internal data class McpInventoryTool(val serverId: String, val serverName: String, val remoteName: String,
    val name: String, val description: String, val parameters: JsonObject)

internal class McpInventorySnapshot internal constructor(
    tools: List<McpInventoryTool>,
    private val owner: McpManager,
    private val bindings: Map<String, Pair<McpClient, McpClientInventory>>,
    private val epoch: Long,
) {
    private val frozenTools = tools.map(::copyInventoryTool)
    val tools: List<McpInventoryTool> get() = frozenTools.map(::copyInventoryTool)
    internal fun matches(candidate: McpManager, current: Map<String, Pair<McpClient, McpClientInventory>>, currentEpoch: Long): Boolean =
        owner === candidate && epoch == currentEpoch && bindings == current
    internal fun binding(serverId: String): Pair<McpClient, McpClientInventory>? = bindings[serverId]
    internal fun matchesEpoch(candidate: McpManager, currentEpoch: Long): Boolean = owner === candidate && epoch == currentEpoch
}

private fun copyInventoryTool(tool: McpInventoryTool): McpInventoryTool =
    tool.copy(parameters = Json.parseToJsonElement(tool.parameters.toString()).jsonObject)

class McpManager(
    private val appPreferences: AppPreferences,
    private val enabled: Boolean = true,
    private val bridgeProvider: () -> cc.ptoe.messenger.core.CoreBridge? = { cc.ptoe.messenger.core.CoreBridgeRegistry.bridge },
    private val startImmediately: Boolean = true,
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
    private val editMutex = Mutex()
    private val startLock = Any()
    private val inventoryAdmissionLock = Any()
    private var started = false
    private var editing = false
    private var needsReload = false
    private var inventoryEpoch = 0L
    @Volatile private var stopped = !enabled
    private val closingClients = mutableSetOf<McpClient>()

    private val _servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val servers: StateFlow<List<McpServerConfig>> = _servers.asStateFlow()
    private val _configurationError = MutableStateFlow(false)
    /** True when the persisted MCP configuration could not be decoded. */
    val configurationError: StateFlow<Boolean> = _configurationError.asStateFlow()
    private val clients = mutableMapOf<String, McpClient>()
    private val toolObservers = mutableMapOf<String, Job>()
    private val pendingClients = mutableSetOf<String>()
    private val _activeTools = MutableStateFlow<List<ChatTool>>(emptyList())
    val activeTools: StateFlow<List<ChatTool>> = _activeTools.asStateFlow()
    private val _toolInventoryReady = MutableStateFlow(false)
    /** True after all enabled servers have finished their initial connection attempt. */
    val toolInventoryReady: StateFlow<Boolean> = _toolInventoryReady.asStateFlow()
    private val _agentDelegationEnabled = MutableStateFlow(false)
    val agentDelegationEnabled: StateFlow<Boolean> = _agentDelegationEnabled.asStateFlow()

    init {
        if (enabled && startImmediately) start()
    }

    /** Start only after the enclosing owner has registered shutdown. */
    internal fun start() = synchronizedBlock(startLock) {
        check(enabled && !stopped && !started) { "MCP manager cannot be started" }
        started = true
        scope.launch {
            try {
                loadServers()
                delegationMutex.withLock {
                    bridgeProvider()?.let {
                        _agentDelegationEnabled.value = it.agentDelegationEnabled()
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: IllegalStateException) {
                if (!synchronizedBlock(startLock) { stopped }) throw failure
            }
        }
    }

    suspend fun setAgentDelegationEnabled(enabled: Boolean) = delegationMutex.withLock {
        check(!stopped) { "MCP manager is shut down" }
        requireNotNull(bridgeProvider()).setAgentDelegationEnabled(enabled)
        _agentDelegationEnabled.value = enabled
    }

    suspend fun loadServers(): List<McpServerConfig> = editMutex.withLock {
        check(!stopped) { "MCP manager is shut down" }
        val rawJson = appPreferences.mcpServersJson.first()
        val list = if (!rawJson.isNullOrBlank()) {
            val decoded = runCatching { json.decodeFromString(ListSerializer(McpServerConfig.serializer()), rawJson) }
            decoded.getOrElse {
                mutex.withLock { synchronizedBlock(inventoryAdmissionLock) { inventoryEpoch++ }; needsReload = true; _configurationError.value = true; updateInventoryReadyLocked() }
                return@withLock _servers.value
            }
        } else emptyList()
        applyConfiguration(list, persist = false)
        list
    }

    suspend fun saveServers(newServers: List<McpServerConfig>) = editMutex.withLock {
        applyConfiguration(newServers, persist = true)
    }

    private suspend fun editServers(change: (List<McpServerConfig>) -> List<McpServerConfig>) = editMutex.withLock {
        check(!stopped) { "MCP manager is shut down" }
        check(!needsReload) { "Reload MCP settings before editing" }
        applyConfiguration(change(_servers.value), persist = true)
    }

    suspend fun addServer(config: McpServerConfig) = editServers { it.filter { old -> old.id != config.id } + config }
    suspend fun updateServer(config: McpServerConfig) = editServers { it.map { old -> if (old.id == config.id) config else old } }
    suspend fun removeServer(serverId: String) = editServers { it.filter { old -> old.id != serverId } }
    suspend fun toggleServer(serverId: String, enabled: Boolean) = editServers { list ->
        list.map { if (it.id == serverId) it.copy(isEnabled = enabled) else it }
    }

    private fun enabledConfigs(configs: List<McpServerConfig>): Map<String, McpServerConfig> =
        configs.filter { it.isEnabled }.distinctBy { it.id }.associateBy { it.id }

    /** The manager mutex protects state only; no client close or DataStore write holds it. */
    private suspend fun applyConfiguration(configs: List<McpServerConfig>, persist: Boolean) {
        val retiring = mutex.withLock {
            check(!stopped) { "MCP manager is shut down" }
            editing = true
            synchronizedBlock(inventoryAdmissionLock) { inventoryEpoch++ }
            val desired = enabledConfigs(configs)
            val removed = clients.keys.filter { id -> desired[id] != clients[id]?.config }
            for (id in removed) {
                pendingClients.remove(id)
                toolObservers.remove(id)?.cancel()
                clients.remove(id)?.let { closingClients.add(it) }
            }
            recomputeActiveTools()
            updateInventoryReadyLocked()
            closingClients.toList()
        }
        try {
            closeClients(retiring)?.let { throw it }
            currentCoroutineContext().ensureActive()
            if (persist) {
                val raw = json.encodeToString(ListSerializer(McpServerConfig.serializer()), configs)
                // A failed/cancelled write may have committed. Force a reload before incremental edits.
                mutex.withLock { check(!stopped) { "MCP manager is shut down" }; needsReload = true }
                var writeAdmitted = false
                try {
                    currentCoroutineContext().ensureActive()
                    writeAdmitted = true
                    withContext(NonCancellable) { appPreferences.setMcpServersJson(raw) }
                }
                catch (failure: Throwable) {
                    withContext(NonCancellable) { mutex.withLock {
                        needsReload = writeAdmitted
                        if (writeAdmitted) _configurationError.value = true
                    } }
                    throw failure
                }
            }
            mutex.withLock {
                check(!stopped) { "MCP manager is shut down" }
                needsReload = false
                _configurationError.value = false
                _servers.value = configs
                startMissingClientsLocked(configs)
            }
        } catch (failure: Throwable) {
            // A cancelled edit may already have retired the old client. Restore the
            // persisted inventory only after every old client has proved it stopped.
            try {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (needsReload) _configurationError.value = true
                        if (!stopped && !needsReload && closingClients.isEmpty()) startMissingClientsLocked(_servers.value)
                    }
                }
            } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        } finally {
            withContext(NonCancellable) { mutex.withLock { editing = false; updateInventoryReadyLocked() } }
        }
    }

    private fun startMissingClientsLocked(configs: List<McpServerConfig>) {
        for (config in enabledConfigs(configs).values) {
            if (!clients.containsKey(config.id)) {
                val client = McpClient(config, bridgeFactory)
                clients[config.id] = client
                pendingClients.add(config.id)
                toolObservers[config.id] = scope.launch {
                    client.tools.collect {
                        mutex.withLock {
                            if (clients[config.id] === client) recomputeActiveTools()
                        }
                    }
                }
                scope.launch {
                    try { client.connect() } finally {
                        mutex.withLock {
                            if (clients[config.id] === client) {
                                pendingClients.remove(config.id)
                                recomputeActiveTools()
                            }
                            updateInventoryReadyLocked()
                        }
                    }
                }
            }
        }
        recomputeActiveTools()
        updateInventoryReadyLocked()
    }

    private fun updateInventoryReadyLocked() {
        val desired = enabledConfigs(_servers.value)
        _toolInventoryReady.value = !stopped && !editing && !needsReload && closingClients.isEmpty() &&
            pendingClients.isEmpty() && clients.size == desired.size &&
            clients.all { (id, client) -> desired[id] == client.config }
    }

    /** Start every close even if another hangs or fails; a failure remains owned for retry. */
    private suspend fun closeClients(closing: List<McpClient>): Throwable? = withContext(NonCancellable) {
        val results = supervisorScope {
            closing.map { client -> async {
                try { client.close(); null } catch (failure: Throwable) { failure }
            } }.map { it.await() }
        }
        mutex.withLock {
            closing.zip(results).forEach { (client, failure) -> if (failure == null) closingClients.remove(client) }
            updateInventoryReadyLocked()
        }
        var error: Throwable? = null
        results.filterNotNull().forEach { failure ->
            if (error == null) error = failure else if (error !== failure) error?.addSuppressed(failure)
        }
        error
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

    /** A coherent view of the published inventory, not permission to dispatch a tool. */
    internal suspend fun inventorySnapshot(): McpInventorySnapshot = mutex.withLock {
        check(_toolInventoryReady.value && !stopped) { "MCP inventory is unavailable" }
        require(_servers.value.map { it.id }.distinct().size == _servers.value.size) { "Duplicate MCP server identity" }
        val bindings = stableInventoryLocked()
        val tools = bindings.flatMap { (id, pair) ->
            val (client, state) = pair
            if (!state.connected) emptyList() else state.tools.map { tool ->
                require(id.isNotBlank() && client.config.name.isNotBlank() && tool.name.isNotBlank()) { "Invalid MCP tool identity" }
                McpInventoryTool(id, client.config.name, tool.name, "${client.config.name}_${tool.name}",
                    tool.description ?: "MCP tool ${tool.name} from ${client.config.name}", tool.inputSchema)
            }
        }
        require(tools.none { it.name.startsWith("messenger_") } && tools.map { it.name }.distinct().size == tools.size) {
            "Duplicate or reserved MCP tool name"
        }
        require(tools.size <= 1_000 && tools.all {
            it.name.encodeToByteArray().size <= 512 && it.description.encodeToByteArray().size <= 16_384 &&
                it.parameters.toString().encodeToByteArray().size <= 65_536
        } && tools.sumOf { it.parameters.toString().encodeToByteArray().size.toLong() } <= 4L * 1024 * 1024) { "MCP inventory exceeds limits" }
        McpInventorySnapshot(tools, this, bindings, inventoryEpoch)
    }

    /** Rechecking cannot itself authorize execution; a later claim needs a joint admission fence. */
    internal suspend fun isInventoryCurrent(snapshot: McpInventorySnapshot): Boolean = mutex.withLock {
        if (!_toolInventoryReady.value || stopped) return@withLock false
        snapshot.matches(this, stableInventoryLocked(), inventoryEpoch)
    }

    /**
     * Serializes initial inventory validation and durable Room claim with configuration edits.
     * The caller must still authorize at the actual transport send boundary.
     */
    internal suspend fun <T> admitInventoryCall(snapshot: McpInventorySnapshot, tool: McpInventoryTool,
        claim: suspend () -> T): Pair<McpClient, T> = mutex.withLock {
        check(_toolInventoryReady.value && !stopped && snapshot.matches(this, stableInventoryLocked(), inventoryEpoch)) {
            "MCP inventory changed"
        }
        check(snapshot.tools.count { it == tool } == 1) { "MCP tool is unavailable" }
        val (client, _) = checkNotNull(snapshot.binding(tool.serverId)) { "MCP server is unavailable" }
        val claimed = claim()
        check(_toolInventoryReady.value && !stopped && snapshot.matches(this, stableInventoryLocked(), inventoryEpoch)) {
            "MCP inventory changed during claim"
        }
        client to claimed
    }

    /** Called immediately before transport send; no suspend, network or cleanup under these locks. */
    internal fun authorizeInventorySend(snapshot: McpInventorySnapshot, tool: McpInventoryTool,
        client: McpClient, authorize: () -> Unit) = synchronizedBlock(inventoryAdmissionLock) {
        check(!stopped && snapshot.matchesEpoch(this, inventoryEpoch)) { "MCP configuration changed" }
        val (expectedClient, captured) = checkNotNull(snapshot.binding(tool.serverId)) { "MCP server is unavailable" }
        check(expectedClient === client) { "MCP client changed" }
        client.admitInventory(captured, authorize)
    }

    private fun stableInventoryLocked(): Map<String, Pair<McpClient, McpClientInventory>> {
        repeat(8) {
            val first = clients.mapValues { (_, client) -> client to client.inventory() }
            if (first.all { (_, pair) -> pair.first.inventory() == pair.second }) return first
        }
        error("MCP inventory is changing")
    }

    /** Stop admission, close every connection and wait for in-flight connection work. */
    suspend fun shutdown(): Unit = withContext(NonCancellable) {
        shutdownMutex.withLock {
            val job = scope.coroutineContext[Job]
            synchronizedBlock(startLock) { synchronizedBlock(inventoryAdmissionLock) { stopped = true }; job?.cancel() }
            val closing = mutex.withLock {
                stopped = true
                closingClients.addAll(clients.values)
                clients.clear()
                toolObservers.clear()
                pendingClients.clear()
                _activeTools.value = emptyList()
                _toolInventoryReady.value = false
                closingClients.toList()
            }
            val failure = closeClients(closing)
            // Close clients first so an edit blocked on one close cannot prevent the
            // shutdown from revoking the other clients. Then join the edit itself.
            editMutex.withLock { }
            delegationMutex.withLock { }
            job?.join()
            failure?.let { throw it }
        }
    }
}
