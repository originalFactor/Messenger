/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.skill.RoomSkillStore
import cc.ptoe.messenger.data.local.skill.StoredSkill
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpSkillsGatewayBackendTest {
    @Test fun skillReadsUseLiveEnabledStateAndAcceptNullDefaultPath(): Unit = runBlocking {
        val dir = Files.createTempDirectory("acp-skill")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("local.db").toString()).setDriver(BundledSQLiteDriver()).build()
        try {
            val skills = RoomSkillStore(db)
            skills.put("review", StoredSkill(mapOf("SKILL.md" to "---\nname: review\ndescription: Review text\n---\nPrivate instructions"),
                mapOf("image.bin" to "YWJj"), true))
            val other = object : AcpGatewayBackend {
                override suspend fun liveTools() = emptyList<AcpGatewayTool>()
                override suspend fun execute(turnId: String, name: String, arguments: JsonObject) = ToolExecutionResult("unexpected", isError = true)
            }
            val gateway = AcpGatewayCore(AcpSkillsGatewayBackend(skills, other), false, emptySet())
            val secret = gateway.capability()
            gateway.beginTurn("turn")
            fun request(id: Int, path: JsonElement?) = buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id); put("method", "tools/call")
                put("params", buildJsonObject {
                    put("name", "messenger_skills")
                    put("arguments", buildJsonObject { put("name", "review"); if (path != null) put("path", path) })
                })
            }
            val result = gateway.request(secret, request(1, JsonNull))!!["result"]!!.jsonObject
            assertFalse(result["isError"]!!.jsonPrimitive.boolean)
            assertTrue(result["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content.contains("Private instructions"))
            val binary = gateway.request(secret, request(2, JsonPrimitive("image.bin")))!!["result"]!!.jsonObject
            assertTrue(binary["isError"]!!.jsonPrimitive.boolean)
            skills.setEnabled("review", false)
            val disabled = gateway.request(secret, request(3, null))!!["result"]!!.jsonObject
            assertTrue(disabled["isError"]!!.jsonPrimitive.boolean)
            gateway.endTurn("turn")
            gateway.close()
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
