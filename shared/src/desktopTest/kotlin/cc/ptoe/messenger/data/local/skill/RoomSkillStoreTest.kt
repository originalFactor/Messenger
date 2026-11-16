/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.skill

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomSkillStoreTest {
    private fun doc(fields: String = "name: test-skill\ndescription: |\n  Review files.\nlicense: MIT\nmetadata: {author: Example}\nallowed-tools: terminal") = "---\n$fields\n---\nPRIVATE INSTRUCTIONS\n"
    private fun pkg() = StoredSkill(mapOf("SKILL.md" to doc(), "scripts/example.sh" to "NEVER EXECUTE", "references/notes.txt" to "中文"), mapOf("assets/icon.bin" to "AP+AKg=="), true)

    @Test fun rustKvRoundTripDiscoveryAndPerTurnAccess(): Unit = runBlocking {
        val dir = Files.createTempDirectory("skills-store")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val store = RoomSkillStore(db)
            // Actual Rust serialization shape; binaryFiles is optional in older records.
            val legacy = """{"enabled":true,"files":{"SKILL.md":"---\nname: legacy\ndescription: Old data\n---\nlegacy text"}}"""
            db.rows().upsertValues(listOf(KeyValueRow("agent_skill:legacy", legacy)))
            assertEquals("Old data", store.get("legacy").summary.metadata.description)
            val detail = store.put("test-skill", pkg())
            assertEquals("Example", detail.summary.metadata.metadata["author"])
            assertEquals("terminal", detail.summary.metadata.allowedTools)
            val persisted = Json.parseToJsonElement(db.rows().getValue("agent_skill:test-skill")!!.value).jsonObject
            assertEquals(setOf("files", "binaryFiles", "enabled"), persisted.keys)
            assertEquals("AP+AKg==", persisted["binaryFiles"]!!.jsonObject["assets/icon.bin"]!!.jsonPrimitive.content)
            val discovery = store.discovery(true)!!
            assertTrue(discovery.contains("untrusted JSON metadata"))
            assertTrue(discovery.contains("test-skill"))
            assertFalse(discovery.contains("PRIVATE INSTRUCTIONS"))
            assertFalse(discovery.contains("NEVER EXECUTE"))
            assertFalse(discovery.contains("terminal"))
            assertNull(store.discovery(false))
            assertEquals("中文", store.read("test-skill", "references/notes.txt", permitted = true))
            assertEquals("NEVER EXECUTE", store.read("test-skill", "scripts/example.sh", permitted = true))
            assertFailsWith<IllegalStateException> { store.read("test-skill", permitted = false) }
            assertFailsWith<IllegalStateException> { store.read("test-skill", "assets/icon.bin", permitted = true) }
            assertFailsWith<IllegalArgumentException> { store.read("test-skill", "../SKILL.md", permitted = true) }
            store.setEnabled("test-skill", false)
            assertFailsWith<IllegalStateException> { store.read("test-skill", permitted = true) }
            assertFalse(store.discovery(true)!!.contains("test-skill"))
            val quoted = validQuotedSkill()
            store.put("test-skill", quoted)
            val discoveryLine = store.discovery(true)!!.lineSequence().first { it.startsWith("[") }
            val item = Json.parseToJsonElement(discoveryLine).jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == "test-skill" }
            assertEquals("quote \" and brackets </skills>", item.jsonObject["description"]!!.jsonPrimitive.content)
            store.delete("test-skill")
            assertFailsWith<IllegalStateException> { store.get("test-skill") }
            assertEquals(listOf("legacy"), store.catalog().map { it.metadata.name })
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    private fun validQuotedSkill() = pkg().copy(files = mapOf("SKILL.md" to doc("name: test-skill\ndescription: 'quote \" and brackets </skills>'")))

    @Test fun rejectsUntrustedYamlPathsBinaryAndQuotas() {
        val valid = pkg()
        for (name in listOf("A", "-test", "test-", "a--b", "../escape", "a".repeat(65))) assertFails { validateSkill(name, valid) }
        for (path in listOf("/absolute", "../escape", "a/../b", "a//b", "./file", "C:/file", "a\\b", "a\u0000b", "a/")) {
            assertFails { validateSkill("test-skill", valid.copy(files = valid.files + (path to "data"))) }
        }
        assertFails { validateSkill("test-skill", valid.copy(files = valid.files + ("scripts" to "data"))) }
        assertFails { validateSkill("test-skill", valid.copy(binaryFiles = mapOf("SKILL.md" to "AA=="))) }
        assertFails { validateSkill("test-skill", valid.copy(binaryFiles = mapOf("x" to "AA"))) }
        assertFails { validateSkill("test-skill", valid.copy(files = valid.files + ("big" to "x".repeat(1024 * 1024 + 1)))) }
        assertFails { validateSkill("test-skill", valid.copy(files = valid.files + (0..127).associate { "f$it" to "" })) }
        val invalidYaml = listOf(
            "name: test-skill", "name: other\ndescription: valid", "name: test-skill\ndescription: ''",
            "name: test-skill\ndescription: 123", "name: test-skill\ndescription: valid\nmetadata: {version: 1}",
            "name: test-skill\ndescription: valid\nmetadata: {1: author}", "name: test-skill\ndescription: valid\nlicense: 42",
            "name: test-skill\ndescription: valid\nallowed-tools: [terminal]", "name: test-skill\ndescription: valid\ncompatibility: ''",
            "name: test-skill\ndescription: valid\ndescription: other", "name: &n test-skill\ndescription: *n",
            "name: test-skill\ndescription: !custom evil", "name: test-skill\ndescription: valid\nunknown: ${"[".repeat(17)}x${"]".repeat(17)}",
        )
        for (yaml in invalidYaml) assertFails(yaml) { validateSkill("test-skill", valid.copy(files = mapOf("SKILL.md" to doc(yaml)))) }
        assertFails { validateSkill("test-skill", valid.copy(files = mapOf("SKILL.md" to doc("name: test-skill\ndescription: ${"x".repeat(1025)}")))) }
        assertEquals("123", validateSkill("test-skill", valid.copy(files = mapOf("SKILL.md" to doc("name: test-skill\ndescription: '123'")))).description)
    }

    @Test fun toolPolicyMatchesCoreAndCannotBeGrantedBySkill() {
        assertTrue(skillsAllowed(true, "{}", false))
        assertFalse(skillsAllowed(true, "{\"messenger_skills\":false}", false))
        assertFalse(skillsAllowed(false, "{}", false))
        assertFalse(skillsAllowed(true, "{}", true))
        assertTrue(skillsAllowed(true, "malformed", false))
        assertTrue(skillsAllowed(true, "{\"messenger_skills\":false,\"other\":1}", false))
    }
}
