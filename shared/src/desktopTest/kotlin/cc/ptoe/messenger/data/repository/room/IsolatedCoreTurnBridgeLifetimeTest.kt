/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.TurnConfigBridge
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.asset.RoomAssetGate
import cc.ptoe.messenger.data.local.room.ConversationRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ModelRow
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.data.remote.sse.ChatStreamEvent
import cc.ptoe.messenger.data.repository.LearningOwnership
import cc.ptoe.messenger.domain.chat.KotlinTurnProvider
import cc.ptoe.messenger.domain.model.Agent
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsolatedCoreTurnBridgeLifetimeTest {
    private fun config() = TurnConfigBridge("c", "model", "", "", "chat", "", null, null, null, null,
        emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "hello", disableTools = true)

    private suspend fun withBridge(provider: KotlinTurnProvider, afterSnapshot: suspend () -> Unit = {},
        afterCapture: suspend () -> Unit = {}, afterLegacyCapture: suspend () -> Unit = {},
        afterLegacyComplete: () -> Unit = {}, beforeAdmission: suspend () -> Unit = {},
        beforeWorkerStart: suspend () -> Unit = {},
        block: suspend (IsolatedCoreTurnBridge) -> Unit) {
        val dir = Files.createTempDirectory("isolated-turn-race")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val job = Job()
        val scope = CoroutineScope(Dispatchers.Default + job)
        val cloudGate = RoomCloudProviderGate.forDao(db.cloudSync())
        try {
            val rows = db.rows()
            rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "secret", "chat", false, 1, 1)))
            rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 100, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
            RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m",
                temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
            rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
            val delegate = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, method, _ ->
                error("Unexpected delegated call: ${method.name}")
            } as CoreBridge
            val assets = CoreAssetService(dir.resolve("assets"), db.assetMetadata(), RoomAssetGate())
            block(IsolatedCoreTurnBridge(delegate, db, LearningOwnership(db, assets), RoomArtifactService(db, assets),
                cloudGate, scope, { 1L }, provider = provider, afterActivitySnapshot = afterSnapshot,
                afterCancellationCapture = afterCapture, afterLegacyCancellationCapture = afterLegacyCapture,
                afterLegacyCancellationComplete = afterLegacyComplete, beforeLegacyAdmission = beforeAdmission,
                beforeLegacyWorkerStart = beforeWorkerStart))
        } finally {
            job.cancelAndJoin()
            cloudGate.shutdown()
            db.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun scopedCancellationWhileWorkerStartIsBlockedPreventsProviderDispatch() = runBlocking {
        val startEntered = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val providerCalls = AtomicInteger()
        val provider = KotlinTurnProvider { _, _ -> flow {
            providerCalls.incrementAndGet()
            emit(ChatStreamEvent.Done("stop"))
        } }
        withBridge(provider, beforeWorkerStart = {
            withContext(NonCancellable) { startEntered.complete(Unit); releaseStart.await() }
        }) { bridge ->
            try {
                val turn = async(Dispatchers.Default) { bridge.runScopedTurn(config(), { _, _ -> error("tools disabled") }) { } }
                withTimeout(5_000) { startEntered.await() }
                turn.cancel()
                releaseStart.complete(Unit)
                assertFailsWith<CancellationException> { withTimeout(5_000) { turn.await() } }
                withTimeout(5_000) { while (bridge.hasActiveTasks()) yield() }
                assertEquals(0, providerCalls.get())
            } finally { releaseStart.complete(Unit) }
        }
    }

    @Test fun legacyCancellationAfterAdmissionBeforeWorkerStartPreventsProviderDispatch() = runBlocking {
        val workerBlocked = CompletableDeferred<Unit>()
        val releaseWorker = CompletableDeferred<Unit>()
        val cancellationBlocked = CompletableDeferred<Unit>()
        val releaseCancellation = CompletableDeferred<Unit>()
        val cancellationCompleted = CompletableDeferred<Unit>()
        val providerCalls = AtomicInteger()
        val provider = KotlinTurnProvider { _, _ -> flow {
            providerCalls.incrementAndGet()
            emit(ChatStreamEvent.Done("stop"))
        } }
        withBridge(provider,
            beforeWorkerStart = { workerBlocked.complete(Unit); releaseWorker.await() },
            afterLegacyCapture = { cancellationBlocked.complete(Unit); releaseCancellation.await() },
            afterLegacyComplete = { cancellationCompleted.complete(Unit) }) { bridge ->
            try {
                val events = mutableListOf<String>()
                val turn = async(Dispatchers.Default) { bridge.runTurn(config(), { _, _ -> error("tools disabled") }) { events.add(it) } }
                withTimeout(5_000) { workerBlocked.await() }
                bridge.cancelTurn()
                withTimeout(5_000) { cancellationBlocked.await() }
                releaseWorker.complete(Unit)
                withTimeout(5_000) { turn.await() }
                assertTrue(events.any { it.contains("\"type\":\"Cancelled\"") })
                assertEquals(0, providerCalls.get())
                releaseCancellation.complete(Unit)
                withTimeout(5_000) { cancellationCompleted.await() }
            } finally { releaseWorker.complete(Unit); releaseCancellation.complete(Unit) }
        }
    }

    @Test fun legacyCancellationDuringAdmissionPreventsProviderDispatchAndDoesNotAffectNextTurn() = runBlocking {
        val admissionBlocked = CompletableDeferred<Unit>()
        val releaseAdmission = CompletableDeferred<Unit>()
        val firstAdmission = AtomicBoolean(true)
        val providerCalls = AtomicInteger()
        val provider = KotlinTurnProvider { _, _ -> flow {
            providerCalls.incrementAndGet()
            emit(ChatStreamEvent.Done("stop"))
        } }
        withBridge(provider, beforeAdmission = {
            if (firstAdmission.compareAndSet(true, false)) { admissionBlocked.complete(Unit); releaseAdmission.await() }
        }) { bridge ->
            try {
                val events = mutableListOf<String>()
                val first = async(Dispatchers.Default) { bridge.runTurn(config(), { _, _ -> error("tools disabled") }) { events.add(it) } }
                withTimeout(5_000) { admissionBlocked.await() }
                bridge.cancelTurn()
                releaseAdmission.complete(Unit)
                withTimeout(5_000) { first.await() }
                assertTrue(events.any { it.contains("\"type\":\"Cancelled\"") })
                assertEquals(0, providerCalls.get())
                val secondEvents = mutableListOf<String>()
                withTimeout(5_000) { bridge.runTurn(config(), { _, _ -> error("tools disabled") }) { secondEvents.add(it) } }
                assertTrue(secondEvents.any { it.contains("\"type\":\"Finished\"") })
                assertEquals(1, providerCalls.get())
            } finally { releaseAdmission.complete(Unit) }
        }
    }

    @Test fun delayedLegacyCancellationCannotCancelAReplacementTurn() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondStopped = CompletableDeferred<Unit>()
        val finishSecond = CompletableDeferred<Unit>()
        val cancellationCaptured = CompletableDeferred<Unit>()
        val resumeCancellation = CompletableDeferred<Unit>()
        val cancellationCompleted = CompletableDeferred<Unit>()
        val starts = AtomicInteger()
        val provider = KotlinTurnProvider { _, _ -> flow {
            if (starts.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                finishFirst.await()
                emit(ChatStreamEvent.Done("stop"))
            } else {
                try { secondStarted.complete(Unit); finishSecond.await(); emit(ChatStreamEvent.Done("stop")) }
                finally { secondStopped.complete(Unit) }
            }
        } }
        withBridge(provider, afterLegacyCapture = { cancellationCaptured.complete(Unit); resumeCancellation.await() },
            afterLegacyComplete = { cancellationCompleted.complete(Unit) }) { bridge ->
            try {
                val first = async(Dispatchers.Default) { bridge.runTurn(config(), { _, _ -> error("tools disabled") }) { } }
                withTimeout(5_000) { firstStarted.await() }
                bridge.cancelTurn()
                withTimeout(5_000) { cancellationCaptured.await() }
                finishFirst.complete(Unit)
                withTimeout(5_000) { first.await() }
                val second = async(Dispatchers.Default) {
                    withTimeout(5_000) {
                        while (true) {
                            try { bridge.runTurn(config(), { _, _ -> error("tools disabled") }) { }; break }
                            catch (error: IllegalStateException) {
                                check(error.message == "A turn worker is still stopping") { error }
                                yield()
                            }
                        }
                    }
                }
                withTimeout(5_000) { secondStarted.await() }
                resumeCancellation.complete(Unit)
                withTimeout(5_000) { cancellationCompleted.await() }
                assertTrue(bridge.backgroundTurnActive("c"))
                assertFalse(secondStopped.isCompleted)
                finishSecond.complete(Unit)
                withTimeout(5_000) { second.await() }
            } finally { finishFirst.complete(Unit); finishSecond.complete(Unit); resumeCancellation.complete(Unit) }
        }
    }

    @Test fun activeQueryRechecksRunnerAdmittedAfterSnapshot() = runBlocking {
        val snapshotTaken = CompletableDeferred<Unit>()
        val resumeQuery = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val firstSnapshot = AtomicBoolean(true)
        val provider = KotlinTurnProvider { _, _ -> flow {
            try { emit(ChatStreamEvent.Done("stop")) }
            finally { withContext(NonCancellable) { cleanupStarted.complete(Unit); releaseCleanup.await() } }
        } }
        withBridge(provider, afterSnapshot = {
            if (firstSnapshot.compareAndSet(true, false)) { snapshotTaken.complete(Unit); resumeQuery.await() }
        }) { bridge ->
            try {
                val query = async(Dispatchers.Default) { bridge.hasActiveTasks() }
                withTimeout(5_000) { snapshotTaken.await() }
                bridge.startBackgroundTurn(config())
                withTimeout(5_000) { cleanupStarted.await() }
                assertFalse(bridge.backgroundTurnActive("c"))
                resumeQuery.complete(Unit)
                assertTrue(withTimeout(5_000) { query.await() })
            } finally { resumeQuery.complete(Unit); releaseCleanup.complete(Unit) }
        }
    }

    @Test fun delayedCancellationCannotCancelAReplacementTurn() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val firstEnded = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val secondStopped = CompletableDeferred<Unit>()
        val cancellationCaptured = CompletableDeferred<Unit>()
        val resumeCancellation = CompletableDeferred<Unit>()
        val firstCapture = AtomicBoolean(true)
        val starts = AtomicInteger()
        val provider = KotlinTurnProvider { _, _ -> flow {
            if (starts.incrementAndGet() == 1) {
                try { firstStarted.complete(Unit); finishFirst.await(); emit(ChatStreamEvent.Done("stop")) }
                finally { firstEnded.complete(Unit) }
            } else {
                try { secondStarted.complete(Unit); awaitCancellation() }
                finally { secondStopped.complete(Unit) }
            }
        } }
        withBridge(provider, afterCapture = {
            if (firstCapture.compareAndSet(true, false)) { cancellationCaptured.complete(Unit); resumeCancellation.await() }
        }) { bridge ->
            try {
                bridge.startBackgroundTurn(config())
                withTimeout(5_000) { firstStarted.await() }
                val oldCancellation = async(Dispatchers.Default) { bridge.cancelBackgroundTurn("c") }
                withTimeout(5_000) { cancellationCaptured.await() }
                finishFirst.complete(Unit)
                withTimeout(5_000) { firstEnded.await() }
                withTimeout(5_000) {
                    while (true) {
                        try { bridge.startBackgroundTurn(config()); break }
                        catch (error: IllegalStateException) {
                            check(error.message == "A turn worker is still stopping") { error }
                            yield()
                        }
                    }
                    secondStarted.await()
                }
                resumeCancellation.complete(Unit)
                withTimeout(5_000) { oldCancellation.await() }
                assertTrue(bridge.backgroundTurnActive("c"))
                assertFalse(secondStopped.isCompleted)
                bridge.cancelBackgroundTurn("c")
                withTimeout(5_000) { secondStopped.await() }
            } finally { finishFirst.complete(Unit); resumeCancellation.complete(Unit) }
        }
    }

    @Test fun finishedBackgroundAndLegacyTurnsRemainActiveUntilProviderCleanupStops() = runBlocking {
        for (legacy in listOf(false, true)) {
            val dir = Files.createTempDirectory("isolated-turn-lifetime")
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString())
                .setDriver(BundledSQLiteDriver()).build()
            val job = Job()
            val scope = CoroutineScope(Dispatchers.Default + job)
            val releaseCleanup = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val cloudGate = RoomCloudProviderGate.forDao(db.cloudSync())
            try {
                val rows = db.rows()
                rows.upsertProviders(listOf(ProviderRow("p", "provider", "https://example.test", "secret", "chat", false, 1, 1)))
                rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 100, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
                RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "prompt", defaultModelId = "m",
                    temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1))
                rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
                val delegate = Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, method, _ ->
                    error("Unexpected delegated call: ${method.name}")
                } as CoreBridge
                val assets = CoreAssetService(dir.resolve("assets"), db.assetMetadata(), RoomAssetGate())
                val provider = KotlinTurnProvider { _, _ -> flow {
                    try {
                        emit(ChatStreamEvent.Content("reply"))
                        emit(ChatStreamEvent.Done("stop"))
                    } finally {
                        withContext(NonCancellable) { cleanupStarted.complete(Unit); releaseCleanup.await() }
                    }
                } }
                val bridge = IsolatedCoreTurnBridge(delegate, db, LearningOwnership(db, assets), RoomArtifactService(db, assets),
                    cloudGate, scope, { 1L }, provider = provider)
                val config = TurnConfigBridge("c", "model", "", "", "chat", "", null, null, null, null,
                    emptyList(), false, 0, "", null, null, null, false, "", operation = "send", content = "hello", disableTools = true)
                val call = if (legacy) async { bridge.runTurn(config, { _, _ -> error("tools disabled") }) { } } else null
                if (!legacy) bridge.startBackgroundTurn(config)
                withTimeout(5_000) { cleanupStarted.await() }
                if (call != null) withTimeout(5_000) { call.await() }
                else withTimeout(5_000) { bridge.observeBackgroundTurn("c") { } }
                assertNull(rows.getValue("kotlin_turn_active_v1:c"))
                assertFalse(bridge.backgroundTurnActive("c"))
                assertTrue(bridge.hasActiveTasks())
                assertFailsWith<IllegalStateException> { bridge.startBackgroundTurn(config) }
                releaseCleanup.complete(Unit)
                withTimeout(5_000) { while (bridge.hasActiveTasks()) yield() }
                assertFalse(bridge.hasActiveTasks())
            } finally {
                releaseCleanup.complete(Unit)
                job.cancelAndJoin()
                cloudGate.shutdown()
                db.close()
                dir.toFile().deleteRecursively()
            }
        }
    }
}
