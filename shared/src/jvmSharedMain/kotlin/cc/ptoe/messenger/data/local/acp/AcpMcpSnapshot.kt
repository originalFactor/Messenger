/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import cc.ptoe.messenger.domain.mcp.McpServerConfig
import cc.ptoe.messenger.domain.mcp.McpTransportType
import kotlinx.serialization.json.*

/** Convert the live platform catalog to ACP v1 wire form without exposing credentials elsewhere. */
internal fun acpMcpSnapshot(configs: List<McpServerConfig>): JsonArray {
    val enabled = configs.filter { it.isEnabled }
    require(enabled.size <= 128 && enabled.all { it.name.isNotBlank() } && enabled.map { it.name }.distinct().size == enabled.size) { "Invalid ACP MCP catalog" }
    return JsonArray(enabled.map { config ->
        buildJsonObject {
            put("name", config.name)
            when (config.transportType) {
                McpTransportType.STDIO -> {
                    require(config.command.isNotBlank() && '\u0000' !in config.command && config.args.none { '\u0000' in it }) { "Invalid ACP MCP command" }
                    put("command", config.command)
                    put("args", JsonArray(config.args.map(::JsonPrimitive)))
                    put("env", JsonArray(config.env.toSortedMap().map { (key, value) ->
                        require(key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value) { "Invalid ACP MCP environment" }
                        buildJsonObject { put("name", key); put("value", value) }
                    }))
                }
                McpTransportType.SSE -> {
                    require(config.url.isNotBlank() && config.headers.keys.all { it.isNotBlank() && '\r' !in it && '\n' !in it } &&
                        config.headers.values.all { '\r' !in it && '\n' !in it }) { "Invalid ACP MCP endpoint" }
                    put("type", "sse"); put("url", config.url)
                    put("headers", JsonArray(config.headers.toSortedMap().map { (key, value) -> buildJsonObject { put("name", key); put("value", value) } }))
                }
            }
        }
    })
}
