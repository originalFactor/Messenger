/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.mcp

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cc.ptoe.messenger.data.local.AppPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpManagerShutdownTest {
    @Test fun editingEnabledServerStopsOldClientBeforeReplacementWithoutReplayingCalls(): Unit = runBlocking {
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val original = McpServerConfig(id = "mock", name = "Old", transportType = McpTransportType.STDIO, command = "old")
        val updated = original.copy(name = "New", command = "new", env = mapOf("CONFIG" to "new"))
        val appPreferences = AppPreferences(preferences)
        appPreferences.setMcpServersJson(Json.encodeToString(ListSerializer(McpServerConfig.serializer()), listOf(original)))
        val starts = ConcurrentLinkedQueue<String>()
        val oldCalls = AtomicInteger()
        val newCalls = AtomicInteger()
        val callStarted = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        val allowClose = CompletableDeferred<Unit>()
        val bridgeCount = AtomicInteger()
        val verifyStop = AtomicBoolean(false)
        val manager = McpManager(appPreferences) {
            val ordinal = bridgeCount.incrementAndGet()
            object : McpProcessBridge {
                private var callback: ((String) -> Unit)? = null
                override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                    onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean {
                    starts.add(command)
                    if (ordinal == 2) assertEquals(mapOf("CONFIG" to "new"), env)
                    callback = onLine
                    return true
                }
                override suspend fun sendLine(line: String): Boolean {
                    val request = Json.decodeFromString<JsonRpcRequest>(line)
                    when (request.method) {
                        "initialize" -> callback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1"}}}""")
                        "tools/list" -> callback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"tools":[{"name":"work","inputSchema":{"type":"object"}}]}}""")
                        "tools/call" -> {
                            if (ordinal == 1) { oldCalls.incrementAndGet(); callStarted.complete(Unit) }
                            else newCalls.incrementAndGet()
                        }
                    }
                    return true
                }
                override suspend fun close() {
                    if (ordinal == 1) {
                        check(verifyStop.get()) { "Old process stop is unverified" }
                        closeStarted.complete(Unit)
                        allowClose.await()
                    }
                }
            }
        }
        try {
            withTimeout(5_000) { manager.toolInventoryReady.first { it } }
            val old = manager.activeTools.value.single()
            assertEquals("Old_work", old.name)
            val call = async(Dispatchers.IO) { old.execute("{}") }
            withTimeout(5_000) { callStarted.await() }
            assertFailsWith<IllegalStateException> { manager.saveServers(listOf(updated)) }
            assertTrue(manager.activeTools.value.isEmpty())
            assertFalse(manager.toolInventoryReady.value)
            assertEquals(listOf("old"), starts.toList())

            verifyStop.set(true)
            val replacement = async(Dispatchers.IO) { manager.saveServers(listOf(updated)) }
            withTimeout(5_000) { closeStarted.await() }
            assertFalse(replacement.isCompleted)
            assertEquals(listOf("old"), starts.toList())
            allowClose.complete(Unit)
            withTimeout(5_000) { replacement.await(); manager.toolInventoryReady.first { it } }
            assertTrue(withTimeout(5_000) { call.await() }.isError)
            assertEquals("New_work", manager.activeTools.value.single().name)
            assertEquals(listOf("old", "new"), starts.toList())
            assertEquals(1, oldCalls.get())
            assertEquals(0, newCalls.get(), "the old request must never be replayed")
            manager.saveServers(listOf(updated))
            assertEquals(listOf("old", "new"), starts.toList(), "unchanged settings must not restart the client")
        } finally {
            verifyStop.set(true)
            allowClose.complete(Unit)
            manager.shutdown()
        }
    }

    @Test fun failedRemovalRetainsUnverifiedClientUntilShutdownCanProveStop(): Unit = runBlocking {
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val config = McpServerConfig(id = "mock", name = "Mock", transportType = McpTransportType.STDIO, command = "mock")
        val appPreferences = AppPreferences(preferences)
        appPreferences.setMcpServersJson(Json.encodeToString(ListSerializer(McpServerConfig.serializer()), listOf(config)))
        val started = CompletableDeferred<Unit>()
        var starts = 0
        var verified = false
        val bridge = object : McpProcessBridge {
            override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean { starts++; started.complete(Unit); return true }
            override suspend fun sendLine(line: String) = true
            override suspend fun close() { check(verified) { "MCP process stop is unverified" } }
        }
        val manager = McpManager(appPreferences) { bridge }
        withTimeout(5_000) { started.await() }
        assertFailsWith<IllegalStateException> { withTimeout(5_000) { manager.saveServers(emptyList()) } }
        assertFailsWith<IllegalStateException> { withTimeout(5_000) { manager.saveServers(listOf(config)) } }
        assertEquals(1, starts, "an unverified old client must prevent a replacement")
        assertFailsWith<IllegalStateException> { withTimeout(5_000) { manager.shutdown() } }
        verified = true
        withTimeout(5_000) { manager.shutdown() }
    }

    @Test fun blockedStdioWriteIsStoppedBeforeJoiningItsCleanup(): Unit = runBlocking {
        val writeEntered = CompletableDeferred<Unit>()
        val stopRequested = CompletableDeferred<Unit>()
        val cleanupAllowed = CompletableDeferred<Unit>()
        var callback: ((String) -> Unit)? = null
        var closeCalls = 0
        val bridge = object : McpProcessBridge {
            override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean { callback = onLine; return true }
            override suspend fun sendLine(line: String): Boolean {
                val request = Json.decodeFromString<JsonRpcRequest>(line)
                when (request.method) {
                    "initialize" -> callback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1"}}}""")
                    "tools/list" -> callback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"tools":[]}}""")
                    "tools/call" -> withContext(NonCancellable) {
                        writeEntered.complete(Unit)
                        stopRequested.await()
                        cleanupAllowed.await()
                    }
                }
                return true
            }
            override suspend fun close() { closeCalls++; stopRequested.complete(Unit) }
        }
        val client = McpClient(McpServerConfig(id = "m", name = "Mock", transportType = McpTransportType.STDIO, command = "mock")) { bridge }
        assertTrue(client.connect())
        val call = async(Dispatchers.IO) { client.callTool("write", "{}") }
        withTimeout(5_000) { writeEntered.await() }
        val closing = async(Dispatchers.IO) { client.close() }
        try {
            withTimeout(5_000) { stopRequested.await() }
            assertFalse(closing.isCompleted, "close must join the actual blocked write cleanup")
        } finally { cleanupAllowed.complete(Unit) }
        withTimeout(5_000) { closing.await() }
        assertTrue(withTimeout(5_000) { call.await() }.isError)
        assertEquals(1, closeCalls)
    }

    @Test fun closingClientCompletesPendingToolCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var toolCalls = 0
        var callback: ((String) -> Unit)? = null
        val bridge = object : McpProcessBridge {
            override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean {
                callback = onLine
                return true
            }
            override suspend fun sendLine(line: String): Boolean {
                val request = Json.decodeFromString<JsonRpcRequest>(line)
                when (request.method) {
                    "initialize" -> callback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"mock","version":"1"}}}""")
                    "tools/list" -> callback?.invoke("""{"jsonrpc":"2.0","id":${request.id},"result":{"tools":[]}}""")
                    "tools/call" -> { toolCalls++; started.complete(Unit) }
                }
                return true
            }
            override suspend fun close() { }
        }
        val client = McpClient(McpServerConfig(id = "m", name = "Mock", transportType = McpTransportType.STDIO, command = "mock")) { bridge }
        assertTrue(client.connect())
        val pending = async { client.callTool("write", "{}") }
        withTimeout(5_000) { started.await(); client.close() }
        assertTrue(withTimeout(5_000) { pending.await() }.isError)
        assertTrue(client.callTool("write", "{}").isError)
        assertEquals(1, toolCalls, "a closed client must not send another tool request")
    }

    @Test fun shutdownCancelsConnectingClientsAndRejectsNewConnections() = runBlocking {
        val preferences = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val appPreferences = AppPreferences(preferences)
        val config = McpServerConfig(id = "mock", name = "Mock", transportType = McpTransportType.STDIO, command = "mock")
        appPreferences.setMcpServersJson(Json.encodeToString(ListSerializer(McpServerConfig.serializer()), listOf(config)))
        val started = CompletableDeferred<Unit>()
        var closed = 0
        val bridge = object : McpProcessBridge {
            override suspend fun startProcess(command: String, env: Map<String, String>, onLine: (String) -> Unit,
                onError: (String) -> Unit, onClose: (Int) -> Unit): Boolean {
                started.complete(Unit)
                return true
            }
            override suspend fun sendLine(line: String) = true
            override suspend fun close() { closed++ }
        }
        val manager = McpManager(appPreferences) { bridge }
        withTimeout(5_000) { started.await(); manager.shutdown() }
        assertEquals(1, closed)
        assertTrue(manager.activeTools.value.isEmpty())
        assertFailsWith<IllegalStateException> { manager.saveServers(listOf(config)) }
        manager.shutdown()
        assertEquals(1, closed)
    }
}
