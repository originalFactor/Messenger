/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.skill

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.domain.model.SkillPackage
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinSkillBridgeTest {
    @Test fun previewsAndSavesSkillsWithoutExposingFilesInCatalog() = runBlocking {
        val dir = Files.createTempDirectory("room-skill-bridge")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val delegate = CoreBridge::class.java.cast(Proxy.newProxyInstance(CoreBridge::class.java.classLoader, arrayOf(CoreBridge::class.java)) { _, _, _ -> error("Unexpected delegate call") })
        val bridge = KotlinSkillBridge(delegate, RoomSkillStore(db))
        val body = """{"files":{"SKILL.md":"---\nname: sample\ndescription: Use this skill\n---\nInstructions","notes.txt":"private notes"},"binaryFiles":{"icon.bin":"AAEC/w=="},"enabled":true}"""
        try {
            val preview = Json.decodeFromString<SkillPackage>(bridge.validateSkill(body))
            assertEquals("sample", preview.name)
            assertEquals("AAEC/w==", preview.binaryFiles["icon.bin"])
            assertFailsWith<IllegalArgumentException> { bridge.saveSkill("wrong-name", body) }
            bridge.saveSkill("sample", body)
            val catalog = Json.parseToJsonElement(bridge.listSkills()).jsonObject.getValue("items").jsonArray
            val entry = Json.decodeFromJsonElement<SkillPackage>(catalog.single())
            assertEquals("sample", entry.name)
            assertTrue(entry.files.isEmpty() && entry.binaryFiles.isEmpty())
            assertEquals("private notes", Json.decodeFromString<SkillPackage>(bridge.getSkill("sample")).files["notes.txt"])
            bridge.saveSkill("sample", body.replace("\"enabled\":true", "\"enabled\":false"))
            assertFalse(Json.decodeFromString<SkillPackage>(bridge.getSkill("sample")).enabled)
            assertFailsWith<IllegalArgumentException> {
                bridge.validateSkill("""{"files":{"SKILL.md":"---\nname: sample\ndescription: valid\n---\n","../escape":"bad"}}""")
            }
            bridge.deleteSkill("sample")
            assertTrue(Json.parseToJsonElement(bridge.listSkills()).jsonObject.getValue("items").jsonArray.isEmpty())
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
