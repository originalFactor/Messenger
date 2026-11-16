/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoomAcpRequestLedgerTest {
    private fun content(text: String) = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) }
    private val stopped = object : AcpRecoveryBarrier {
        override suspend fun <T> withStopped(requestId: String, sessionId: String, action: suspend () -> T) = action()
    }
    private fun database(path: String) = Room.databaseBuilder<LocalDatabase>(name = path).setDriver(BundledSQLiteDriver()).build()

    @Test fun concurrentAdmissionIsAtMostOnceAndPerSession(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-admit")
        val db = database(dir.resolve("db").toString())
        try {
            val ledger = RoomAcpRequestLedger(db)
            val id = UUID.randomUUID().toString()
            val payload = content("secret prompt and key")
            val attempts = coroutineScope { (1..12).map { async(Dispatchers.Default) { ledger.admit(id, "acp:one", payload) } }.awaitAll() }
            assertEquals(1, attempts.count { it == AcpAdmission.New })
            assertEquals(11, attempts.count { it == AcpAdmission.Existing(AcpAdmissionState.ADMITTED, true) })
            assertEquals(AcpAdmission.Existing(AcpAdmissionState.ADMITTED, false), ledger.admit(id, "acp:one", content("changed")))
            assertEquals(AcpAdmission.Existing(AcpAdmissionState.ADMITTED, false), ledger.admit(id, "acp:other", payload))
            val indexKey = "kotlin_acp_request_v1:$id"
            val stored = db.rows().getValue(indexKey)!!.value
            assertFalse(stored.contains("secret prompt")); assertFalse(stored.contains("key"))
            assertEquals(setOf("sessionId", "digest", "state"), Json.parseToJsonElement(stored).jsonObject.keys)
            assertEquals(AcpAdmission.SessionBusy, ledger.admit(UUID.randomUUID().toString(), "acp:one", payload))
            assertEquals(AcpAdmission.New, ledger.admit(UUID.randomUUID().toString(), "acp:other", payload))
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun recoveryRequiresProofAndNeverReexecutesAfterRestart(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-recover")
        val path = dir.resolve("db").toString()
        val id = UUID.randomUUID().toString()
        val next = UUID.randomUUID().toString()
        val payload = content("private text")
        val first = database(path)
        try {
            val ledger = RoomAcpRequestLedger(first)
            assertEquals(AcpAdmission.New, ledger.admit(id, "acp:one", payload))
            assertEquals(AcpAdmissionState.UNCERTAIN, ledger.markUncertain(id, "acp:one"))
        } finally { first.close() }
        val second = database(path)
        try {
            val ledger = RoomAcpRequestLedger(second)
            assertEquals(AcpAdmission.SessionBusy, ledger.admit(next, "acp:one", payload))
            assertEquals(id, ledger.activeRequest("acp:one"))
            var called = false
            val denied = object : AcpRecoveryBarrier {
                override suspend fun <T> withStopped(requestId: String, sessionId: String, action: suspend () -> T): T {
                    called = true
                    error("Cannot prove the old worker stopped")
                }
            }
            assertFailsWith<IllegalStateException> { ledger.recover(id, "acp:one", denied) }
            assertTrue(called)
            assertEquals(AcpAdmission.SessionBusy, ledger.admit(next, "acp:one", payload))
            assertEquals(AcpAdmission.Existing(AcpAdmissionState.UNCERTAIN, true), ledger.admit(id, "acp:one", payload))
            assertFailsWith<IllegalStateException> { ledger.complete(id, "acp:one", stopped) }
            assertEquals(AcpAdmissionState.INTERRUPTED, ledger.recover(id, "acp:one", stopped))
            assertEquals(null, ledger.activeRequest("acp:one"))
            assertEquals(AcpAdmission.Existing(AcpAdmissionState.INTERRUPTED, true), ledger.admit(id, "acp:one", payload))
            assertEquals(AcpAdmission.New, ledger.admit(next, "acp:one", payload))
            assertEquals(AcpAdmissionState.INTERRUPTED, ledger.recover(id, "acp:one", stopped))
            assertEquals(AcpAdmission.SessionBusy, ledger.admit(UUID.randomUUID().toString(), "acp:one", payload))
        } finally { second.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun concurrentDifferentIdsCannotBothClaimSessionAndCompletionReleasesOnlyOwner(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-claims")
        val db = database(dir.resolve("db").toString())
        try {
            val ledger = RoomAcpRequestLedger(db)
            val ids = List(12) { UUID.randomUUID().toString() }
            val results = coroutineScope { ids.map { id -> async(Dispatchers.Default) { id to ledger.admit(id, "acp:one", content("x")) } }.awaitAll() }
            val (winner, _) = results.single { it.second == AcpAdmission.New }
            assertEquals(11, results.count { it.second == AcpAdmission.SessionBusy })
            val denied = object : AcpRecoveryBarrier {
                override suspend fun <T> withStopped(requestId: String, sessionId: String, action: suspend () -> T): T = error("No stop proof")
            }
            assertFailsWith<IllegalStateException> { ledger.complete(winner, "acp:one", denied) }
            assertEquals(AcpAdmission.SessionBusy, ledger.admit(UUID.randomUUID().toString(), "acp:one", content("x")))
            assertFailsWith<IllegalStateException> { ledger.complete(winner, "acp:other", stopped) }
            assertEquals(AcpAdmissionState.COMPLETED, ledger.complete(winner, "acp:one", stopped))
            assertEquals(AcpAdmissionState.COMPLETED, ledger.complete(winner, "acp:one", stopped))
            val other = ids.first { it != winner }
            assertEquals(AcpAdmission.New, ledger.admit(other, "acp:one", content("x")))
            assertEquals(AcpAdmission.Existing(AcpAdmissionState.COMPLETED, true), ledger.admit(winner, "acp:one", content("x")))
            assertFailsWith<IllegalStateException> { ledger.markUncertain(winner, "acp:one") }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun invalidRequestIdsCannotCreateAliases(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-invalid")
        val db = database(dir.resolve("db").toString())
        try {
            val ledger = RoomAcpRequestLedger(db)
            assertFailsWith<IllegalArgumentException> { ledger.admit("1-1-1-1-1", "acp:one", content("x")) }
            assertFailsWith<IllegalArgumentException> { ledger.admit(UUID.randomUUID().toString().uppercase(), "acp:one", content("x")) }
            assertFailsWith<IllegalArgumentException> { ledger.admit(UUID.randomUUID().toString(), "acp:\n", content("x")) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
