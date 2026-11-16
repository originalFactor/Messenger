/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.mcp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.data.local.AppPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpManagerReconcileTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private fun config(id: String, command: String = id) =
        McpServerConfig(id = id, name = id, transportType = McpTransportType.STDIO, command = command)

    private class Servers : AutoCloseable {
        val starts = ConcurrentLinkedQueue<String>()
        val stops = ConcurrentLinkedQueue<String>()
        val pendingInitialize = CompletableDeferred<Unit>()
        val closeEntered = CompletableDeferred<Unit>()
        val allowClose = CompletableDeferred<Unit>()
        val failA = AtomicBoolean(false)
        val blockA = AtomicBoolean(false)
        val holdC = AtomicBoolean(false)
        private val callbacks = ConcurrentHashMap<String, (String) -> Unit>()
        private val closeCallbacks = ConcurrentHashMap<String, (Int) -> Unit>()
        private val initializeIds = ConcurrentHashMap<String, Long>()

        fun bridge(): McpProcessBridge = object : McpProcessBridge {
            private var command = ""
            override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean {
                this.command = command
                callbacks[command] = onLine
                closeCallbacks[command] = onClose
                starts.add(command)
                return true
            }
            override suspend fun sendLine(line: String): Boolean {
                val request = Json.decodeFromString<JsonRpcRequest>(line)
                when (request.method) {
                    "initialize" -> {
                        if (command == "c" && holdC.get()) {
                            initializeIds[command] = checkNotNull(request.id)
                            pendingInitialize.complete(Unit)
                        } else replyInitialize(command, checkNotNull(request.id))
                    }
                    "tools/list" -> callbacks[command]?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"tools":[{"name":"tool","inputSchema":{"type":"object"}}]}}""")
                }
                return true
            }
            override suspend fun close() {
                stops.add(command)
                if (command == "a") {
                    check(!failA.get()) { "Cannot verify old A stopped" }
                    if (blockA.get()) { closeEntered.complete(Unit); allowClose.await() }
                }
            }
        }

        fun finishC() = replyInitialize("c", checkNotNull(initializeIds["c"]))
        fun disconnect(command: String) = checkNotNull(closeCallbacks[command]).invoke(1)
        private fun replyInitialize(command: String, id: Long) {
            callbacks[command]?.invoke("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""")
        }
        override fun close() { allowClose.complete(Unit) }
    }

    @Test fun unexpectedDisconnectWithdrawsOnlyThatServersTools(): Unit = runBlocking {
        val appPreferences = AppPreferences(MemoryPreferences())
        val initial = listOf(config("a"), config("b"))
        appPreferences.setMcpServersJson(Json.encodeToString(ListSerializer(McpServerConfig.serializer()), initial))
        val servers = Servers()
        val manager = McpManager(appPreferences) { servers.bridge() }
        try {
            withTimeout(5_000) { manager.toolInventoryReady.first { it } }
            assertEquals(setOf("a_tool", "b_tool"), manager.activeTools.value.map { it.name }.toSet())

            servers.disconnect("a")
            withTimeout(5_000) { manager.activeTools.first { tools -> tools.map { it.name }.toSet() == setOf("b_tool") } }
            assertTrue(manager.toolInventoryReady.value)

            manager.saveServers(listOf(config("a", "new-a"), config("b")))
            withTimeout(5_000) { manager.toolInventoryReady.first { it } }
            assertEquals(setOf("a_tool", "b_tool"), manager.activeTools.value.map { it.name }.toSet())
            servers.disconnect("a") // A late close belongs to the replaced client.
            assertEquals(setOf("a_tool", "b_tool"), manager.getActiveTools().map { it.name }.toSet())
        } finally { servers.close(); manager.shutdown() }
    }

    @Test fun failedMultiEditWithdrawsEveryChangedClientAndLateConnectCannotMarkReady(): Unit = runBlocking {
        val appPreferences = AppPreferences(MemoryPreferences())
        val old = listOf(config("a"), config("b"), config("c"))
        val changed = listOf(config("a", "new-a"), config("b", "new-b"), config("c"))
        appPreferences.setMcpServersJson(Json.encodeToString(ListSerializer(McpServerConfig.serializer()), old))
        val servers = Servers().apply { holdC.set(true) }
        val manager = McpManager(appPreferences) { servers.bridge() }
        try {
            withTimeout(5_000) {
                servers.pendingInitialize.await()
                manager.activeTools.first { tools -> tools.map { it.name }.toSet().containsAll(setOf("a_tool", "b_tool")) }
            }
            servers.failA.set(true)
            assertFailsWith<IllegalStateException> { manager.saveServers(changed) }
            assertTrue(servers.stops.containsAll(listOf("a", "b")))
            assertTrue(manager.activeTools.value.none { it.name == "a_tool" || it.name == "b_tool" })
            assertEquals(old, manager.servers.value)
            assertFalse(manager.toolInventoryReady.value)
            servers.finishC()
            withTimeout(5_000) { manager.activeTools.first { tools -> tools.any { it.name == "c_tool" } } }
            assertFalse(manager.toolInventoryReady.value, "a late connection cannot hide an unverified retirement")
            assertTrue(servers.starts.none { it.startsWith("new-") })
            servers.failA.set(false)
            manager.saveServers(changed)
            withTimeout(5_000) { manager.toolInventoryReady.first { it } }
            assertEquals(setOf("a_tool", "b_tool", "c_tool"), manager.activeTools.value.map { it.name }.toSet())
            assertEquals(changed, manager.servers.value)
        } finally { servers.failA.set(false); servers.close(); manager.shutdown() }
    }

    @Test fun shutdownStopsOtherClientsWhileAnEditWaitsForOneClose(): Unit = runBlocking {
        val appPreferences = AppPreferences(MemoryPreferences())
        val old = listOf(config("a"), config("b"))
        appPreferences.setMcpServersJson(Json.encodeToString(ListSerializer(McpServerConfig.serializer()), old))
        val servers = Servers().apply { blockA.set(true) }
        val manager = McpManager(appPreferences) { servers.bridge() }
        try {
            withTimeout(5_000) { manager.toolInventoryReady.first { it } }
            val edit = async(Dispatchers.IO) { runCatching { manager.saveServers(listOf(config("a", "new-a"), config("b"))) } }
            withTimeout(5_000) { servers.closeEntered.await() }
            val shutdown = async(Dispatchers.IO) { manager.shutdown() }
            withTimeout(5_000) { while ("b" !in servers.stops) delay(10) }
            assertFalse(shutdown.isCompleted, "shutdown must still wait for A's stop proof")
            servers.allowClose.complete(Unit)
            withTimeout(5_000) { shutdown.await(); edit.await() }
            assertTrue(servers.starts.none { it == "new-a" })
        } finally { servers.close(); manager.shutdown() }
    }

    @Test fun cancelledEditRestoresPersistedClientAfterTheOldOneStops(): Unit = runBlocking {
        val appPreferences = AppPreferences(MemoryPreferences())
        val old = listOf(config("a"), config("b"))
        appPreferences.setMcpServersJson(Json.encodeToString(ListSerializer(McpServerConfig.serializer()), old))
        val servers = Servers().apply { blockA.set(true) }
        val manager = McpManager(appPreferences) { servers.bridge() }
        try {
            withTimeout(5_000) { manager.toolInventoryReady.first { it } }
            val edit = async(Dispatchers.IO) { manager.saveServers(listOf(config("a", "new-a"), config("b"))) }
            withTimeout(5_000) { servers.closeEntered.await() }
            edit.cancel()
            servers.allowClose.complete(Unit)
            assertFailsWith<CancellationException> { withTimeout(5_000) { edit.await() } }
            withTimeout(5_000) { manager.toolInventoryReady.first { it } }
            assertEquals(old, manager.servers.value)
            assertEquals(2, servers.starts.count { it == "a" })
            assertTrue(servers.starts.none { it == "new-a" })
            assertEquals(setOf("a_tool", "b_tool"), manager.activeTools.value.map { it.name }.toSet())
        } finally { servers.close(); manager.shutdown() }
    }

    @Test fun shutdownRevokesClientsBeforeWaitingForBlockedDelegation(): Unit = runBlocking {
        val appPreferences = AppPreferences(MemoryPreferences())
        appPreferences.setMcpServersJson(Json.encodeToString(ListSerializer(McpServerConfig.serializer()), listOf(config("a"), config("b"))))
        val delegationEntered = CompletableDeferred<Unit>()
        val allowDelegation = CompletableDeferred<Unit>()
        val previous = CoreBridgeRegistry.bridge
        val stub = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ -> null } as CoreBridge
        CoreBridgeRegistry.bridge = object : CoreBridge by stub {
            override suspend fun agentDelegationEnabled() = false
            override suspend fun setAgentDelegationEnabled(enabled: Boolean) { delegationEntered.complete(Unit); allowDelegation.await() }
        }
        val servers = Servers()
        val manager = McpManager(appPreferences) { servers.bridge() }
        try {
            withTimeout(5_000) { manager.toolInventoryReady.first { it } }
            val setting = async(Dispatchers.IO) { manager.setAgentDelegationEnabled(true) }
            withTimeout(5_000) { delegationEntered.await() }
            val shutdown = async(Dispatchers.IO) { manager.shutdown() }
            withTimeout(5_000) { while (!servers.stops.containsAll(listOf("a", "b"))) delay(10) }
            assertFalse(shutdown.isCompleted)
            assertTrue(manager.activeTools.value.isEmpty())
            assertFailsWith<IllegalStateException> { manager.addServer(config("new")) }
            allowDelegation.complete(Unit)
            withTimeout(5_000) { shutdown.await(); setting.await() }
        } finally {
            allowDelegation.complete(Unit)
            servers.close()
            manager.shutdown()
            CoreBridgeRegistry.bridge = previous
        }
    }

    @Test fun concurrentIncrementalEditsDoNotOverwriteEachOther(): Unit = runBlocking {
        val firstWrite = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        val writes = AtomicInteger()
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (writes.incrementAndGet() == 1) { firstWrite.complete(Unit); allowWrite.await() }
                return transform(data.value).also { data.value = it }
            }
        }
        val appPreferences = AppPreferences(preferences)
        val manager = McpManager(appPreferences)
        try {
            withTimeout(5_000) { manager.toolInventoryReady.first { it } }
            val a = config("a").copy(isEnabled = false)
            val b = config("b").copy(isEnabled = false)
            val first = async(Dispatchers.IO) { manager.addServer(a) }
            withTimeout(5_000) { firstWrite.await() }
            val second = async(Dispatchers.IO) { manager.addServer(b) }
            delay(50)
            assertFalse(second.isCompleted)
            allowWrite.complete(Unit)
            withTimeout(5_000) { first.await(); second.await() }
            assertEquals(listOf(a, b), manager.servers.value)
            val stored = appPreferences.mcpServersJson.first()!!
            assertEquals(listOf(a, b), Json.decodeFromString(ListSerializer(McpServerConfig.serializer()), stored))
        } finally { allowWrite.complete(Unit); manager.shutdown() }
    }

    @Test fun ambiguousPreferenceWriteRequiresReloadBeforeIncrementalEdits(): Unit = runBlocking {
        val failAfterCommit = AtomicBoolean(true)
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val value = transform(data.value)
                data.value = value
                if (failAfterCommit.getAndSet(false)) error("Write outcome is ambiguous")
                return value
            }
        }
        val manager = McpManager(AppPreferences(preferences))
        val a = config("a").copy(isEnabled = false)
        val b = config("b").copy(isEnabled = false)
        try {
            withTimeout(5_000) { manager.toolInventoryReady.first { it } }
            assertFailsWith<IllegalStateException> { manager.addServer(a) }
            assertTrue(manager.servers.value.isEmpty())
            assertTrue(manager.configurationError.value)
            assertFalse(manager.toolInventoryReady.value)
            assertFailsWith<IllegalStateException> { manager.addServer(b) }
            assertEquals(listOf(a), manager.loadServers())
            manager.addServer(b)
            assertEquals(listOf(a, b), manager.servers.value)
        } finally { manager.shutdown() }
    }
}
