/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.local.schedule.*
import cc.ptoe.messenger.domain.chat.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

class RoomScheduledRecoveryTest {
    private fun open(path: Path) = Room.databaseBuilder<LocalDatabase>(name = path.toString()).setDriver(BundledSQLiteDriver()).build()
    private fun store(db: LocalDatabase) = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(), KotlinTurnToolPolicy { _, _, _ -> false })
    private val id = "d3bc81a8-0589-49da-8cb1-8dc6a82507c6"

    private suspend fun seed(db: LocalDatabase): Pair<String, ScheduledRunClaim> {
        val rows = db.rows()
        rows.upsertAgents(listOf(Json.parseToJsonElement("""{"_id":"agent","isDefault":true,"defaultModelId":"m","toolsEnabled":false}""").jsonObject.agentRow(null)))
        rows.upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.test", "key", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", false, false, false, false, 1, null, null)))
        rows.upsertConversations(listOf(Json.parseToJsonElement("""{"_id":"conversation","agentId":"agent","writable":true}""").jsonObject.conversationRow()))
        val tasks = RoomScheduledTasks(db)
        tasks.create(KotlinScheduledTask(id, "Task", "hello", "agent", null, 1, 1))
        val claim = tasks.claimDue(1000).single().second
        assertTrue(tasks.recordConversation(claim, "conversation"))
        val store = store(db)
        val turn = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("conversation", "hello", null,
            scheduledRun = true, scheduledClaim = KotlinScheduledTurnClaim(id, claim.token, claim.startedAt)))).turnId
        assertTrue(store.claim(turn, false))
        store.beginProvider(turn)
        store.appendContent(turn, "partial")
        store.release(turn)
        return turn to claim
    }

    @Test fun stoppedProviderIsMarkedInterruptedAndClaimIsRetiredWithoutReplay(): Unit = runBlocking {
        val dir = Files.createTempDirectory("scheduled-recovery")
        val path = dir.resolve("db")
        val first = open(path)
        val (turn, _) = try { seed(first) } finally { first.close() }
        val db = open(path)
        try {
            var proofs = 0
            store(db).reconcilePreviousScheduledTurns(ScheduledRecoveryBarrier { proofs++ })
            assertEquals(1, proofs)
            assertNull(RoomScheduledTasks(db).active(id))
            assertNull(db.rows().getValue("kotlin_turn_active_v1:conversation"))
            assertEquals(KotlinTurnPhase.FAILED, store(db).snapshot(turn).phase)
            val messages = db.rows().messagesFor(listOf("conversation"))
            assertEquals(listOf("hello", "partial"), messages.map { it.content })
            assertEquals("error", messages.last().status)
            assertTrue(messages.last().errorMessage!!.contains("may have completed and incurred a charge"))
            assertTrue(RoomScheduledTasks(db).list().single().lastError!!.contains("interrupted"))
            assertEquals(emptyList(), RoomScheduledTasks(db).claimDue(1000))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun cancellationAndDeletedTaskPreserveConversationAndClearOnlyTheMatchingClaim(): Unit = runBlocking {
        for (deleted in listOf(false, true)) {
            val dir = Files.createTempDirectory("scheduled-recovery-cancel")
            val db = open(dir.resolve("db"))
            try {
                val (turn, _) = seed(db)
                val tasks = RoomScheduledTasks(db)
                if (deleted) tasks.delete(id) else tasks.update(tasks.list().single().copy(enabled = false))
                store(db).reconcilePreviousScheduledTurns(ScheduledRecoveryBarrier { })
                assertEquals(KotlinTurnPhase.CANCELLED, store(db).snapshot(turn).phase)
                assertNull(tasks.active(id))
                assertNotNull(db.rows().conversation("conversation"))
                assertTrue(db.rows().observeTurnErrorKeys().first().isNotEmpty())
                if (deleted) assertTrue(tasks.list().isEmpty()) else assertEquals("Execution cancelled", tasks.list().single().lastError)
            } finally { db.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun interruptedTitleKeepsTheCompletedAssistantAnswer(): Unit = runBlocking {
        val dir = Files.createTempDirectory("scheduled-recovery-title")
        val db = open(dir.resolve("db"))
        try {
            val (turn, _) = seed(db)
            val rows = db.rows()
            val assistant = rows.messagesFor(listOf("conversation")).last()
            rows.upsertMessages(listOf(assistant.copy(status = "sent")))
            val row = checkNotNull(rows.getValue("kotlin_turn_v1:$turn"))
            val stored = Json.parseToJsonElement(row.value).jsonObject
            rows.upsertValues(listOf(row.copy(value = JsonObject(stored + ("phase" to JsonPrimitive("TITLE_REQUESTING"))).toString())))
            store(db).reconcilePreviousScheduledTurns(ScheduledRecoveryBarrier { })
            assertEquals(KotlinTurnPhase.FINISHED, store(db).snapshot(turn).phase)
            assertEquals("sent", rows.messagesFor(listOf("conversation")).last().status)
            assertEquals("partial", rows.messagesFor(listOf("conversation")).last().content)
            assertNull(RoomScheduledTasks(db).active(id))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun proofFailureMissingClaimAndReplacementLockLeaveAllEvidence(): Unit = runBlocking {
        for (failure in listOf("proof", "claim", "replacement")) {
            val dir = Files.createTempDirectory("scheduled-recovery-denied")
            val db = open(dir.resolve("db"))
            try {
                val (turn, claim) = seed(db)
                if (failure == "claim") db.rows().deleteValue("kotlin_scheduled_active_v1:$id")
                if (failure == "replacement") db.rows().upsertValues(listOf(KeyValueRow(CLOUD_REPLACEMENT_LOCK_KEY, "replacement")))
                val before = db.rows().getValue("kotlin_turn_v1:$turn")
                val messages = db.rows().messagesFor(listOf("conversation"))
                assertFailsWith<IllegalStateException> { store(db).reconcilePreviousScheduledTurns(ScheduledRecoveryBarrier {
                    if (failure == "proof") error("worker stop unverified")
                }) }
                assertEquals(before, db.rows().getValue("kotlin_turn_v1:$turn"))
                assertEquals(messages, db.rows().messagesFor(listOf("conversation")))
                assertEquals(turn, db.rows().getValue("kotlin_turn_active_v1:conversation")?.value)
                if (failure != "claim") assertEquals(claim, RoomScheduledTasks(db).active(id))
            } finally { db.close(); dir.toFile().deleteRecursively() }
        }
    }

    @Test fun claimedToolKeepsTurnAndClaimForSeparateRecovery(): Unit = runBlocking {
        val dir = Files.createTempDirectory("scheduled-recovery-tool")
        val db = open(dir.resolve("db"))
        try {
            val (turn, claim) = seed(db)
            val row = checkNotNull(db.rows().getValue("kotlin_turn_v1:$turn"))
            val stored = Json.parseToJsonElement(row.value).jsonObject
            db.rows().upsertValues(listOf(row.copy(value = JsonObject(stored + mapOf(
                "phase" to JsonPrimitive("EXECUTING_TOOL"), "invocationStarted" to JsonPrimitive(true))).toString())))
            store(db).reconcilePreviousScheduledTurns(ScheduledRecoveryBarrier { })
            assertEquals(KotlinTurnPhase.INTERRUPTED_TOOL, store(db).snapshot(turn).phase)
            assertEquals(turn, db.rows().getValue("kotlin_turn_active_v1:conversation")?.value)
            assertEquals(claim, RoomScheduledTasks(db).active(id))
            assertFailsWith<IllegalStateException> { RoomScheduledTasks(db).recoverInterrupted() }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
