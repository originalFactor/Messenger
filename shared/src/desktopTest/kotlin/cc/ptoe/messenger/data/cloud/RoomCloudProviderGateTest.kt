/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.chat.KotlinTurnToolPolicy
import cc.ptoe.messenger.data.chat.KotlinTurnRequestPreparer
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.chat.RuntimeWorkspaceFence
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.buildJsonObject
import okio.Path.Companion.toPath
import kotlin.test.*

class RoomCloudProviderGateTest {
    @Test fun invalidationBoundReplacesStaleGenerationWithoutRevokingNewSession(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-invalidation-bound")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forTesting(dao, KotlinTurnProvider { _, _ -> flow { awaitCancellation() } }, 2)
        try {
            val stale = SyncSession("account", "old")
            val fresh = SyncSession("account", "fresh")
            dao.activate(stale)
            gate.invalidate(stale)
            assertFailsWith<IllegalStateException> { gate.requireValid(stale) }
            dao.activate(fresh)
            gate.invalidate(stale)
            gate.requireValid(fresh)
            val permit = gate.registerToolPermit(fresh)
            assertEquals("fresh", permit.authorize { "fresh" })
            permit.release()
            gate.invalidate(SyncSession("second", "generation"))
            gate.invalidate(SyncSession("third", "generation"))
            gate.invalidate(SyncSession("fourth", "generation"))
            gate.requireValid(stale)
            gate.requireValid(fresh)
        } finally { gate.shutdown(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun toolPermitRemainsRevokedAfterDurableSignOutAndDoesNotRevokeNewSession(): Unit = runBlocking {
        val dir = Files.createTempDirectory("cloud-tool-permit")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forTesting(dao, KotlinTurnProvider { _, _ -> flow { awaitCancellation() } })
        try {
            val old = SyncSession("account", "old")
            val fresh = SyncSession("account", "fresh")
            dao.activate(old)
            val permit = gate.registerToolPermit(old)
            assertEquals("admitted", permit.authorize { "admitted" })
            gate.invalidate(old)
            assertFailsWith<IllegalStateException> { permit.authorize { error("must not dispatch") } }
            gate.revoke(old) // Durable cleanup removes the temporary invalidation marker.
            dao.activate(fresh)
            val newPermit = gate.registerToolPermit(fresh)
            assertFailsWith<IllegalStateException> { permit.authorize { error("must not dispatch") } }
            assertEquals("fresh", newPermit.authorize { "fresh" })
            permit.release(); newPermit.release()
            assertFailsWith<IllegalStateException> { newPermit.authorize { error("released") } }
        } finally { gate.shutdown(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun session401AfterProviderDoneRejectsPendingCompletionAndToolClaim(): Unit = runBlocking {
        for (holdAfterBeginTool in listOf(false, true)) {
            val dir = Files.createTempDirectory("room-cloud-401-done")
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val doneSent = CompletableDeferred<Unit>()
            val call = ToolCallData("call", "read", "{}")
            val upstream = KotlinTurnProvider { _, _ -> flow {
                emit(ChatStreamEvent.Done("tool_calls", toolCalls = listOf(call)))
                doneSent.complete(Unit)
            } }
            val dao = db.cloudSync()
            val gate = RoomCloudProviderGate.forTesting(dao, upstream)
            try {
                val session = SyncSession("account", "generation")
                val user = CloudUser(id = "account", aiApiKey = "secret")
                val host = "https://cloud.example"
                dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie", host, user))
                dao.updateCloudUser(session, user, host)
                val rows = db.rows()
                rows.upsertModels(listOf(ModelRow("model", BUILTIN_PROVIDER_ID, "model-name", "Model", true, 100,
                    null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
                RoomAgentRepository(db).ensureDefaultAgent(Agent("agent", "Agent", systemPrompt = "prompt", defaultModelId = "model",
                    temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
                rows.upsertConversations(listOf(ConversationRow("conversation", "Chat", BUILTIN_PROVIDER_ID, "agent", null,
                    null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
                val preparer = object : KotlinTurnRequestPreparer {
                    override fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow,
                        model: ModelRow, provider: ProviderRow, history: List<MessageRow>) =
                        ChatCompletionRequestDto(model.modelId, emptyList(), tools = listOf(
                            ToolSpecDto("function", ToolSpecFunctionDto("read", "read", buildJsonObject {}))))
                    override fun toolGrant() = KotlinRuntimeToolGrant("conversation", null, "/workspace", null, "host", listOf("read"))
                }
                val actual = RoomKotlinTurnStore(db, preparer, KotlinTurnToolPolicy { _, _, _ -> true }, gate)
                val held = object : KotlinTurnStore by actual {
                    override suspend fun completeProvider(turnId: String, calls: List<ToolCallData>, usage: cc.ptoe.messenger.data.remote.dto.UsageDto?) {
                        if (!holdAfterBeginTool) { entered.complete(Unit); release.await() }
                        actual.completeProvider(turnId, calls, usage)
                    }
                    override suspend fun beginTool(turnId: String): ToolCallData {
                        val result = actual.beginTool(turnId)
                        if (holdAfterBeginTool) { entered.complete(Unit); release.await() }
                        return result
                    }
                }
                val dispatched = AtomicInteger()
                val runner = KotlinChatTurnRunner(held, upstream, KotlinTurnToolExecutor { id, tool ->
                    actual.claimToolInvocation(id, tool)
                    dispatched.incrementAndGet()
                    ToolExecutionResult("executed")
                }, scope)
                val admission = runner.submit(KotlinTurnCommand.Send("conversation", "hi", null))
                val turn = assertIs<KotlinTurnAdmission.Accepted>(admission, "Unexpected admission: $admission").turnId
                withTimeout(5_000) { entered.await(); doneSent.await() }
                // Model a 401 observed while durable credential cleanup is blocked by a reply.
                gate.invalidate(session)
                assertEquals(session, dao.currentSession())
                release.complete(Unit)
                withTimeout(5_000) { runner.awaitStopped(turn) }
                assertEquals(0, dispatched.get())
                assertEquals(if (holdAfterBeginTool) KotlinTurnPhase.INTERRUPTED_TOOL else KotlinTurnPhase.FAILED, actual.snapshot(turn).phase)
                assertNotNull(actual.observeFailure(turn).first())
                if (!holdAfterBeginTool) assertNull(rows.getValue("kotlin_turn_active_v1:conversation"))
            } finally {
                release.complete(Unit)
                scope.cancel()
                scope.coroutineContext[Job]?.join()
                gate.shutdown()
                db.close()
                dir.toFile().deleteRecursively()
            }
        }
    }
    @Test fun builtInTransportStreamsWithoutFollowingCredentialRedirects() = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-gate-http")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val redirected = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val leaks = AtomicInteger()
        redirected.createContext("/steal") { exchange -> leaks.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        redirected.start()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val useRedirect = AtomicBoolean(false)
        val credentials = java.util.concurrent.ConcurrentLinkedQueue<String>()
        server.createContext("/v1/chat/completions") { exchange ->
            credentials.add(exchange.requestHeaders.getFirst("Authorization") ?: "")
            exchange.requestBody.readBytes()
            if (useRedirect.get()) {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${redirected.address.port}/steal")
                exchange.sendResponseHeaders(302, -1); exchange.close()
            } else {
                val body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n".toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        val host = "http://127.0.0.1:${server.address.port}"
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forDao(dao)
        try {
            val session = SyncSession("account", "generation")
            val user = CloudUser(id = "account", aiApiKey = "secret")
            dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie", host, user))
            dao.updateCloudUser(session, user, host)
            val round = KotlinTurnSnapshot("turn", "conversation", KotlinTurnPhase.REQUESTING_PROVIDER,
                Provider(BUILTIN_PROVIDER_ID, "Cloud", "$host/v1", "secret", createdAt = 1, updatedAt = 1),
                ChatCompletionRequestDto("model", emptyList()), cloudIdentity = KotlinCloudTurnIdentity(session.accountId, session.generation))
            val events = withTimeout(5000) { gate.stream(round).toList() }
            assertTrue(events.any { it is ChatStreamEvent.Content && it.text == "hello" })
            assertTrue(events.any { it is ChatStreamEvent.Done })
            useRedirect.set(true)
            val failure = assertFailsWith<IllegalStateException> { withTimeout(5000) { gate.stream(round).toList() } }
            assertTrue(failure.message.orEmpty().contains("302"))
            assertEquals(0, leaks.get())
            assertEquals(listOf("Bearer secret", "Bearer secret"), credentials.toList())
        } finally {
            gate.shutdown(); db.close(); server.stop(0); redirected.stop(0); dir.toFile().deleteRecursively()
        }
    }

    @Test fun expiryAndLogoutRevokeBeforeDrainingRequestsAndRejectStaleCredentials() = runBlocking {
        for (logout in listOf(false, true)) {
            val dir = Files.createTempDirectory("room-cloud-turn-gate")
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val logoutRequests = AtomicInteger()
            server.createContext("/api/auth/logout") { exchange ->
                logoutRequests.incrementAndGet()
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.start()
            val host = "http://127.0.0.1:${server.address.port}"
            val client = RoomCloudHttpClient()
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val mayStop = CompletableDeferred<Unit>()
            val releaseWorkspace = CompletableDeferred<Unit>()
            val workspaceEntered = CompletableDeferred<Unit>()
            val started = CompletableDeferred<Unit>()
            val stopping = CompletableDeferred<Unit>()
            val dispatched = AtomicInteger()
            val upstream = KotlinTurnProvider { _, _ -> flow {
                dispatched.incrementAndGet()
                started.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { stopping.complete(Unit); mayStop.await() } }
            } }
            val gate = RoomCloudProviderGate.forTesting(db.cloudSync(), upstream)
            try {
                val dao = db.cloudSync()
                val auth = RoomCloudAuthentication(dao, client)
                val session = SyncSession("account", "generation-1")
                val user = CloudUser(id = "account", aiApiKey = "cloud-secret")
                val credentials = StoredCloudSession(session, "messenger_session=cookie", host, user)
                dao.saveCredentials(credentials)
                dao.updateCloudUser(session, user, host)
                val rows = db.rows()
                rows.upsertModels(listOf(ModelRow("model", BUILTIN_PROVIDER_ID, "model-name", "Model", true, 100,
                    null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
                RoomAgentRepository(db).ensureDefaultAgent(Agent("agent", "Agent", systemPrompt = "prompt", defaultModelId = "model",
                    temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
                rows.upsertConversations(listOf(ConversationRow("conversation", "Chat", BUILTIN_PROVIDER_ID, "agent", null,
                    null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
                val store = RoomKotlinTurnStore(db, { _, _, _, model, _, _ -> ChatCompletionRequestDto(model.modelId, emptyList()) },
                    KotlinTurnToolPolicy { _, _, _ -> true }, cloudGate = gate)
                val runner = KotlinChatTurnRunner(store, upstream, KotlinTurnToolExecutor { _, _ -> error("unexpected tool") }, scope)
                val turn = assertIs<KotlinTurnAdmission.Accepted>(runner.submit(KotlinTurnCommand.Send("conversation", "hi", null))).turnId
                withTimeout(5000) { started.await() }
                val staleRound = store.snapshot(turn)
                val workspace = async {
                    RuntimeWorkspaceFence.forDatabase(db).mutate {
                        workspaceEntered.complete(Unit)
                        releaseWorkspace.await()
                    }
                }
                withTimeout(5000) { workspaceEntered.await() }
                val revoke = async(Dispatchers.Default) { if (logout) auth.logout(host) else auth.expireIfCurrent(session) }
                withTimeout(5000) { stopping.await() }
                withTimeout(5000) { while (dao.currentSession() != null) yield() }
                assertNull(dao.currentSession())
                assertNull(dao.value("cloud_session"))
                assertFalse(workspace.isCompleted, "session revocation must not wait for the workspace fence")
                assertEquals("", dao.providerRow(BUILTIN_PROVIDER_ID)?.apiKey)
                assertNull(withTimeoutOrNull(200) { revoke.await() }, "revocation must wait until the request has stopped")
                assertFailsWith<IllegalStateException> { store.providerStream(staleRound, upstream).toList() }
                assertEquals(1, dispatched.get())
                mayStop.complete(Unit)
                withTimeout(5000) { revoke.await(); runner.awaitStopped(turn) }
                assertEquals(KotlinTurnPhase.FAILED, store.snapshot(turn).phase)
                assertNotNull(store.observeFailure(turn).first())
                assertNull(rows.getValue("kotlin_turn_active_v1:conversation"))
                assertNull(dao.providerRow(BUILTIN_PROVIDER_ID))
                assertEquals(if (logout) 1 else 0, logoutRequests.get())
                assertEquals(!logout, auth.sessionExpired.value)

                // A later login, even to the same account and with the same AI key, cannot revive
                // the old turn's captured credentials or let a stale 401 clear the new session.
                val next = session.copy(generation = "generation-2")
                dao.saveCredentials(credentials.copy(session = next))
                dao.updateCloudUser(next, user, host)
                auth.expireIfCurrent(session)
                assertEquals(next, dao.currentSession())
                assertFailsWith<IllegalStateException> { store.providerStream(staleRound, upstream).toList() }
                assertEquals(1, dispatched.get())
            } finally {
                releaseWorkspace.complete(Unit)
                mayStop.complete(Unit)
                scope.cancel()
                gate.shutdown()
                scope.coroutineContext[Job]?.join()
                client.close()
                db.close()
                server.stop(0)
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test fun session401InvalidatesProviderBeforeBlockedDeviceReplyAndIgnoresStale401(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-early-401")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val dao = db.cloudSync()
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val releaseReply = CompletableDeferred<Unit>()
        val enteredReply = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val upstream = KotlinTurnProvider { _, _ -> flow {
            calls.incrementAndGet(); started.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        } }
        val gate = RoomCloudProviderGate.forTesting(dao, upstream)
        val http = RoomCloudHttpClient()
        val host = "https://cloud.example"
        val session = SyncSession("account", "generation-1")
        val user = CloudUser(id = "account", aiApiKey = "secret")
        val stored = StoredCloudSession(session, "messenger_session=cookie", host, user)
        fun round(identity: SyncSession) = KotlinTurnSnapshot("turn", "conversation", KotlinTurnPhase.REQUESTING_PROVIDER,
            Provider(BUILTIN_PROVIDER_ID, "Cloud", "$host/v1", "secret", createdAt = 1, updatedAt = 1),
            ChatCompletionRequestDto("model", emptyList()), cloudIdentity = KotlinCloudTurnIdentity(identity.accountId, identity.generation))
        try {
            dao.saveCredentials(stored)
            dao.updateCloudUser(session, user, host)
            db.rows().upsertValues(listOf(KeyValueRow("device_id", "device")))
            val remote = KotlinRemoteControlScope(session.accountId, session.generation, host, "device")
            RoomDeviceControlGrantStore(db).save(remote, null, kotlinx.serialization.json.buildJsonObject {
                put("enabled", kotlinx.serialization.json.JsonPrimitive(true))
            })
            val replies = RoomDeviceControlFence.forDao(dao).replies(db, stored, "device")
            val reply = async { replies.send(1) { enteredReply.complete(Unit); releaseReply.await() } }
            withTimeout(5_000) { enteredReply.await() }
            val stream = async { runCatching { gate.stream(round(session)).toList() } }
            withTimeout(5_000) { started.await() }
            val auth = RoomCloudAuthentication(dao, http)
            val expiry = async(Dispatchers.Default) { auth.expireIfCurrent(session) }
            withTimeout(5_000) { stopped.await() }
            assertFalse(expiry.isCompleted, "durable clearing still waits for the admitted reply")
            assertEquals(session, dao.currentSession())
            assertFailsWith<IllegalStateException> { withTimeout(1_000) { gate.stream(round(session)).toList() } }
            assertEquals(1, calls.get())
            releaseReply.complete(Unit)
            withTimeout(5_000) { reply.await(); expiry.await(); stream.await() }
            assertNull(dao.currentSession())
            assertTrue(auth.sessionExpired.value)

            val newer = session.copy(generation = "generation-2")
            dao.saveCredentials(stored.copy(session = newer))
            dao.updateCloudUser(newer, user, host)
            auth.expireIfCurrent(session)
            assertEquals(newer, dao.currentSession())
            val second = async { runCatching { gate.stream(round(newer)).toList() } }
            withTimeout(5_000) { while (calls.get() < 2) yield() }
            assertFalse(second.isCompleted, "a stale 401 must not revoke the new provider")
            second.cancelAndJoin()
        } finally {
            releaseReply.complete(Unit)
            gate.shutdown(); http.close(); db.close(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun session401DuringRoomValidationPreventsProviderStart(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-admission-401")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val dao = db.cloudSync()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val upstream = KotlinTurnProvider { _, _ -> flow { calls.incrementAndGet(); awaitCancellation() } }
        val gate = RoomCloudProviderGate.forTesting(dao, upstream) { entered.complete(Unit); release.await() }
        val http = RoomCloudHttpClient()
        val host = "https://cloud.example"
        val session = SyncSession("account", "generation")
        val user = CloudUser(id = "account", aiApiKey = "secret")
        try {
            dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie", host, user))
            dao.updateCloudUser(session, user, host)
            val round = KotlinTurnSnapshot("turn", "conversation", KotlinTurnPhase.REQUESTING_PROVIDER,
                Provider(BUILTIN_PROVIDER_ID, "Cloud", "$host/v1", "secret", createdAt = 1, updatedAt = 1),
                ChatCompletionRequestDto("model", emptyList()), cloudIdentity = KotlinCloudTurnIdentity(session.accountId, session.generation))
            val stream = async { runCatching { gate.stream(round).toList() } }
            withTimeout(5_000) { entered.await() }
            val auth = RoomCloudAuthentication(dao, http)
            val expiry = async(start = CoroutineStart.UNDISPATCHED) { auth.expireIfCurrent(session) }
            assertFalse(expiry.isCompleted)
            release.complete(Unit)
            val result = withTimeout(5_000) { stream.await() }
            assertTrue(result.isFailure)
            withTimeout(5_000) { expiry.await() }
            assertEquals(0, calls.get())
            assertNull(dao.currentSession())
        } finally {
            release.complete(Unit)
            gate.shutdown(); http.close(); db.close(); dir.toFile().deleteRecursively()
        }
    }

    @Test fun cancelled401WaitsForCoordinatorAndStillClearsExactSession(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-cancelled-401")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val dao = db.cloudSync()
        val gate = RoomCloudProviderGate.forTesting(dao, KotlinTurnProvider { _, _ -> flow { awaitCancellation() } })
        val http = RoomCloudHttpClient()
        val session = SyncSession("account", "generation")
        val host = "https://cloud.example"
        val user = CloudUser(id = "account", aiApiKey = "secret")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            dao.saveCredentials(StoredCloudSession(session, "messenger_session=cookie", host, user))
            dao.updateCloudUser(session, user, host)
            val coordinator = RoomCloudCoordinator(db, http, host,
                RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")))
            coordinator.restore()
            val holder = async { coordinator.withSession { _, _ -> entered.complete(Unit); release.await() } }
            withTimeout(5_000) { entered.await() }
            val rejection = launch(start = CoroutineStart.UNDISPATCHED) { coordinator.rejectSession(session) }
            rejection.cancel()
            val round = KotlinTurnSnapshot("turn", "conversation", KotlinTurnPhase.REQUESTING_PROVIDER,
                Provider(BUILTIN_PROVIDER_ID, "Cloud", "$host/v1", "secret", createdAt = 1, updatedAt = 1),
                ChatCompletionRequestDto("model", emptyList()), cloudIdentity = KotlinCloudTurnIdentity(session.accountId, session.generation))
            assertFailsWith<IllegalStateException> { withTimeout(1_000) { gate.stream(round).toList() } }
            assertEquals(session, dao.currentSession())
            release.complete(Unit)
            withTimeout(5_000) { holder.await(); rejection.join() }
            assertNull(dao.currentSession())
            assertNull(coordinator.user.value)
            assertTrue(coordinator.sessionExpired.value)
        } finally {
            release.complete(Unit)
            gate.shutdown(); http.close(); db.close(); dir.toFile().deleteRecursively()
        }
    }
}
