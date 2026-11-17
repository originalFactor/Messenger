/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.presentation.platform.TaskPushEvent
import cc.ptoe.messenger.presentation.platform.TaskPushNotifications
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KotlinRoomEventsBridgeTest {
    @Test fun eventOwnerRemainsAvailableUntilChatWorkersActuallyStop(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-event-shutdown")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val workers = Job()
        val composition = KotlinRoomBridgeComposition.attach(db, RoomManagedShutdown { })
        val events = Channel<List<String>>(Channel.UNLIMITED)
        try {
            composition.bindChatWorkers(workers)
            composition.bridge.subscribe { kind, ids -> if (kind == "taskStatus") events.trySend(ids) }
            assertFailsWith<IllegalStateException> { composition.shutdown() }
            composition.chatLifecycle.status("conversation", "failed")
            assertEquals(listOf("failed", "chat", "conversation"), withTimeout(5_000) { events.receive() })
            workers.cancelAndJoin()
            composition.shutdown()
            composition.chatLifecycle.status("conversation", "completed")
            assertNull(withTimeoutOrNull(200) { events.receive() })
        } finally { workers.cancelAndJoin(); composition.shutdown(); db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun settingsChangesNotifySubscribersUntilShutdown(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-event-bridge")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val delegate = CoreBridge::class.java.cast(Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ -> error("Unexpected delegate call") })
        val bridge = KotlinRoomEventsBridge(delegate, db)
        val events = Channel<String>(Channel.UNLIMITED)
        val pushes = Channel<TaskPushEvent>(Channel.UNLIMITED)
        val previousPushHandler = TaskPushNotifications.onEvent
        try {
            TaskPushNotifications.onEvent = { pushes.trySend(it); error("push handler failed") }
            bridge.chatTaskStatus("conversation", "approval")
            assertEquals(TaskPushEvent("approval", "conversation", "chat"), withTimeout(5_000) { pushes.receive() })
            bridge.subscribe { kind, _ -> events.trySend(kind) }
            assertEquals("Other", withTimeout(5_000) { events.receive() })
            db.rows().upsertValues(listOf(KeyValueRow("learning_tools_enabled", "true")))
            assertEquals("Other", withTimeout(5_000) { events.receive() })
            bridge.chatTaskStatus("conversation", "completed")
            assertEquals(TaskPushEvent("completed", "conversation", "chat"), withTimeout(5_000) { pushes.receive() })
            assertEquals("taskStatus", withTimeout(5_000) { events.receive() })
            bridge.shutdown()
            bridge.chatTaskStatus("conversation", "failed")
            db.rows().upsertValues(listOf(KeyValueRow("learning_tools_enabled", "false")))
            assertNull(withTimeoutOrNull(200) { events.receive() })
            assertNull(withTimeoutOrNull(200) { pushes.receive() })
            assertFailsWith<IllegalStateException> { bridge.subscribe { _, _ -> } }
        } finally { TaskPushNotifications.onEvent = previousPushHandler; bridge.shutdown(); db.close(); dir.toFile().deleteRecursively() }
    }
}
