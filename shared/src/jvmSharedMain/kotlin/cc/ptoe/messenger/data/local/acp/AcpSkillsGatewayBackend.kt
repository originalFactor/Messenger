/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.data.local.skill.RoomSkillStore
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Reads live enabled state from Room for every call; skill text never grants capabilities. */
internal class AcpSkillsGatewayBackend(private val skills: RoomSkillStore, private val other: AcpGatewayBackend) : AcpGatewayBackend {
    override suspend fun liveTools() = other.liveTools()
    override suspend fun execute(turnId: String, name: String, arguments: JsonObject): ToolExecutionResult {
        if (name != "messenger_skills") return other.execute(turnId, name, arguments)
        return try {
            require(arguments.keys.all { it == "name" || it == "path" }) { "Invalid skill arguments" }
            val nameValue = arguments["name"] as? JsonPrimitive
            require(nameValue?.isString == true) { "Skill name is required" }
            val pathValue = arguments["path"] as? JsonPrimitive
            require(arguments["path"] == null || arguments["path"] is kotlinx.serialization.json.JsonNull || pathValue?.isString == true) { "Invalid skill path" }
            ToolExecutionResult(skills.read(nameValue.content, pathValue?.contentOrNull ?: "SKILL.md", permitted = true))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ToolExecutionResult(failure.message ?: "Skill read failed", isError = true)
        }
    }
}
