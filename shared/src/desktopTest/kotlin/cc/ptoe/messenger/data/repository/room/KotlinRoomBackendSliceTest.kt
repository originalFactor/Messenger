/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.data.local.image.ImageGenerationAuthorization
import cc.ptoe.messenger.data.local.image.ImageJobStatus
import cc.ptoe.messenger.data.local.image.ImageProfile
import cc.ptoe.messenger.data.local.image.ImageRequest
import cc.ptoe.messenger.data.local.image.PriorImageWorkersStopped
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.schedule.KotlinScheduledTask
import cc.ptoe.messenger.data.local.schedule.RoomScheduledTasks
import cc.ptoe.messenger.data.local.schedule.ScheduledRecoveryBarrier
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.data.migration.RoomShutdownState
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.ChatModel
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.model.Provider
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinRoomBackendSliceTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun cancelledCallerStillJoinsItsChildCleanupBeforeOwnerStops(): Unit = runBlocking {
        val calls = PartialChatCalls()
        val childStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val caller = async {
            calls.run { coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try { childStarted.complete(Unit); awaitCancellation() }
                    finally { withContext(NonCancellable) { cleanupStarted.complete(Unit); allowCleanup.await() } }
                }
                awaitCancellation()
            } }
        }
        withTimeout(5_000) { childStarted.await() }
        caller.cancel()
        withTimeout(5_000) { cleanupStarted.await() }
        val stopping = async(start = CoroutineStart.UNDISPATCHED) { calls.stopAndJoin() }
        assertFalse(stopping.isCompleted)
        assertFailsWith<IllegalStateException> { calls.run { } }
        allowCleanup.complete(Unit)
        withTimeout(5_000) { stopping.await(); caller.join() }
    }

    @Test fun repositoriesChatAndAssetsShareOneOwnerAndShutdownJoinsWorkers(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-backend-slice")
        val assetRoot = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val requests = AtomicInteger()
        val waiting = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            requests.incrementAndGet()
            val request = exchange.requestBody.bufferedReader().readText()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            if (request.contains("hold the response")) {
                try {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"pending\"}}]}\n\n".toByteArray())
                    exchange.responseBody.flush()
                    waiting.complete(Unit)
                    release.await(10, TimeUnit.SECONDS)
                } catch (_: Exception) { } finally { exchange.close() }
            } else {
                val bytes = ("data: {\"choices\":[{\"delta\":{\"content\":\"reply\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n").toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        val lifecycle = RoomManagedShutdown { }
        val preferences = MemoryPreferences()
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val base = "http://127.0.0.1:${server.address.port}"
            val backend = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(preferences), ThemePreferences(preferences),
                assetRoot, dir.resolve("avatars"), dir.resolve("chat-images"), base, "$base/models.json")
            val provider = Provider("p", "Provider", "$base/v1", "key", createdAt = 1, updatedAt = 1)
            backend.providers.insert(provider)
            backend.models.insert(ChatModel("m", "p", "model", "Model", true, createdAt = 1))
            backend.conversations.insert(Conversation("c", "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            fun config(text: String, noTools: Boolean = true) = TurnConfigBridge("c", "model", "", "", "chat", "", null,
                null, null, null, emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = text,
                disableTools = noTools)
            assertFailsWith<IllegalStateException> { backend.bridge.startBackgroundTurn(config("requires model")) }
            assertTrue(backend.messages.getByConversationId("c").first().isEmpty())
            assertFailsWith<IllegalArgumentException> { backend.bridge.startBackgroundTurn(config("no tool host", false).copy(hasWorkspace = true)) }
            assertTrue(backend.messages.getByConversationId("c").first().isEmpty())
            val agent = backend.agents.getDefaultAgent().first()!!
            backend.agents.update(agent.copy(defaultModelId = "m"))
            backend.agents.delete("a")
            assertEquals("a", backend.agents.getDefaultAgent().first()?.id)
            backend.currentAgent.setCurrentAgentId("a")
            assertEquals("a", backend.bridge.kvGet("current_agent_id"))

            val bytes = "owned learning asset".toByteArray()
            val assetId = backend.learning.importAsset("lesson", "application/octet-stream", Base64.getEncoder().encodeToString(bytes))
            assertContentEquals(bytes, backend.bridge.learningAsset(assetId))

            backend.bridge.startBackgroundTurn(config("hello"))
            val events = mutableListOf<String>()
            withTimeout(10_000) { backend.bridge.observeBackgroundTurn("c") { events += it } }
            assertTrue(events.any { Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content == "Finished" })
            assertEquals(listOf("hello", "reply"), backend.messages.getByConversationId("c").first().map { it.content })
            assertEquals("reply", backend.conversations.getById("c").first()?.lastMessage)
            assertEquals(1, requests.get())

            backend.bridge.startBackgroundTurn(config("hold the response"))
            withTimeout(5_000) { waiting.await() }
            val closing = async { lifecycle.stopAndAwait() }
            withTimeout(10_000) { closing.await() }
            assertFailsWith<IllegalStateException> { backend.bridge.startBackgroundTurn(config("after shutdown")) }
            assertFailsWith<IllegalStateException> { backend.assetGate.withExclusiveAssets { } }
            assertEquals("sent", db.rows().messagesFor(listOf("c")).last().status)
            assertEquals(null, db.rows().getValue("kotlin_turn_active_v1:c"))
            assertEquals(2, requests.get())
        } finally {
            release.countDown()
            db.close()
            server.stop(0)
            executor.shutdownNow()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun approvedLearningToolRunsThroughRoomAndRealProviderTurn(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-backend-tool")
        val assetRoot = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val requests = AtomicInteger()
        val payloads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val approved = CompletableDeferred<Unit>()
        val toolArguments = """{"action":"upsert","record":{"id":"note","title":"Notes","source":{"kind":"manual","snapshot":"source"},"content":{"kind":"item","itemKind":"note"}}}"""
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            payloads.add(exchange.requestBody.bufferedReader().readText())
            val bytes = if (requests.incrementAndGet() == 1) {
                val encoded = Json.encodeToString(toolArguments)
                ("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"messenger_learning\",\"arguments\":$encoded}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                    "data: [DONE]\n\n").toByteArray()
            } else {
                ("data: {\"choices\":[{\"delta\":{\"content\":\"saved\"},\"finish_reason\":\"stop\"}]}\n\n" + "data: [DONE]\n\n").toByteArray()
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val lifecycle = RoomManagedShutdown { }
        val preferences = MemoryPreferences()
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m",
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            db.rows().upsertValues(listOf(KeyValueRow("learning_tools_enabled", "true")))
            lifecycle.enter()
            val base = "http://127.0.0.1:${server.address.port}"
            val backend = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(preferences), ThemePreferences(preferences),
                assetRoot, dir.resolve("avatars"), dir.resolve("chat-images"), base, "$base/models.json")
            backend.providers.insert(Provider("p", "Provider", "$base/v1", "key", createdAt = 1, updatedAt = 1))
            backend.models.insert(ChatModel("m", "p", "model", "Model", true, supportsToolCalling = true, createdAt = 1))
            backend.conversations.insert(Conversation("c", "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            val config = TurnConfigBridge("c", "model", "", "", "chat", "", null, null, null, null,
                emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "save a note",
                disableTools = false, supportsToolApproval = true)
            backend.bridge.startBackgroundTurn(config)
            val observer = async {
                backend.bridge.observeBackgroundTurn("c") { event ->
                    val type = Json.parseToJsonElement(event).jsonObject["type"]?.jsonPrimitive?.content
                    if (type == "ToolApprovalRequested") approved.complete(Unit)
                }
            }
            withTimeout(10_000) { approved.await() }
            assertFailsWith<IllegalArgumentException> { backend.learning.get("item", "note") }
            backend.bridge.respondToolApproval("call-1", true)
            withTimeout(10_000) { observer.await() }
            assertEquals("Notes", backend.learning.get("item", "note").title)
            assertEquals(2, requests.get())
            assertTrue(payloads.first().contains("messenger_learning"))
            assertTrue(payloads.last().contains("call-1"))
            withTimeout(10_000) { lifecycle.stopAndAwait() }
            assertFailsWith<IllegalStateException> { backend.bridge.startBackgroundTurn(config) }
        } finally {
            db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun schedulerRequiresProofAndEnforcesTaskCeilingsAndCancellation(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-backend-schedule")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val requests = AtomicInteger()
        val payloads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val cancelEntered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            val request = exchange.requestBody.bufferedReader().readText()
            payloads.add(request)
            val number = requests.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            if (request.contains("cancel-me")) {
                try {
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"pending\"}}]}\n\n".toByteArray())
                    exchange.responseBody.flush(); cancelEntered.complete(Unit)
                    release.await(10, TimeUnit.SECONDS)
                } catch (_: Exception) { } finally { exchange.close() }
            } else {
                val encoded = Json.encodeToString("""{"action":"overview"}""")
                val response = if (number == 1) {
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"scheduled-call\",\"type\":\"function\",\"function\":{\"name\":\"messenger_learning\",\"arguments\":$encoded}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                } else "data: {\"choices\":[{\"delta\":{\"content\":\"scheduled answer\"},\"finish_reason\":\"stop\"}]}\n\n"
                val bytes = (response + "data: [DONE]\n\n").toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        val lifecycle = RoomManagedShutdown { }
        val prefs = MemoryPreferences()
        val proofCount = AtomicInteger()
        val allowedId = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
        val deniedId = "a8406500-b75a-4d4a-a895-493521a13d23"
        val cancelId = "05800b51-64db-48ae-a5a2-d56d16ac1234"
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            db.rows().upsertProviders(listOf(cc.ptoe.messenger.data.local.room.ProviderRow("p", "Provider", "$base/v1", "key", "chat", false, 1, 1)))
            db.rows().upsertModels(listOf(cc.ptoe.messenger.data.local.room.ModelRow("m", "p", "model", "Model", true, 0, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m",
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            val allowed = KotlinScheduledTask(allowedId, "Allowed", "summarize learning", "a", intervalMinutes = 1, nextRunAt = 1,
                allowedTools = listOf("messenger_learning"))
            val denied = KotlinScheduledTask(deniedId, "Denied", "use terminal", "a", intervalMinutes = 1, nextRunAt = 1,
                allowedTools = listOf("terminal"))
            db.rows().upsertValues(listOf(KeyValueRow("learning_tools_enabled", "true"), KeyValueRow("artifact_tools_enabled", "true"),
                KeyValueRow("scheduled_task:$allowedId", Json.encodeToString(allowed)), KeyValueRow("scheduled_task:$deniedId", Json.encodeToString(denied))))
            lifecycle.enter()
            val backend = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs), assets,
                dir.resolve("avatars"), dir.resolve("chat-images"), base, "$base/models.json", now = { 10_000 },
                schedulerRecovery = ScheduledRecoveryBarrier { proofCount.incrementAndGet() })
            val scheduler = checkNotNull(backend.scheduler)
            val taskStore = RoomScheduledTasks(db)
            withTimeout(10_000) {
                while (true) {
                    val tasks = taskStore.list()
                    if (tasks.all { it.lastRunAt != null } && tasks.all { taskStore.active(it.id) == null }) break
                    delay(20)
                }
            }
            val saved = taskStore.list().associateBy { it.id }
            assertEquals(null, saved.getValue(allowedId).lastError)
            assertTrue(saved.getValue(deniedId).lastError.orEmpty().contains("unavailable tools"))
            val conversationId = checkNotNull(saved.getValue(allowedId).lastConversationId)
            assertEquals("scheduled answer", db.rows().messagesFor(listOf(conversationId)).last().content)
            assertEquals(2, requests.get())
            assertTrue(payloads.first().contains("messenger_learning"))
            assertFalse(payloads.first().contains("messenger_artifacts"))
            assertEquals(1, proofCount.get())

            backend.agents.update(checkNotNull(backend.agents.getDefaultAgent().first()).copy(toolsEnabled = false))
            backend.bridge.scheduledTaskRequest("POST", "/scheduled-tasks",
                Json.encodeToString(KotlinScheduledTask(cancelId, "Cancel", "cancel-me", "a", intervalMinutes = 1, nextRunAt = 1)))
            scheduler.tick()
            withTimeout(10_000) { cancelEntered.await() }
            val cancelConversation = checkNotNull(taskStore.list().single { it.id == cancelId }.lastConversationId)
            backend.bridge.cancelBackgroundTurn(cancelConversation)
            withTimeout(10_000) { while (taskStore.active(cancelId) != null) delay(20) }
            assertEquals("Execution cancelled", taskStore.list().single { it.id == cancelId }.lastError)
            assertEquals(3, requests.get())
            withTimeout(10_000) { lifecycle.stopAndAwait() }
            assertFailsWith<IllegalStateException> { backend.bridge.scheduledTaskRequest("GET", "/scheduled-tasks") }
            assertFailsWith<IllegalStateException> { scheduler.tick() }
            assertFailsWith<IllegalStateException> { scheduler.start() }
        } finally { release.countDown(); db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively() }
    }

    @Test fun rejectedSchedulerRecoveryProofLeavesInterruptedClaimUntouched(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-backend-schedule-proof")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        val prefs = MemoryPreferences()
        val key = "kotlin_scheduled_active_v1:d3bc81a8-0589-49da-8cb1-8dc6a82507c6"
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            db.rows().upsertValues(listOf(KeyValueRow(key, "unrecovered")))
            lifecycle.enter()
            assertFailsWith<IllegalStateException> {
                KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs), assets,
                    dir.resolve("avatars"), dir.resolve("chat-images"), schedulerRecovery = ScheduledRecoveryBarrier { error("Previous worker may be alive") })
            }
            assertEquals("unrecovered", db.rows().getValue(key)?.value)
            withTimeout(10_000) { lifecycle.stopAndAwait() }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun imageJobsShareTheSliceAndShutdownJoinsTheirCleanup(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-backend-image")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val png = ByteArrayOutputStream().use { out ->
            check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out))
            out.toByteArray()
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val requests = AtomicInteger()
        server.executor = executor
        server.createContext("/v1/images/generations") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.readBytes()
            val body = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val lifecycle = RoomManagedShutdown { }
        val prefs = MemoryPreferences()
        val proofCount = AtomicInteger()
        val authorizations = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Boolean>()
        val allowCleanup = CompletableDeferred<Unit>()
        val recovery = IsolatedImageRecovery()
        var backend: KotlinRoomBackendSlice? = null
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val base = "http://127.0.0.1:${server.address.port}"
            val config = IsolatedImageJobsConfig(dir, PriorImageWorkersStopped { proofCount.incrementAndGet() },
                ImageGenerationAuthorization { request ->
                    authorizations.incrementAndGet()
                    if (request.requestId == "blocked") {
                        try { entered.complete(Unit); awaitCancellation() }
                        finally { withContext(NonCancellable) {
                            val gateAvailable = runCatching { checkNotNull(backend).assetGate.withExclusiveAssets { true } }.getOrDefault(false)
                            cleanupStarted.complete(gateAvailable)
                            allowCleanup.await()
                        } }
                    }
                }, recovery)
            val slice = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs), assets,
                dir.resolve("avatars"), dir.resolve("chat-images"), base, "$base/models.json", now = { 1000 }, imageJobs = config)
            backend = slice
            slice.providers.insert(Provider("p", "Images", "$base/v1", "key", createdAt = 1, updatedAt = 1))
            slice.bridge.learningRequest("PUT", "/image-generation/capability", """{"enabled":true}""")
            slice.bridge.learningRequest("PUT", "/image-generation/profiles/profile",
                Json.encodeToString(ImageProfile("profile", "Profile", "p", "image-model", enabled = true)))
            val body = """{"requestId":"finished","profileId":"profile","prompt":"draw"}"""
            val started = Json.parseToJsonElement(slice.bridge.learningRequest("POST", "/image-generation/jobs", body)).jsonObject
            val id = started.getValue("id").jsonPrimitive.content
            withTimeout(10_000) { while (checkNotNull(slice.imageOwner).hasActiveJobs()) delay(20) }
            val finished = db.imageJobs().read(id)
            assertEquals(ImageJobStatus.succeeded, finished.status)
            assertContentEquals(png, slice.bridge.learningAsset(finished.assetIds.single()))
            assertEquals(id, Json.parseToJsonElement(slice.bridge.learningRequest("POST", "/image-generation/jobs", body)).jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals(1, requests.get())
            assertEquals(1, proofCount.get())

            val blocked = async { slice.bridge.learningRequest("POST", "/image-generation/jobs",
                """{"requestId":"blocked","profileId":"profile","prompt":"draw"}""") }
            withTimeout(5_000) { entered.await() }
            assertTrue(slice.bridge.hasActiveTasks())
            val stopping = async { lifecycle.stopAndAwait() }
            assertTrue(withTimeout(5_000) { cleanupStarted.await() })
            assertFalse(stopping.isCompleted)
            allowCleanup.complete(Unit)
            withTimeout(10_000) { stopping.await(); blocked.join() }
            assertEquals(2, authorizations.get())
            assertEquals(1, requests.get())
            assertFailsWith<IllegalStateException> { slice.bridge.learningRequest("GET", "/image-generation/jobs") }
            assertFailsWith<IllegalStateException> { slice.assetGate.withExclusiveAssets { } }
        } finally {
            allowCleanup.complete(Unit)
            if (lifecycle.state.value == RoomShutdownState.Running) lifecycle.stopAndAwait()
            db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun rejectedImageRecoveryProofDoesNotAlterStoredJobs(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-backend-image-proof")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val lifecycle = RoomManagedShutdown { }
        val prefs = MemoryPreferences()
        val recovery = IsolatedImageRecovery()
        val key = "local_image_job:unrecovered"
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            db.rows().upsertValues(listOf(KeyValueRow(key, "unchanged")))
            lifecycle.enter()
            val config = IsolatedImageJobsConfig(dir, PriorImageWorkersStopped { error("Previous image worker may be alive") },
                ImageGenerationAuthorization { }, recovery)
            assertFailsWith<IllegalStateException> {
                KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs), assets,
                    dir.resolve("avatars"), dir.resolve("chat-images"), imageJobs = config)
            }
            assertEquals("unchanged", db.rows().getValue(key)?.value)
            assertFailsWith<IllegalStateException> { recovery.recoverAfterStop(PriorImageWorkersStopped { }) }
            withTimeout(10_000) { lifecycle.stopAndAwait() }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun imageRecoveryHandleSurvivesLaterBackendInitializationFailure(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-backend-image-handle")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        var safeToRelease = false
        val lifecycle = RoomManagedShutdown { check(safeToRelease) { "External workers may still be alive" } }
        val prefs = MemoryPreferences()
        val recovery = IsolatedImageRecovery()
        val proofs = AtomicInteger()
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = null,
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val images = IsolatedImageJobsConfig(dir, PriorImageWorkersStopped { proofs.incrementAndGet() },
                ImageGenerationAuthorization { }, recovery)
            assertFailsWith<IllegalStateException> {
                KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs), assets,
                    dir.resolve("avatars"), dir.resolve("chat-images"),
                    schedulerRecovery = ScheduledRecoveryBarrier { error("Previous scheduled worker may be alive") }, imageJobs = images)
            }
            assertEquals(1, proofs.get())
            assertFailsWith<IllegalStateException> { recovery.recoverAfterStop(PriorImageWorkersStopped { }) }
            val stopping = async { lifecycle.stopAndAwait() }
            withTimeout(10_000) { lifecycle.state.first { it is RoomShutdownState.RecoveryRequired } }
            recovery.recoverAfterStop(PriorImageWorkersStopped { proofs.incrementAndGet() })
            assertEquals(2, proofs.get())
            safeToRelease = true
            lifecycle.retryStop()
            withTimeout(10_000) { stopping.await() }
            assertFailsWith<IllegalStateException> { recovery.recoverAfterStop(PriorImageWorkersStopped { }) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun modelImageToolRequiresOptInAndApprovalAndUsesSeparateAuthorization(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-backend-model-image")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val png = ByteArrayOutputStream().use { out ->
            check(ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out)); out.toByteArray()
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val chatCalls = AtomicInteger(); val imageCalls = AtomicInteger()
        val uiAuthorizations = AtomicInteger(); val modelAuthorizations = AtomicInteger()
        val payloads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val approval = CompletableDeferred<Unit>()
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            payloads.add(exchange.requestBody.bufferedReader().readText())
            val number = chatCalls.incrementAndGet()
            val content = when (number) {
                2 -> {
                    val args = Json.encodeToString("""{"requestId":"model-request","profileId":"profile","prompt":"draw"}""")
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"image-call\",\"type\":\"function\",\"function\":{\"name\":\"messenger_generate_image\",\"arguments\":$args}}]},\"finish_reason\":\"tool_calls\"}]}\n\n"
                }
                else -> "data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n"
            }
            val bytes = (content + "data: [DONE]\n\n").toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/images/generations") { exchange ->
            imageCalls.incrementAndGet(); exchange.requestBody.readBytes()
            val body = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val lifecycle = RoomManagedShutdown { }
        val prefs = MemoryPreferences()
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m",
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val base = "http://127.0.0.1:${server.address.port}"
            val images = IsolatedImageJobsConfig(dir, PriorImageWorkersStopped { },
                ImageGenerationAuthorization { uiAuthorizations.incrementAndGet() }, IsolatedImageRecovery(),
                modelAuthorization = ImageGenerationAuthorization { modelAuthorizations.incrementAndGet() })
            val slice = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs), assets,
                dir.resolve("avatars"), dir.resolve("chat-images"), base, "$base/models.json", now = { 1000 }, imageJobs = images)
            slice.providers.insert(Provider("p", "Provider", "$base/v1", "key", createdAt = 1, updatedAt = 1))
            slice.models.insert(ChatModel("m", "p", "model", "Model", true, supportsToolCalling = true, createdAt = 1))
            slice.bridge.learningRequest("PUT", "/image-generation/profiles/profile",
                Json.encodeToString(ImageProfile("profile", "Profile", "p", "image-model", enabled = true)))
            for (id in listOf("disabled", "enabled")) slice.conversations.insert(
                Conversation(id, "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            fun config(id: String) = TurnConfigBridge(id, "model", "", "", "chat", "", null, null, null, null,
                emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "draw",
                disableTools = false, supportsToolApproval = true)

            slice.bridge.startBackgroundTurn(config("disabled"))
            withTimeout(10_000) { slice.bridge.observeBackgroundTurn("disabled") { } }
            assertFalse(payloads.first().contains("messenger_generate_image"))
            assertEquals(0, imageCalls.get())
            slice.bridge.learningRequest("PUT", "/image-generation/capability", """{"enabled":true}""")
            slice.bridge.startBackgroundTurn(config("enabled"))
            val events = mutableListOf<String>()
            val observer = async {
                slice.bridge.observeBackgroundTurn("enabled") { event ->
                    val type = Json.parseToJsonElement(event).jsonObject["type"]?.jsonPrimitive?.content
                    events += type.orEmpty()
                    if (type == "ToolApprovalRequested") approval.complete(Unit)
                }
            }
            withTimeout(10_000) { approval.await() }
            assertEquals(0, imageCalls.get())
            assertEquals(0, modelAuthorizations.get())
            slice.bridge.respondToolApproval("image-call", true)
            withTimeout(10_000) { observer.await() }
            assertTrue("Finished" in events)
            assertEquals(1, imageCalls.get())
            assertEquals(1, modelAuthorizations.get())
            assertEquals(0, uiAuthorizations.get())
            assertTrue(payloads.elementAt(1).contains("messenger_generate_image"))
            assertTrue(payloads.last().contains("image-call"))
            val job = db.imageJobs().jobs().single()
            assertEquals(ImageJobStatus.succeeded, job.status)
            assertContentEquals(png, slice.bridge.learningAsset(job.assetIds.single()))
            withTimeout(10_000) { lifecycle.stopAndAwait() }
        } finally {
            if (lifecycle.state.value == RoomShutdownState.Running) lifecycle.stopAndAwait()
            db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun modelImageStatusFailureRetainsOwnerUntilVerifiedRecovery(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-backend-model-image-recovery")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val imageCalls = AtomicInteger()
        val imageEntered = CompletableDeferred<Unit>()
        val approval = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readBytes()
            val args = Json.encodeToString("""{"requestId":"in-flight","profileId":"profile","prompt":"draw"}""")
            val response = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"image-fault\",\"type\":\"function\",\"function\":{\"name\":\"messenger_generate_image\",\"arguments\":$args}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" + "data: [DONE]\n\n"
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/images/generations") { exchange ->
            try {
                imageCalls.incrementAndGet(); exchange.requestBody.readBytes(); imageEntered.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
            } catch (_: Exception) { } finally { exchange.close() }
        }
        server.start()
        val lifecycle = RoomManagedShutdown { }
        val prefs = MemoryPreferences()
        val recovery = IsolatedImageRecovery()
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m",
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val base = "http://127.0.0.1:${server.address.port}"
            val images = IsolatedImageJobsConfig(dir, PriorImageWorkersStopped { }, ImageGenerationAuthorization { }, recovery,
                modelAuthorization = ImageGenerationAuthorization { })
            val slice = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs), assets,
                dir.resolve("avatars"), dir.resolve("chat-images"), base, "$base/models.json", now = { 1000 }, imageJobs = images)
            slice.providers.insert(Provider("p", "Provider", "$base/v1", "key", createdAt = 1, updatedAt = 1))
            slice.models.insert(ChatModel("m", "p", "model", "Model", true, supportsToolCalling = true, createdAt = 1))
            slice.conversations.insert(Conversation("c", "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            slice.bridge.learningRequest("PUT", "/image-generation/profiles/profile",
                Json.encodeToString(ImageProfile("profile", "Profile", "p", "image-model", enabled = true)))
            slice.bridge.learningRequest("PUT", "/image-generation/capability", """{"enabled":true}""")
            val config = TurnConfigBridge("c", "model", "", "", "chat", "", null, null, null, null,
                emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "draw",
                disableTools = false, supportsToolApproval = true)
            slice.bridge.startBackgroundTurn(config)
            val observer = async {
                slice.bridge.observeBackgroundTurn("c") { event ->
                    if (Json.parseToJsonElement(event).jsonObject["type"]?.jsonPrimitive?.content == "ToolApprovalRequested") approval.complete(Unit)
                }
            }
            withTimeout(10_000) { approval.await() }
            slice.bridge.respondToolApproval("image-fault", true)
            withTimeout(10_000) { imageEntered.await() }
            val admitted = checkNotNull(db.imageJobs().findRequest(ImageRequest("in-flight", "profile", "draw")))
            assertEquals(ImageJobStatus.running, admitted.status)
            db.useWriterConnection { connection ->
                connection.usePrepared("CREATE TRIGGER fail_image_update BEFORE UPDATE ON kv WHEN NEW.`key` LIKE 'local_image_job:%' BEGIN SELECT RAISE(ABORT, 'temporary storage failure'); END") { it.step() }
            }
            val stopping = async { lifecycle.stopAndAwait() }
            withTimeout(10_000) { lifecycle.state.first { it is RoomShutdownState.RecoveryRequired } }
            assertFalse(stopping.isCompleted)
            assertEquals(ImageJobStatus.running, db.imageJobs().read(admitted.id).status)
            assertFailsWith<IllegalStateException> { slice.assetGate.withExclusiveAssets { } }
            assertFailsWith<IllegalStateException> { recovery.recoverAfterStop(PriorImageWorkersStopped { error("Stop proof missing") }) }
            db.useWriterConnection { connection -> connection.usePrepared("DROP TRIGGER fail_image_update") { it.step() } }
            recovery.recoverAfterStop(PriorImageWorkersStopped { })
            val recovered = db.imageJobs().read(admitted.id)
            assertEquals(ImageJobStatus.unknown, recovered.status)
            assertTrue(recovered.error.orEmpty().contains("No automatic retry"))
            lifecycle.retryStop()
            withTimeout(10_000) { stopping.await(); observer.join() }
            assertEquals(1, imageCalls.get())
        } finally {
            release.countDown()
            db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun shutdownCancelsDispatchedModelImageAndRecordsUnknownWithoutRetry(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-backend-model-image-stop")
        val assets = Files.createDirectory(dir.resolve("assets"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val imageCalls = AtomicInteger()
        val imageEntered = CompletableDeferred<Unit>()
        val approval = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.readBytes()
            val args = Json.encodeToString("""{"requestId":"stop-model","profileId":"profile","prompt":"draw"}""")
            val response = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"image-stop\",\"type\":\"function\",\"function\":{\"name\":\"messenger_generate_image\",\"arguments\":$args}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" + "data: [DONE]\n\n"
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/images/generations") { exchange ->
            try {
                imageCalls.incrementAndGet(); exchange.requestBody.readBytes(); imageEntered.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
            } catch (_: Exception) { } finally { exchange.close() }
        }
        server.start()
        val lifecycle = RoomManagedShutdown { }
        val prefs = MemoryPreferences()
        try {
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m",
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
            lifecycle.enter()
            val base = "http://127.0.0.1:${server.address.port}"
            val images = IsolatedImageJobsConfig(dir, PriorImageWorkersStopped { }, ImageGenerationAuthorization { }, IsolatedImageRecovery(),
                modelAuthorization = ImageGenerationAuthorization { })
            val slice = KotlinRoomBackendSlice.attach(db, lifecycle, AppPreferences(prefs), ThemePreferences(prefs), assets,
                dir.resolve("avatars"), dir.resolve("chat-images"), base, "$base/models.json", now = { 1000 }, imageJobs = images)
            slice.providers.insert(Provider("p", "Provider", "$base/v1", "key", createdAt = 1, updatedAt = 1))
            slice.models.insert(ChatModel("m", "p", "model", "Model", true, supportsToolCalling = true, createdAt = 1))
            slice.conversations.insert(Conversation("c", "Chat", "p", "a", createdAt = 1, updatedAt = 1, lastMessage = null))
            slice.bridge.learningRequest("PUT", "/image-generation/profiles/profile",
                Json.encodeToString(ImageProfile("profile", "Profile", "p", "image-model", enabled = true)))
            slice.bridge.learningRequest("PUT", "/image-generation/capability", """{"enabled":true}""")
            val config = TurnConfigBridge("c", "model", "", "", "chat", "", null, null, null, null,
                emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "draw",
                disableTools = false, supportsToolApproval = true)
            slice.bridge.startBackgroundTurn(config)
            val observer = async {
                slice.bridge.observeBackgroundTurn("c") { event ->
                    if (Json.parseToJsonElement(event).jsonObject["type"]?.jsonPrimitive?.content == "ToolApprovalRequested") approval.complete(Unit)
                }
            }
            withTimeout(10_000) { approval.await() }
            slice.bridge.respondToolApproval("image-stop", true)
            withTimeout(10_000) { imageEntered.await() }
            val job = checkNotNull(db.imageJobs().findRequest(ImageRequest("stop-model", "profile", "draw")))
            assertTrue(slice.bridge.hasActiveTasks())
            withTimeout(10_000) { lifecycle.stopAndAwait(); observer.join() }
            val stopped = db.imageJobs().read(job.id)
            assertEquals(ImageJobStatus.unknown, stopped.status)
            assertTrue(stopped.error.orEmpty().contains("No automatic retry"))
            assertEquals(1, imageCalls.get())
            assertFailsWith<IllegalStateException> { slice.assetGate.withExclusiveAssets { } }
        } finally {
            release.countDown()
            db.close(); server.stop(0); executor.shutdownNow(); dir.toFile().deleteRecursively()
        }
    }
}
