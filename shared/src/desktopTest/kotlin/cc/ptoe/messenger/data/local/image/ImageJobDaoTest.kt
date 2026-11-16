/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.image

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProviderRow
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageJobDaoTest {
    @Test fun jobsRequireOptInAreIdempotentAndNeverResumeAmbiguousRequests(): Unit = runBlocking {
        val dir = Files.createTempDirectory("image-job")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.com/v1", "secret", "chat", false, 1, 1)))
            val dao = db.imageJobs()
            val profile = ImageProfile("profile", "Profile", "p", "model", enabled = true, supportedParameters = setOf("n"), defaults = buildJsonObject { put("n", 1) })
            dao.putProfile(profile)
            val request = ImageRequest("request", "profile", "draw")
            assertFailsWith<IllegalStateException> { dao.admit(request, 1) }
            dao.setEnabled(true)
            val admitted = dao.admit(request, 2)
            assertTrue(admitted.created)
            assertEquals(ImageJobStatus.queued, admitted.job.status)
            assertFalse(dao.admit(request, 3).created)
            assertFailsWith<IllegalStateException> { dao.admit(request.copy(prompt = "different"), 3) }
            assertEquals(ImageJobStatus.running, dao.markRunning(admitted.job.id, 4).status)
            assertFailsWith<IllegalStateException> { dao.markRunning(admitted.job.id, 5) }
            dao.recoverInterrupted(6)
            assertEquals(ImageJobStatus.unknown, dao.read(admitted.job.id).status)
            dao.setEnabled(false)
            assertEquals(ImageJobStatus.unknown, dao.admit(request, 7).job.status)
            assertFalse(dao.admit(request, 7).created)
            assertFailsWith<IllegalStateException> { dao.markRunning(admitted.job.id, 8) }
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
