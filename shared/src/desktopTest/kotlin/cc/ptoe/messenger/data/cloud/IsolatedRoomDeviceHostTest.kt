/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.room.AgentRow
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.ModelRow
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.data.local.room.ProjectRow
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.repository.room.KotlinRoomBackendSlice
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IsolatedRoomDeviceHostTest {
    @Test fun revokingRemotePromptWaitsForProviderCleanupAndDoesNotRetry(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-remote-prompt-revoke")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        val entered = CompletableDeferred<Unit>()
        val stopping = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val provider = KotlinTurnProvider { _, _ -> flow {
            calls.incrementAndGet(); entered.complete(Unit)
            try { awaitCancellation() } finally { stopping.complete(Unit); withContext(NonCancellable) { release.await() } }
        } }
        Relay().use { relay ->
            try {
                db.rows().upsertValues(listOf(
                    KeyValueRow("cloud_session", "messenger_session=one"), KeyValueRow("cloud_session_host", relay.base),
                    KeyValueRow("cloud_user_json", """{"id":"account"}"""), KeyValueRow("device_id", "device-1"),
                    KeyValueRow("device_control_grant", """["${relay.base}","account"]"""),
                    KeyValueRow("device_control_config", config(true).toString()), KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, "1"),
                ))
                db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "key", "chat", false, 1, 1)))
                db.rows().upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 1000, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
                db.rows().upsertAgents(listOf(AgentRow("a", "Agent", null, "prompt", "", "m", 0.7, 1.0, null, null,
                    true, false, false, false, false, false, false, null, null, null, "chat", true, false, "", 1, 1)))
                db.rows().upsertProjects(listOf(ProjectRow("project", "Project", "/workspace", "device-1", 1, 1)))
                db.rows().upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", "project", null,
                    null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
                lifecycle.enter()
                val prefs = MemoryPreferences()
                val slice = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs),
                    Files.createDirectory(dir.resolve("assets"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))),
                    dir.resolve("avatars"), dir.resolve("images"), defaultServer = relay.base, catalogUrl = "${relay.base}/models.json",
                    deviceHost = IsolatedRoomDeviceHostConfig { "Phone" }, turnProvider = provider)
                eventually { relay.packets.any { it.second["enabled"]?.jsonPrimitive?.content == "true" } }
                relay.command("messenger_session=one", buildJsonObject { put("action", "prompt"); put("sessionId", "c"); put("text", "hello") })
                withTimeout(8_000) { entered.await() }
                val session = checkNotNull(db.cloudSync().currentSession())
                val authority = KotlinRemoteControlScope("account", session.generation, relay.base, "device-1")
                RoomDeviceControlGrantStore(db).save(authority, 1, config(false))
                withTimeout(8_000) { stopping.await() }
                val local = TurnConfigBridge("c", "model", "", "", "chat", "", null, null, null, null,
                    emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "next",
                    disableTools = true, supportsToolApproval = true)
                assertFailsWith<IllegalStateException> { slice.bridge.startBackgroundTurn(local) }
                assertEquals(1, calls.get())
                release.complete(Unit)
                withTimeout(8_000) { while (slice.bridge.hasActiveTasks()) delay(10) }
                assertEquals(1, calls.get())
                assertEquals(1, db.rows().messagesFor(listOf("c")).count { it.role == "user" })
                slice.bridge.startBackgroundTurn(local)
                withTimeout(8_000) { while (calls.get() != 2) delay(10) }
                slice.bridge.cancelBackgroundTurn("c")
                withTimeout(8_000) { while (slice.bridge.hasActiveTasks()) delay(10) }
            } finally {
                release.complete(Unit)
                if (lifecycle.state.value == cc.ptoe.messenger.data.migration.RoomShutdownState.Running) lifecycle.stopAndAwait()
                db.close(); dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun remoteTextPromptRunsOnOwnedConversationAndRejectsReplayedIdentityAfterRestart(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-remote-prompt")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val provider = KotlinTurnProvider { _, request -> flow {
            check(request.tools.isNullOrEmpty())
            calls.incrementAndGet()
            emit(cc.ptoe.messenger.data.remote.sse.ChatStreamEvent.Content("answer"))
            emit(cc.ptoe.messenger.data.remote.sse.ChatStreamEvent.Done("stop"))
        } }
        Relay().use { relay ->
            var lifecycle = RoomManagedShutdown { }
            try {
                db.rows().upsertValues(listOf(
                    KeyValueRow("cloud_session", "messenger_session=one"), KeyValueRow("cloud_session_host", relay.base),
                    KeyValueRow("cloud_user_json", """{"id":"account"}"""), KeyValueRow("device_id", "device-1"),
                    KeyValueRow("device_control_grant", """["${relay.base}","account"]"""),
                    KeyValueRow("device_control_config", config(true).toString()), KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, "1"),
                ))
                db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "key", "chat", false, 1, 1)))
                db.rows().upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 1000, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
                db.rows().upsertAgents(listOf(AgentRow("a", "Agent", null, "prompt", "", "m", 0.7, 1.0, null, null,
                    true, false, false, false, false, false, false, null, null, null, "chat", true, false, "", 1, 1)))
                db.rows().upsertProjects(listOf(ProjectRow("project", "Project", "/workspace", "device-1", 1, 1)))
                db.rows().upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", "project", null,
                    null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
                val assets = Files.createDirectory(dir.resolve("assets"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                suspend fun attach(): KotlinRoomBackendSlice {
                    lifecycle.enter()
                    val prefs = MemoryPreferences()
                    return KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs), assets,
                        dir.resolve("avatars"), dir.resolve("images"), defaultServer = relay.base, catalogUrl = "${relay.base}/models.json",
                        deviceHost = IsolatedRoomDeviceHostConfig { "Phone" }, turnProvider = provider)
                }
                attach()
                eventually { relay.packets.any { it.second["enabled"]?.jsonPrimitive?.content == "true" } }
                val id = UUID.randomUUID().toString()
                val payload = buildJsonObject { put("action", "prompt"); put("sessionId", "c"); put("text", "hello") }
                fun send() = relay.send("messenger_session=one", "controlRequest", """{"id":"$id","payload":$payload}""")
                fun finalReply() = relay.packets.lastOrNull { it.second["id"]?.jsonPrimitive?.content == id &&
                    it.second["done"]?.jsonPrimitive?.content == "true" }?.second
                send()
                eventually { finalReply()?.get("payload")?.jsonObject?.get("type")?.jsonPrimitive?.content == "Finished" }
                assertEquals(listOf("hello", "answer"), db.rows().messagesFor(listOf("c")).map { it.content })
                assertEquals(1, calls.get())
                lifecycle.stopAndAwait()
                lifecycle = RoomManagedShutdown { }
                val previousConnections = relay.connections.size
                attach()
                eventually { relay.connections.size > previousConnections && relay.packets.count { it.second["enabled"]?.jsonPrimitive?.content == "true" } >= 2 }
                val priorCount = relay.packets.count { it.second["id"]?.jsonPrimitive?.content == id && it.second["done"]?.jsonPrimitive?.content == "true" }
                send()
                eventually { relay.packets.count { it.second["id"]?.jsonPrimitive?.content == id && it.second["done"]?.jsonPrimitive?.content == "true" } > priorCount }
                assertEquals("Error", finalReply()!!.getValue("payload").jsonObject.getValue("type").jsonPrimitive.content)
                assertEquals(1, calls.get())
                assertEquals(listOf("hello", "answer"), db.rows().messagesFor(listOf("c")).map { it.content })
            } finally {
                if (lifecycle.state.value == cc.ptoe.messenger.data.migration.RoomShutdownState.Running) lifecycle.stopAndAwait()
                db.close(); dir.toFile().deleteRecursively()
            }
        }
    }

    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    private class Relay(private val rejectEvents: Boolean = false) : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newCachedThreadPool()
        private val releaseStreams = CountDownLatch(1)
        val releaseReply = CountDownLatch(1)
        val blockReply = AtomicBoolean(false)
        val replyEntered = CompletableDeferred<Unit>()
        val packets = ConcurrentLinkedQueue<Pair<String, JsonObject>>()
        val connections = ConcurrentLinkedQueue<String>()
        private val streams = java.util.concurrent.ConcurrentHashMap<String, OutputStream>()
        val base get() = "http://127.0.0.1:${server.address.port}"
        init {
            server.executor = executor
            server.createContext("/api/remote/events") { exchange ->
                val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
                connections.add(cookie)
                if (rejectEvents) { exchange.sendResponseHeaders(401, -1); exchange.close() }
                else {
                    try {
                        exchange.sendResponseHeaders(200, 0)
                        streams[cookie] = exchange.responseBody
                        send(cookie, "controlReady", """{"connectionId":"${UUID.randomUUID()}"}""")
                        releaseStreams.await(20, TimeUnit.SECONDS)
                    } catch (_: Exception) { } finally { exchange.close() }
                }
            }
            server.createContext("/api/remote/control") { exchange ->
                try {
                    val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
                    val packet = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                    packets.add(cookie to packet)
                    if (packet["kind"]?.jsonPrimitive?.content == "reply" && blockReply.get()) {
                        replyEntered.complete(Unit)
                        releaseReply.await(15, TimeUnit.SECONDS)
                    }
                    exchange.sendResponseHeaders(202, -1)
                } catch (_: Exception) { } finally { exchange.close() }
            }
            server.createContext("/api/auth/login") { exchange ->
                val body = """{"user":{"id":"account"}}""".toByteArray()
                exchange.responseHeaders.add("Set-Cookie", "messenger_session=two; Path=/")
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
        }
        fun send(cookie: String, event: String, data: String) {
            val out = checkNotNull(streams[cookie])
            synchronized(out) { out.write("event: $event\ndata: $data\n\n".toByteArray()); out.flush() }
        }
        fun command(cookie: String, action: String): String = UUID.randomUUID().toString().also {
            send(cookie, "controlRequest", """{"id":"$it","payload":{"action":"$action"}}""")
        }
        fun command(cookie: String, payload: JsonObject): String = UUID.randomUUID().toString().also {
            send(cookie, "controlRequest", """{"id":"$it","payload":$payload}""")
        }
        fun reply(id: String) = packets.firstOrNull { it.second["id"]?.jsonPrimitive?.content == id }?.second
        override fun close() { releaseReply.countDown(); releaseStreams.countDown(); server.stop(0); executor.shutdownNow() }
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(8_000) { while (!condition()) delay(10) }
    private fun config(enabled: Boolean) = buildJsonObject { put("enabled", enabled); put("name", "Phone") }

    private suspend fun open(db: LocalDatabase, dir: java.nio.file.Path, relay: Relay, lifecycle: RoomManagedShutdown): RoomCloudFacade {
        db.rows().upsertValues(listOf(
            KeyValueRow("cloud_session", "messenger_session=one"), KeyValueRow("cloud_session_host", relay.base),
            KeyValueRow("cloud_user_json", """{"id":"account"}"""), KeyValueRow("device_id", "device-1"),
            KeyValueRow("device_control_grant", """["${relay.base}","account"]"""),
            KeyValueRow("device_control_config", config(true).toString()), KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, "1"),
        ))
        val transport = RoomCloudHttpClient()
        lifecycle.registerStop { transport.close() }
        return RoomCloudFacade.open(db, AppPreferences(MemoryPreferences()), transport,
            RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")), relay.base,
            onCreated = { facade -> lifecycle.registerStop { facade.shutdown() } })
    }

    @Test fun revokeFencesRepliesAndSessionReplacementAndShutdownStopTheOldHost(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-device-composition")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        Relay().use { relay ->
            try {
                lifecycle.enter()
                val cloud = open(db, dir, relay, lifecycle)
                val other = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("other.db").toString()).setDriver(BundledSQLiteDriver()).build()
                try {
                    assertFailsWith<IllegalStateException> {
                        IsolatedRoomDeviceHost.attach(other, cloud, lifecycle, IsolatedRoomDeviceHostConfig { it })
                    }
                } finally { other.close() }
                IsolatedRoomDeviceHost.attach(db, cloud, lifecycle, IsolatedRoomDeviceHostConfig { "Messenger $it" })
                eventually { relay.packets.any { it.first == "messenger_session=one" && it.second["enabled"]?.jsonPrimitive?.content == "true" } }
                val unsupported = relay.command("messenger_session=one", "create")
                eventually { relay.reply(unsupported) != null }
                assertEquals("unavailable", relay.reply(unsupported)!!.getValue("payload").jsonObject.getValue("code").jsonPrimitive.content)

                relay.blockReply.set(true)
                relay.command("messenger_session=one", "catalog")
                withTimeout(8_000) { relay.replyEntered.await() }
                val session = checkNotNull(db.cloudSync().currentSession())
                val grant = RoomDeviceControlGrantStore(db)
                val scope = KotlinRemoteControlScope("account", session.generation, relay.base, "device-1")
                val revoke = async { grant.save(scope, 1, config(false)) }
                delay(100)
                assertFalse(revoke.isCompleted)
                relay.releaseReply.countDown()
                assertEquals(2L, withTimeout(8_000) { revoke.await() })
                eventually { relay.packets.any { it.first == "messenger_session=one" && it.second["enabled"]?.jsonPrimitive?.content == "false" } }
                grant.save(scope, 2, config(true))
                eventually { relay.packets.count { it.first == "messenger_session=one" && it.second["enabled"]?.jsonPrimitive?.content == "true" } >= 2 }
                relay.blockReply.set(false)
                cloud.login("account@example.com", "password", null)
                eventually { relay.packets.any { it.first == "messenger_session=two" && it.second["enabled"]?.jsonPrimitive?.content == "true" } }
                val replacement = relay.command("messenger_session=two", "catalog")
                eventually { relay.reply(replacement) != null }
                assertEquals("Catalog", relay.reply(replacement)!!.getValue("payload").jsonObject.getValue("type").jsonPrimitive.content)
                withTimeout(8_000) { lifecycle.stopAndAwait() }
                val oldCount = relay.packets.size
                runCatching { relay.command("messenger_session=two", "catalog") }
                delay(200)
                assertEquals(oldCount, relay.packets.size)
                assertEquals(1, relay.connections.count { it == "messenger_session=one" })
                assertEquals(1, relay.connections.count { it == "messenger_session=two" })
            } finally {
                relay.releaseReply.countDown()
                if (lifecycle.state.value == cc.ptoe.messenger.data.migration.RoomShutdownState.Running) lifecycle.stopAndAwait()
                db.close(); dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun relayUnauthorizedExpiresTheSameFacadeSessionAndDoesNotReconnect(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-device-expiry")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        Relay(rejectEvents = true).use { relay ->
            try {
                lifecycle.enter()
                val cloud = open(db, dir, relay, lifecycle)
                IsolatedRoomDeviceHost.attach(db, cloud, lifecycle, IsolatedRoomDeviceHostConfig { it })
                withTimeout(8_000) { cloud.sessionExpired.first { it } }
                assertNull(cloud.user.first())
                assertNull(db.cloudSync().currentSession())
                delay(200)
                assertEquals(listOf("messenger_session=one"), relay.connections.toList())
                withTimeout(8_000) { lifecycle.stopAndAwait() }
            } finally {
                if (lifecycle.state.value == cc.ptoe.messenger.data.migration.RoomShutdownState.Running) lifecycle.stopAndAwait()
                db.close(); dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun remoteCancelStopsOnlyTheOwnedTurnAndWaitsForWorkerCleanup(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-remote-cancel")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        val started = CompletableDeferred<Unit>()
        val stopping = CompletableDeferred<Unit>()
        val allowStop = CompletableDeferred<Unit>()
        val provider = KotlinTurnProvider { _, _ -> flow {
            try { started.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { stopping.complete(Unit); allowStop.await() } }
        } }
        Relay().use { relay ->
            try {
                db.rows().upsertValues(listOf(
                    KeyValueRow("cloud_session", "messenger_session=one"), KeyValueRow("cloud_session_host", relay.base),
                    KeyValueRow("cloud_user_json", """{"id":"account"}"""), KeyValueRow("device_id", "device-1"),
                    KeyValueRow("device_control_grant", """["${relay.base}","account"]"""),
                    KeyValueRow("device_control_config", config(true).toString()), KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, "1"),
                ))
                db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "key", "chat", false, 1, 1)))
                db.rows().upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 1000, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
                db.rows().upsertAgents(listOf(AgentRow("a", "Agent", null, "prompt", "", "m", 0.7, 1.0, null, null,
                    true, false, false, false, false, false, false, null, null, null, "chat", false, false, "", 1, 1)))
                db.rows().upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null,
                    null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
                lifecycle.enter()
                val prefs = MemoryPreferences()
                val slice = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs),
                    Files.createDirectory(dir.resolve("assets"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))),
                    dir.resolve("avatars"), dir.resolve("images"),
                    defaultServer = relay.base, catalogUrl = "${relay.base}/models.json",
                    deviceHost = IsolatedRoomDeviceHostConfig { "Phone" }, turnProvider = provider)
                eventually { relay.packets.any { it.second["enabled"]?.jsonPrimitive?.content == "true" } }
                val turn = TurnConfigBridge("c", "model", "", "", "chat", "", null, null, null, null,
                    emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "hello", disableTools = true)
                slice.bridge.startBackgroundTurn(turn)
                withTimeout(8_000) { started.await() }
                val active = checkNotNull(db.rows().getValue("kotlin_turn_active_v1:c")).value
                fun cancel(id: String) = buildJsonObject { put("action", "notificationCancel"); put("sessionId", "c"); put("requestId", id) }
                val stale = relay.command("messenger_session=one", cancel("stale"))
                eventually { relay.reply(stale) != null }
                assertEquals("Error", relay.reply(stale)!!.getValue("payload").jsonObject.getValue("type").jsonPrimitive.content)
                assertFalse(stopping.isCompleted)
                val request = relay.command("messenger_session=one", cancel(active))
                withTimeout(8_000) { stopping.await() }
                assertNull(relay.reply(request))
                allowStop.complete(Unit)
                eventually { relay.reply(request) != null }
                assertEquals("CancelAccepted", relay.reply(request)!!.getValue("payload").jsonObject.getValue("type").jsonPrimitive.content)
                assertNull(db.rows().getValue("kotlin_turn_active_v1:c"))
                withTimeout(8_000) { lifecycle.stopAndAwait() }
            } finally {
                allowStop.complete(Unit)
                if (lifecycle.state.value == cc.ptoe.messenger.data.migration.RoomShutdownState.Running) lifecycle.stopAndAwait()
                db.close(); dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun remoteDecisionUsesLiveLocalOwnerAndRejectsStaleCalls(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-remote-approval")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        val providerCalls = java.util.concurrent.atomic.AtomicInteger()
        val provider = KotlinTurnProvider { _, _ -> flow {
            if (providerCalls.getAndIncrement() % 2 == 0) emit(cc.ptoe.messenger.data.remote.sse.ChatStreamEvent.Done("tool_calls",
                toolCalls = listOf(ToolCallData("call-1", "messenger_learning", """{"action":"upsert","record":{"id":"note","title":"Notes","source":{"kind":"manual","snapshot":"source"},"content":{"kind":"item","itemKind":"note"}}}"""))))
            else emit(cc.ptoe.messenger.data.remote.sse.ChatStreamEvent.Done("stop"))
        } }
        Relay().use { relay ->
            try {
                db.rows().upsertValues(listOf(
                    KeyValueRow("cloud_session", "messenger_session=one"), KeyValueRow("cloud_session_host", relay.base),
                    KeyValueRow("cloud_user_json", """{"id":"account"}"""), KeyValueRow("device_id", "device-1"),
                    KeyValueRow("device_control_grant", """["${relay.base}","account"]"""),
                    KeyValueRow("device_control_config", config(true).toString()), KeyValueRow(REMOTE_CONTROL_EPOCH_KEY, "1"),
                    KeyValueRow("learning_tools_enabled", "true"),
                ))
                db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "key", "chat", false, 1, 1)))
                db.rows().upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 1000, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
                db.rows().upsertAgents(listOf(AgentRow("a", "Agent", null, "prompt", "", "m", 0.7, 1.0, null, null,
                    true, false, false, false, false, false, false, null, null, null, "chat", true, false, "", 1, 1)))
                db.rows().upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null,
                    null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
                lifecycle.enter()
                val prefs = MemoryPreferences()
                val slice = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs),
                    Files.createDirectory(dir.resolve("assets"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))),
                    dir.resolve("avatars"), dir.resolve("images"), defaultServer = relay.base, catalogUrl = "${relay.base}/models.json",
                    deviceHost = IsolatedRoomDeviceHostConfig { "Phone" }, turnProvider = provider)
                eventually { relay.packets.any { it.second["enabled"]?.jsonPrimitive?.content == "true" } }
                val turn = TurnConfigBridge("c", "model", "", "", "chat", "", null, null, null, null,
                    emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "hello",
                    disableTools = false, supportsToolApproval = true)
                slice.bridge.startBackgroundTurn(turn)
                val approval = CompletableDeferred<Unit>()
                val observer = async { slice.bridge.observeBackgroundTurn("c") { event ->
                    if (Json.parseToJsonElement(event).jsonObject["type"]?.jsonPrimitive?.content == "ToolApprovalRequested") approval.complete(Unit)
                } }
                withTimeout(8_000) { approval.await() }
                val active = checkNotNull(db.rows().getValue("kotlin_turn_active_v1:c")).value
                fun decision(turnId: String, option: String) = buildJsonObject {
                    put("action", "notificationDecision"); put("sessionId", "c"); put("requestId", turnId); put("optionId", option)
                }
                for ((id, option) in listOf("stale" to "deny:call-1", active to "deny:other")) {
                    val request = relay.command("messenger_session=one", decision(id, option))
                    eventually { relay.reply(request) != null }
                    assertEquals("Error", relay.reply(request)!!.getValue("payload").jsonObject.getValue("type").jsonPrimitive.content)
                    assertEquals(1, providerCalls.get())
                }
                val request = relay.command("messenger_session=one", decision(active, "deny:call-1"))
                eventually { relay.reply(request) != null }
                assertEquals("DecisionAccepted", relay.reply(request)!!.getValue("payload").jsonObject.getValue("type").jsonPrimitive.content)
                withTimeout(8_000) { observer.await() }
                assertEquals(2, providerCalls.get())
                assertFalse(db.rows().messagesFor(listOf("c")).none { it.role == "tool" && it.content.contains("denied", ignoreCase = true) })

                // A second actual turn proves that a valid remote approval reaches the running
                // tool and that the same decision cannot be accepted or executed twice.
                assertFailsWith<IllegalArgumentException> { slice.learning.get("item", "note") }
                withTimeout(8_000) { while (slice.bridge.hasActiveTasks()) delay(10) }
                slice.bridge.startBackgroundTurn(turn)
                val secondApproval = CompletableDeferred<Unit>()
                val secondObserver = async { slice.bridge.observeBackgroundTurn("c") { event ->
                    if (Json.parseToJsonElement(event).jsonObject["type"]?.jsonPrimitive?.content == "ToolApprovalRequested") secondApproval.complete(Unit)
                } }
                withTimeout(8_000) { secondApproval.await() }
                val secondTurn = checkNotNull(db.rows().getValue("kotlin_turn_active_v1:c")).value
                val approveRequest = relay.command("messenger_session=one", decision(secondTurn, "approve:call-1"))
                eventually { relay.reply(approveRequest) != null }
                assertEquals("DecisionAccepted", relay.reply(approveRequest)!!.getValue("payload").jsonObject.getValue("type").jsonPrimitive.content)
                withTimeout(8_000) { secondObserver.await() }
                assertEquals("Notes", slice.learning.get("item", "note").title)
                assertEquals(4, providerCalls.get())
                val repeated = relay.command("messenger_session=one", decision(secondTurn, "approve:call-1"))
                eventually { relay.reply(repeated) != null }
                assertEquals("Error", relay.reply(repeated)!!.getValue("payload").jsonObject.getValue("type").jsonPrimitive.content)
                assertEquals(2, db.rows().messagesFor(listOf("c")).count { it.role == "tool" })
                assertEquals(4, providerCalls.get())
                lifecycle.stopAndAwait()
            } finally {
                if (lifecycle.state.value == cc.ptoe.messenger.data.migration.RoomShutdownState.Running) lifecycle.stopAndAwait()
                db.close(); dir.toFile().deleteRecursively()
            }
        }
    }
}
