/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.LocalDatabase
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Catalog must wait for the first explicit native publication and return a credential-preserving snapshot. */
internal fun interface AcpPublishedMcpCatalog { suspend fun snapshotWhenReady(): List<cc.ptoe.messenger.domain.mcp.McpServerConfig> }

/** New workers may use only the currently configured profile and device-local project. */
internal class RoomAcpNewSessionAuthority(private val db: LocalDatabase, private val catalog: AcpPublishedMcpCatalog,
    private val allowTakeover: Boolean = false) : AcpNewSessionAuthority {
    override suspend fun verify(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration) {
        val published = withTimeout(10_000) { acpMcpSnapshot(catalog.snapshotWhenReady()) }
        check(published == configuration.mcpServers) { "ACP MCP configuration changed" }
        db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val rows = db.rows()
            val project = requireNotNull(rows.project(binding.projectId)) { "ACP project is unavailable" }
            val device = rows.getValue("device_id")?.value
            check(!device.isNullOrBlank() && project.deviceId == device && project.workspace == binding.workspace) { "ACP project is not locally owned" }
            val path = Path.of(binding.workspace)
            check(path.isAbsolute && Files.isDirectory(path, NOFOLLOW_LINKS)) { "ACP workspace is unavailable" }
            val config = rows.getValue("device_control_config")?.value?.let { Json.parseToJsonElement(it) as? JsonObject }
                ?: error("ACP profiles are unavailable")
            val profile = (config["agents"] as? JsonArray)?.map { it as? JsonObject ?: error("Invalid ACP profile") }
                ?.singleOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == binding.profileId } ?: error("ACP profile is unavailable")
            fun booleanField(name: String, default: Boolean): Boolean = profile[name]?.let {
                val value = it as? JsonPrimitive ?: error("Invalid ACP profile setting")
                check(!value.isString) { "Invalid ACP profile setting" }
                value.booleanOrNull ?: error("Invalid ACP profile setting")
            } ?: default
            check(booleanField("enabled", true)) { "ACP profile is disabled" }
            val takeover = booleanField("providerTakeover", false)
            check(configuration.providerTakeover == takeover) { "ACP provider takeover setting changed" }
            check(!takeover || allowTakeover && binding.profileId in setOf("codex", "claude-code")) { "ACP model relay is unavailable for this profile" }
            val command = (profile["command"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Invalid ACP command")
            val args = (profile["args"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Invalid ACP argument") } ?: emptyList()
            val env = (profile["env"] as? JsonObject)?.mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: error("Invalid ACP environment") } ?: emptyMap()
            check(command == configuration.command && args == configuration.arguments && env == configuration.environment) { "ACP profile changed" }
            val delegation = !binding.delegated && rows.getValue("mcp_agent_delegation_enabled")?.value == "true"
            check(configuration.allowDelegation == delegation) { "ACP delegation setting changed" }
            val enabledCore = if (binding.delegated) emptySet() else buildSet {
                if (rows.getValue("learning_tools_enabled")?.value == "true") add("messenger_learning")
                if (rows.getValue("artifact_tools_enabled")?.value == "true") add("messenger_artifacts")
                if (rows.getValue("image_generation_enabled")?.value == "true") add("messenger_generate_image")
            }
            check(configuration.coreToolScope == enabledCore) { "ACP Core tool settings changed" }
        } }
    }
}

internal class AcpSessionCreator(private val manager: AcpWorkerManager, private val store: RoomAcpSessionStore,
    private val authority: AcpNewSessionAuthority, private val now: () -> Long) {
    suspend fun create(draft: AcpWorkerBinding, configuration: AcpWorkerConfiguration, title: String): AcpWorkerBinding =
        manager.createNew(draft, configuration, authority) { binding -> store.createUnderReservation(binding, title, now()) }
}
