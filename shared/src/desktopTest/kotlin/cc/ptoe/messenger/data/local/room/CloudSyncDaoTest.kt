/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.CloudReplacementPlan
import cc.ptoe.messenger.data.cloud.PulledCloudDelta
import cc.ptoe.messenger.data.cloud.PendingSyncChange
import cc.ptoe.messenger.data.cloud.SyncCollection
import cc.ptoe.messenger.data.cloud.SyncMutation
import cc.ptoe.messenger.data.cloud.SyncSession
import cc.ptoe.messenger.data.chat.ACTIVE_TURN_KEY_PREFIX
import androidx.room.Transactor
import androidx.room.useWriterConnection
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class CloudSyncDaoTest {
    @Test fun activeTurnsRejectConflictingCloudChangesAndKeepCursor(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-sync-active")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val dao = db.cloudSync()
            val session = SyncSession("account", "generation")
            fun doc(raw: String) = Json.parseToJsonElement(raw).jsonObject
            fun delta(collection: SyncCollection, raw: String) = PulledCloudDelta(mapOf(collection to listOf(doc(raw))), 7)
            dao.activate(session)
            dao.saveAgent(doc("""{"_id":"default","isDefault":true}""").agentRow(null))
            dao.saveAgent(doc("""{"_id":"agent","name":"Agent"}""").agentRow(null))
            dao.saveProject(ProjectRow("project", "Project", "/workspace", null, 1, 1))
            dao.saveConversation(doc("""{"_id":"conversation","agentId":"agent","projectId":"project","title":"Local"}""").conversationRow())
            dao.saveMessages(listOf(MessageRow("message", "conversation", "assistant", "partial", null, 1, "sending", null)))
            dao.applyPull(session, PulledCloudDelta(emptyMap(), 4), false)
            dao.writeValue(KeyValueRow(ACTIVE_TURN_KEY_PREFIX + "conversation", "turn"))
            val changes = listOf(
                delta(SyncCollection.CONVERSATION, """{"_id":"conversation","agentId":"agent","title":"Remote","messages":[]}"""),
                delta(SyncCollection.CONVERSATION, """{"_id":"conversation","deleted":true}"""),
                delta(SyncCollection.AGENT, """{"_id":"agent","deleted":true}"""),
                delta(SyncCollection.AGENT, """{"_id":"default","isDefault":true,"toolsEnabled":false}"""),
                delta(SyncCollection.AGENT, """{"_id":"new-default","isDefault":true,"toolsEnabled":false}"""),
                delta(SyncCollection.PROJECT, """{"_id":"project","deleted":true}"""),
                delta(SyncCollection.PROVIDER, """{"_id":"provider","deleted":true}"""),
            )
            for (change in changes) {
                assertFailsWith<IllegalStateException> { dao.applyPull(session, change, false) }
                assertEquals(4, dao.cursor(session))
                assertEquals("Local", dao.conversationRow("conversation")!!.title)
                assertEquals("partial", dao.cloudMessages("conversation").single().content)
            }
            assertFailsWith<IllegalStateException> { dao.applyPull(session, PulledCloudDelta(mapOf(
                SyncCollection.AGENT to listOf(doc("""{"_id":"default","isDefault":true}"""))), 7), true) }
            assertEquals(4, dao.cursor(session))
            dao.deleteValue(ACTIVE_TURN_KEY_PREFIX + "conversation")
            dao.applyPull(session, changes.first(), false)
            assertEquals(7, dao.cursor(session))
            assertEquals("Remote", dao.conversationRow("conversation")!!.title)
            assertTrue(dao.cloudMessages("conversation").isEmpty())
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun pullWaitsForTurnAdmissionAndSeesItsCommittedMarker(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-sync-active-race")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val dao = db.cloudSync()
            val session = SyncSession("account", "generation")
            fun doc(raw: String) = Json.parseToJsonElement(raw).jsonObject
            dao.activate(session)
            dao.saveAgent(doc("""{"_id":"agent","isDefault":true}""").agentRow(null))
            dao.saveConversation(doc("""{"_id":"conversation","agentId":"agent","title":"Local"}""").conversationRow())
            dao.applyPull(session, PulledCloudDelta(emptyMap(), 4), false)
            val markerWritten = CompletableDeferred<Unit>()
            val finishAdmission = CompletableDeferred<Unit>()
            val admission = async(Dispatchers.IO) {
                db.useWriterConnection { connection ->
                    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        dao.writeValue(KeyValueRow(ACTIVE_TURN_KEY_PREFIX + "conversation", "turn"))
                        markerWritten.complete(Unit)
                        finishAdmission.await()
                    }
                }
            }
            markerWritten.await()
            val pullStarted = CompletableDeferred<Unit>()
            val pull = async(Dispatchers.IO) {
                pullStarted.complete(Unit)
                runCatching { dao.applyPull(session, PulledCloudDelta(mapOf(
                    SyncCollection.CONVERSATION to listOf(doc("""{"_id":"conversation","deleted":true}"""))), 7), false) }
            }
            pullStarted.await()
            try {
                // Admission still holds the writer transaction; the pull must wait rather
                // than inspect the database before the active marker commits.
                assertNull(withTimeoutOrNull(200) { pull.await() })
            } finally { finishAdmission.complete(Unit) }
            withTimeout(5_000) { admission.await() }
            val result = withTimeout(5_000) { pull.await() }
            assertTrue(result.isFailure)
            assertEquals(4, dao.cursor(session))
            assertEquals("Local", dao.conversationRow("conversation")!!.title)
            assertEquals("turn", dao.value(ACTIVE_TURN_KEY_PREFIX + "conversation")!!.value)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun queueIsDurableAndOldAcknowledgementCannotEraseNewerChange(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-sync-queue")
        val path = dir.resolve("local.db")
        val session = SyncSession("account", "generation-1")
        try {
            var db = Room.databaseBuilder<LocalDatabase>(name = path.toString()).setDriver(BundledSQLiteDriver()).build()
            var dao = db.cloudSync()
            dao.activate(session)
            dao.enqueue(session, SyncCollection.AGENT, "agent", SyncMutation.UPSERT)
            val old = dao.pending(session).single()
            dao.enqueue(session, SyncCollection.AGENT, "agent", SyncMutation.UPSERT)
            dao.acknowledge(session, old)
            assertTrue(dao.pending(session).single().revision > old.revision)
            db.close()
            db = Room.databaseBuilder<LocalDatabase>(name = path.toString()).setDriver(BundledSQLiteDriver()).build()
            try {
                dao = db.cloudSync()
                assertEquals(session, dao.currentSession())
                val newer = dao.pending(session).single()
                dao.acknowledge(session, newer)
                assertTrue(dao.pending(session).isEmpty())
            } finally { db.close() }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun importedPendingAndSessionChangeAreProtected(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-sync-session")
        try {
            val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
            try {
                val dao = db.cloudSync()
                val first = SyncSession("one", "1")
                val second = SyncSession("two", "2")
                dao.writeMeta(SyncMetaRow("one", 42, "[\"agent:legacy\"]", "[]"))
                dao.activate(first)
                assertFailsWith<IllegalStateException> { dao.pending(first) }
                assertFailsWith<IllegalStateException> { dao.cursor(first) }
                dao.writeValue(KeyValueRow("cloud_session_host", "https://a.example"))
                assertFailsWith<IllegalArgumentException> { dao.adoptLegacyState(first, "https://b.example") }
                dao.adoptLegacyState(first, "https://a.example")
                val legacy = dao.pending(first).single()
                assertEquals(0, legacy.revision)
                assertEquals(42, dao.cursor(first))
                dao.enqueue(first, SyncCollection.AGENT, "legacy", SyncMutation.DELETE)
                dao.acknowledge(first, legacy)
                assertEquals(SyncMutation.DELETE, dao.pending(first).single().mutation)
                assertFailsWith<IllegalStateException> { dao.activate(second) }
                assertFalse(dao.expireIfCurrent(first.copy(generation = "stale")))
                assertEquals(first, dao.currentSession())
                dao.writeValue(KeyValueRow("cloud_session_host", "https://b.example"))
                assertFailsWith<IllegalStateException> { dao.cursor(first) }
                assertFailsWith<IllegalStateException> { dao.enqueue(first, SyncCollection.AGENT, "other", SyncMutation.UPSERT) }
                dao.writeValue(KeyValueRow("cloud_session_host", "https://a.example"))
                assertEquals(42, dao.cursor(first))
                assertEquals("legacy", dao.pending(first).single().id)
            } finally { db.close() }
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun pullProtectsPendingAndLocalFieldsAndRollsBackUnsafeRestore(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-sync-apply")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val dao = db.cloudSync(); val session = SyncSession("account", "generation")
            dao.activate(session)
            fun doc(raw: String) = Json.parseToJsonElement(raw).jsonObject
            val default = doc("""{"_id":"agent","name":"Default","isDefault":true}""")
            val provider = doc("""{"_id":"provider","name":"Cloud","models":[{"id":"model","modelId":"m","cacheWriteRate":1.5}]}""")
            fun delta(version: Long, vararg docs: Pair<SyncCollection, List<kotlinx.serialization.json.JsonObject>>) = PulledCloudDelta(docs.toMap(), version)
            dao.applyPull(session, delta(4, SyncCollection.AGENT to listOf(default), SyncCollection.PROVIDER to listOf(provider)), true)
            db.rows().upsertLearningRecords(listOf(LearningRecordRow("learning", "note", 1, null, "note", false, 1, "{}")))
            dao.writeValue(KeyValueRow("local_asset:test", "bytes"))
            dao.applyPull(session, delta(4, SyncCollection.AGENT to listOf(default), SyncCollection.PROVIDER to listOf(provider)), true)
            assertEquals("{}", db.learningReview().recordBody("learning"))
            assertEquals("bytes", dao.value("local_asset:test")?.value)
            dao.saveProvider(ProviderRow("builtin-messenger-cloud-ai", "Cloud", "url", "key", "chat", false, 1, 1))
            dao.enqueueFullSnapshot(session)
            val fullSnapshot = dao.pendingSnapshots(session)
            assertEquals(setOf("agent", "provider"), fullSnapshot.map { it.id }.toSet())
            fullSnapshot.forEach { dao.acknowledge(session, it) }
            dao.saveProvider(dao.providerRow("provider")!!.copy(nativeWebSearch = true, name = "local"))
            dao.saveModels(dao.providerModels("provider").map { it.copy(metadata = "{}") })
            dao.applyPull(session, delta(5, SyncCollection.PROVIDER to listOf(provider)), false)
            assertTrue(dao.providerRow("provider")!!.nativeWebSearch)
            assertEquals("{}", dao.providerModels("provider").single().metadata)
            assertEquals(1.5, dao.providerModels("provider").single().cacheWriteRate)
            val rich = doc("""{"_id":"provider","name":"Cloud","models":[{"id":"model","modelId":"m","cacheWriteRate":1.5,"metadata":{"inputModalities":["text","image"],"reasoning":true,"supportsTemperature":false}}]}""")
            dao.applyPull(session, delta(5, SyncCollection.PROVIDER to listOf(rich)), false)
            val metadata = Json.parseToJsonElement(dao.providerModels("provider").single().metadata!!).jsonObject
            assertEquals("image", metadata["inputModalities"]!!.jsonArray[1].jsonPrimitive.content)
            assertEquals("true", metadata["reasoning"]!!.jsonPrimitive.content)
            val imported = dao.providerModels("provider").single()
            assertEquals("text,image", imported.inputModalities)
            assertTrue(imported.supportsThinking)
            assertFalse(imported.supportsTemperature)
            dao.saveModels(listOf(imported.copy(metadataOverrides = """{"inputModalities":["text"],"reasoning":false,"supportsTemperature":true}""")))
            dao.applyPull(session, delta(5, SyncCollection.PROVIDER to listOf(rich)), false)
            val overridden = dao.providerModels("provider").single()
            assertEquals("text", overridden.inputModalities)
            assertFalse(overridden.supportsThinking)
            assertTrue(overridden.supportsTemperature)
            val agentWithNulls = doc("""{"_id":"null-agent","temperature":null,"topP":null}""").agentRow(null)
            assertNull(agentWithNulls.temperature)
            assertNull(agentWithNulls.topP)
            val disabled = doc("""{"_id":"disabled","temperature":0.7,"temperatureEnabled":false,"topP":1.0,"topPEnabled":false}""").agentRow(null)
            assertNull(disabled.temperature)
            assertNull(disabled.topP)
            val enabled = doc("""{"_id":"enabled","temperature":0.0,"temperatureEnabled":true,"topP":0.0,"topPEnabled":true}""").agentRow(null)
            assertEquals(0.0, enabled.temperature)
            assertEquals(0.0, enabled.topP)
            assertEquals(kotlinx.serialization.json.JsonNull, agentWithNulls.cloudRequest()["temperature"])

            dao.enqueue(session, SyncCollection.PROVIDER, "provider", SyncMutation.UPSERT)
            val outgoing = dao.pendingSnapshots(session).single().body!!
            assertFalse("nativeWebSearch" in outgoing)
            assertEquals("true", outgoing["models"]!!.jsonArray.single().jsonObject["metadata"]!!.jsonObject["reasoning"]!!.jsonPrimitive.content)
            assertEquals(1.5, outgoing["models"]!!.jsonArray.single().jsonObject["cacheWriteRate"]!!.jsonPrimitive.content.toDouble())
            dao.applyPull(session, delta(6, SyncCollection.PROVIDER to listOf(doc("""{"_id":"provider","deleted":true}"""))), false)
            assertEquals("Cloud", dao.providerRow("provider")!!.name)
            assertFailsWith<IllegalStateException> { dao.applyPull(session, delta(7, SyncCollection.AGENT to listOf(default)), true) }
            assertEquals(6, dao.cursor(session))
            assertEquals("bytes", dao.value("local_asset:test")?.value)
            assertEquals(1, dao.defaultCount())
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun replacementRekeysDefaultAndPreservesConversationAtomically(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-sync-rekey")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val dao = db.cloudSync(); val session = SyncSession("account", "generation")
            dao.activate(session)
            fun doc(raw: String) = Json.parseToJsonElement(raw).jsonObject
            dao.saveAgent(doc("""{"_id":"old","name":"Default","isDefault":true}""").agentRow(null))
            dao.saveConversation(doc("""{"_id":"conversation","agentId":"old"}""").conversationRow())
            dao.saveMessages(listOf(MessageRow("msg", "conversation", "user", "keep", null, 1, "sent", null)))
            dao.enqueue(session, SyncCollection.AGENT, "old", SyncMutation.UPSERT)
            val snapshot = dao.localSnapshot(session)
            val plan = CloudReplacementPlan(5, snapshot.revision, "old", "new", emptyList())
            val lock = dao.beginReplacement(session)
            val prepared = dao.prepareReplacement(session, plan, lock)
            assertTrue(prepared > snapshot.revision)
            assertEquals("new", dao.replacementDefaults().single().id)
            assertNull(dao.agentRow("old"))
            assertEquals("new", dao.conversationRow("conversation")!!.agentId)
            assertEquals("keep", dao.cloudMessages("conversation").single().content)
            assertEquals(setOf("new", "conversation"), dao.pending(session).map { it.id }.toSet())
            assertFailsWith<IllegalStateException> { dao.prepareReplacement(session, plan, lock) }
            assertEquals("new", dao.replacementDefaults().single().id)
            dao.finishReplacement(session, lock)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun imageFilesAreStagedOnlyForAcceptedDocumentsAndRolledBackOnFailure(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-image-transaction")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val dao = db.cloudSync(); val session = SyncSession("account", "generation")
            dao.activate(session)
            fun doc(raw: String) = Json.parseToJsonElement(raw).jsonObject
            dao.saveAgent(doc("""{"_id":"default","isDefault":true}""").agentRow(null))
            val imageParts = """[{"type":"image","dataUri":"data:image/png;base64,YWJj","localPath":"/foreign"}]"""
            val jsonParts = kotlinx.serialization.json.JsonPrimitive(imageParts).toString()
            fun conversation(agent: String) = doc("""{"_id":"chat","agentId":"$agent","messages":[{"id":"m","partsJson":$jsonParts}]}""")
            fun delta(document: kotlinx.serialization.json.JsonObject) = PulledCloudDelta(mapOf(SyncCollection.CONVERSATION to listOf(document)), 1)
            val imageDir = dir.resolve("images")
            val store = RoomCloudSyncStore(dao, {}, cc.ptoe.messenger.data.cloud.CloudImageRehydrator(imageDir))
            dao.enqueue(session, SyncCollection.CONVERSATION, "chat", SyncMutation.UPSERT)
            store.commitPull(session, delta(conversation("missing")), false)
            assertTrue(Files.notExists(imageDir))
            dao.acknowledge(session, dao.pending(session).single())
            assertFailsWith<IllegalStateException> { store.commitPull(session, delta(conversation("missing")), false) }
            assertTrue(Files.list(imageDir).use { it.findAny().isEmpty })
            store.commitPull(session, delta(conversation("default")), false)
            val parts = Json.parseToJsonElement(dao.cloudMessages("chat").single().partsJson!!).jsonArray
            val path = java.nio.file.Path.of(parts.single().jsonObject["localPath"]!!.jsonPrimitive.content)
            assertTrue(Files.isRegularFile(path))
            assertEquals("abc", Files.readString(path))
            val changed = SyncSession("other", "different")
            assertFailsWith<IllegalStateException> { dao.activate(changed) }
            assertFailsWith<IllegalStateException> { store.commitPull(changed, delta(conversation("default")), false) }
            assertEquals(1L, Files.list(imageDir).use { it.count() })
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun dataOwnershipRejectsAccountAndBaseSwitchEvenAfterLogout(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-cloud-scope")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val dao = db.cloudSync()
            val first = SyncSession("account", "one")
            val user = cc.ptoe.messenger.data.cloud.CloudUser(id = "account")
            val a = cc.ptoe.messenger.data.cloud.StoredCloudSession(first, "messenger_session=token", "https://example.test/a", user)
            dao.saveCredentials(a)
            dao.enqueue(first, SyncCollection.AGENT, "pending", SyncMutation.UPSERT)
            dao.clearCredentialsIfCurrent(first)
            val second = a.copy(session = first.copy(generation = "two"), host = "https://example.test/b")
            assertFailsWith<IllegalStateException> { dao.saveCredentials(second) }
            dao.saveCredentials(a.copy(session = first.copy(generation = "three")))
            assertEquals("pending", dao.pending(first.copy(generation = "three")).single().id)
            val otherUser = cc.ptoe.messenger.data.cloud.CloudUser(id = "other")
            assertFailsWith<IllegalStateException> { dao.saveCredentials(second.copy(session = SyncSession("other", "four"), host = a.host, user = otherUser)) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

}
