/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CloudImageRehydratorTest {
    @Test fun imagesAreWrittenLocallyWithoutTrustingTheRemotePath() {
        val dir = Files.createTempDirectory("cloud-images")
        try {
            val parts = """[{"type":"image","dataUri":"data:image/png;base64,YWJj","localPath":"/foreign/a.png"}]"""
            val doc = kotlinx.serialization.json.buildJsonObject {
                put("_id", kotlinx.serialization.json.JsonPrimitive("chat"))
                put("messages", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive("message"))
                    put("partsJson", kotlinx.serialization.json.JsonPrimitive(parts))
                })))
            }
            val stage = CloudImageRehydrator(dir)
            val created = mutableListOf<java.nio.file.Path>()
            val result = stage.stage(doc, created)
            val rewritten = result["messages"]!!.jsonArray.single().jsonObject["partsJson"]!!.jsonPrimitive.content
            val path = java.nio.file.Path.of(Json.parseToJsonElement(rewritten).jsonArray.single().jsonObject["localPath"]!!.jsonPrimitive.content)
            assertTrue(path.startsWith(dir))
            assertEquals("abc", Files.readString(path))
            assertEquals(listOf(path), created)
            val reused = mutableListOf<java.nio.file.Path>()
            assertEquals(result, stage.stage(doc, reused))
            assertTrue(reused.isEmpty())
            assertFailsWith<IllegalStateException> { CloudImageRehydrator.rejectUnstagedImages(doc) }
            val bad = Json.parseToJsonElement(doc.toString().replace("YWJj", "%%%%")).jsonObject
            assertFailsWith<IllegalArgumentException> { stage.stage(bad, created) }
            stage.cleanCreated(created)
            assertTrue(Files.notExists(path))
            java.io.RandomAccessFile(dir.resolve("existing").toFile(), "rw").use { it.setLength(512L * 1024 * 1024) }
            val overflow = mutableListOf<java.nio.file.Path>()
            assertFailsWith<IllegalStateException> { stage.stage(doc, overflow) }
            assertTrue(overflow.isEmpty())
        } finally { dir.toFile().deleteRecursively() }
    }
}
