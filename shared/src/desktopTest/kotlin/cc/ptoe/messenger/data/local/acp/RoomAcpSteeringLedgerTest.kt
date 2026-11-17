/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class RoomAcpSteeringLedgerTest {
    private val session = "acp:session"
    private val turn = UUID.randomUUID().toString()
    private val request = UUID.randomUUID().toString()
    private val content = Json.parseToJsonElement("""[{"type":"text","text":"secret"},{"type":"image","data":"YWJj","mimeType":"image/png"}]""").jsonArray

    @Test fun injectionCommitsTranscriptAndDeduplicationTogether(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-steering-ledger")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", """{"acp:session":{"id":"acp:session","messages":[]}}""")))
            val ledger = RoomAcpSteeringLedger(db)
            ledger.admit(request, session, turn, content)
            assertEquals(RoomAcpSteeringLedger.Pending(request, turn), ledger.pending(session))
            assertFalse(db.rows().getValue("kotlin_acp_steering_v1:$request")!!.value.contains("secret"))
            assertFails { ledger.admit(UUID.randomUUID().toString(), session, turn, content) }
            assertFails { ledger.settle(request, session, turn, JsonArray(emptyList()), "injected", 10) }
            ledger.settle(request, session, turn, content, "injected", 10)
            assertNull(ledger.pending(session))
            val row = Json.parseToJsonElement(db.rows().getValue("local_acp_sessions")!!.value).jsonObject[session]!!.jsonObject["messages"]!!.jsonArray.single().jsonObject
            assertEquals("secret", row["text"]!!.jsonPrimitive.content)
            assertEquals(content, row["content"])
            assertFails { ledger.settle(request, session, turn, content, "injected", 10) }
            assertFails { ledger.admit(request, session, turn, content) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun unknownRemainsBlockedUntilStoppedProofAndExplicitAcknowledgement(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-steering-unknown")
        val path = dir.resolve("db")
        fun open() = Room.databaseBuilder<LocalDatabase>(name = path.toString()).setDriver(BundledSQLiteDriver()).build()
        var db = open()
        try {
            db.rows().upsertValues(listOf(KeyValueRow("local_acp_sessions", """{"acp:session":{"id":"acp:session","messages":[]}}""")))
            RoomAcpSteeringLedger(db).admit(request, session, turn, content)
            db.close()
            db = open()
            val ledger = RoomAcpSteeringLedger(db)
            assertEquals(RoomAcpSteeringLedger.Pending(request, turn), ledger.pending(session))
            assertFails { ledger.acknowledgeUnknown(request, session, turn, object : AcpRecoveryBarrier {
                override suspend fun <T> withStopped(requestId: String, sessionId: String, action: suspend () -> T): T = error("worker still active")
            }) }
            assertNotNull(ledger.pending(session))
            ledger.acknowledgeUnknown(request, session, turn, object : AcpRecoveryBarrier {
                override suspend fun <T> withStopped(requestId: String, sessionId: String, action: suspend () -> T): T {
                    assertEquals(turn, requestId); assertEquals(session, sessionId)
                    return action()
                }
            })
            assertNull(ledger.pending(session))
            assertFails { ledger.admit(request, session, turn, content) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
