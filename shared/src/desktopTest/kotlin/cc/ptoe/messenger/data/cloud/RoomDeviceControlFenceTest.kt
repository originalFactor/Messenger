/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomDeviceControlFenceTest {
    @Test fun grantAndSessionRevocationWaitForAdmittedReplyAndBlockOldReplies(): Unit = runBlocking {
        val dir = Files.createTempDirectory("control-reply-fence")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val provider = RoomCloudProviderGate.forDao(db.cloudSync())
        try {
            val scope = KotlinRemoteControlScope("account", "one", "https://cloud.example", "device")
            val session = StoredCloudSession(SyncSession("account", "one"), "messenger_session=secret", "https://cloud.example", CloudUser(id = "account"))
            db.rows().upsertValues(listOf(
                KeyValueRow("kotlin_sync_session", """["account","one"]"""), KeyValueRow("device_id", "device"),
                KeyValueRow("cloud_session", session.cookie), KeyValueRow("cloud_session_host", session.host),
                KeyValueRow("cloud_user_json", """{"id":"account"}"""),
            ))
            val store = RoomDeviceControlGrantStore(db)
            fun config(enabled: Boolean) = buildJsonObject { put("enabled", enabled) }
            store.save(scope, null, config(true))
            val replies = RoomDeviceControlFence.forDao(db.cloudSync()).replies(db, session, "device")
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val first = async { replies.send(1) { entered.complete(Unit); release.await() } }
            withTimeout(5_000) { entered.await() }
            val disable = async { store.save(scope, 1, config(false)) }
            delay(100)
            assertFalse(disable.isCompleted)
            release.complete(Unit); first.await()
            assertEquals(2L, withTimeout(5_000) { disable.await() })
            assertFailsWith<IllegalStateException> { replies.send(1) { error("stale reply sent") } }
            store.save(scope, 2, config(true))
            val enteredAgain = CompletableDeferred<Unit>(); val releaseAgain = CompletableDeferred<Unit>()
            val second = async { replies.send(3) { enteredAgain.complete(Unit); releaseAgain.await() } }
            withTimeout(5_000) { enteredAgain.await() }
            val logout = async { provider.revoke(session.session) }
            delay(100)
            assertFalse(logout.isCompleted)
            releaseAgain.complete(Unit); second.await()
            assertTrue(withTimeout(5_000) { logout.await() })
            assertFailsWith<IllegalStateException> { replies.send(3) { error("signed-out reply sent") } }
        } finally { provider.shutdown(); db.close(); dir.toFile().deleteRecursively() }
    }
}
