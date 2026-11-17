/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Transactor
import androidx.room.useReaderConnection
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.schedule.scheduleToolDeclaration
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Supplies settings-only descriptors from the same declarations used by Kotlin tool execution. */
internal class KotlinRoomToolCatalogBridge(
    private val delegate: CoreBridge,
    private val db: LocalDatabase,
) : CoreBridge by delegate {
    override suspend fun learningRequest(method: String, path: String, body: String?): String {
        if (method != "GET" || path != "/tools/core" || body != null) {
            return delegate.learningRequest(method, path, body)
        }
        return db.useReaderConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
                val entries: List<Pair<ToolSpecDto, String?>> = listOf(
                    coreSkillSpec to null,
                    coreAgentSpec to "mcp_agent_delegation_enabled",
                    coreLearningSpec to "learning_tools_enabled",
                    coreArtifactSpec to "artifact_tools_enabled",
                    coreImageSpec to "image_generation_enabled",
                    scheduleToolDeclaration() to "scheduled_task_tool_enabled",
                )
                val enabled = entries.map { (spec, setting) ->
                    spec to (setting == null || db.rows().getValue(setting)?.value == "true")
                }
                buildJsonObject {
                    put("tools", buildJsonArray {
                        enabled.forEach { (spec, available) ->
                            add(buildJsonObject {
                                put("name", spec.function.name)
                                put("description", spec.function.description)
                                put("parametersJson", spec.function.parameters.toString())
                                put("enabled", available)
                                put("workspaceRequired", false)
                            })
                        }
                    })
                }.toString()
            }
        }
    }
}
