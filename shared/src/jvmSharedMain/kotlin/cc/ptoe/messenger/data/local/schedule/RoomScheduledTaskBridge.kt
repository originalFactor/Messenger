/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.schedule

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Compatible with the existing ScheduledTasksScreen/CoreBridge request surface; not yet installed. */
internal class RoomScheduledTaskBridge(private val store: RoomScheduledTasks) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    @Serializable private data class Setting(val enabled: Boolean)

    suspend fun request(method: String, path: String, body: String? = null): String = when {
        path == "/scheduled-tasks" && method == "GET" -> json.encodeToString(store.list())
        path == "/scheduled-tasks" && method == "POST" -> json.encodeToString(store.create(json.decodeFromString(requireNotNull(body))))
        path == "/scheduled-tasks/tool-settings" && method == "GET" -> json.encodeToString(Setting(store.toolEnabled()))
        path == "/scheduled-tasks/tool-settings" && method == "PUT" -> {
            val setting = json.decodeFromString<Setting>(requireNotNull(body))
            store.setToolEnabled(setting.enabled)
            json.encodeToString(setting)
        }
        path.startsWith("/scheduled-tasks/") && method == "PUT" -> {
            val task = json.decodeFromString<KotlinScheduledTask>(requireNotNull(body))
            require(path == "/scheduled-tasks/${task.id}") { "Scheduled task ID does not match path" }
            json.encodeToString(store.update(task))
        }
        path.startsWith("/scheduled-tasks/") && method == "DELETE" -> {
            val id = path.removePrefix("/scheduled-tasks/")
            require(id.isNotBlank() && '/' !in id) { "Invalid scheduled task path" }
            store.delete(id)
            ""
        }
        else -> error("Unsupported scheduled task request")
    }
}
