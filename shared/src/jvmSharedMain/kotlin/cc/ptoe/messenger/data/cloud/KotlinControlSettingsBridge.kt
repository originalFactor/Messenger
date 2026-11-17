/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.presentation.ui.acp.AcpProfile
import java.nio.file.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Isolated settings adapter; install only with a single shared Room owner and lifecycle. */
internal class KotlinControlSettingsBridge(
    private val delegate: CoreBridge,
    private val db: LocalDatabase,
    private val configurationWrite: suspend (suspend () -> Unit) -> Unit = { it() },
) : CoreBridge by delegate {
    private val store = RoomDeviceControlGrantStore(db)
    private val identity = RoomDeviceHostAccessSource(db) { "Messenger · $it" }
    private val json = Json { encodeDefaults = true }
    private val tickets = LinkedHashMap<String, RoomControlSettingsSnapshot>()

    override suspend fun controlSettings(): JsonObject {
        identity.ensureDeviceId()
        val snapshot = store.readForBridge()
        val profiles = profiles(snapshot.config)
        val initial = if (snapshot.templatesSeeded) profiles else {
            val ids = profiles.mapTo(mutableSetOf()) { it.id }
            (profiles + defaults.filter { it.id !in ids }.take((50 - profiles.size).coerceAtLeast(0)))
        }
        val ticket = randomUuid()
        synchronized(tickets) {
            tickets[ticket] = snapshot
            while (tickets.size > 128) tickets.remove(tickets.keys.first())
        }
        val config = snapshot.config + mapOf(
            "name" to (snapshot.config["name"] ?: JsonPrimitive("")),
            "agents" to json.encodeToJsonElement(initial),
            TICKET to JsonPrimitive(ticket),
        )
        return JsonObject(config)
    }

    override suspend fun saveControlSettings(settings: JsonObject) {
        val ticket = (settings[TICKET] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Settings must be reloaded before saving")
        val snapshot = synchronized(tickets) { tickets.remove(ticket) } ?: error("Settings must be reloaded before saving")
        val config = JsonObject(settings - TICKET)
        val enabled = (config["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        require(enabled != null) { "Invalid remote control setting" }
        val name = (config["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Invalid device name")
        require(name.codePointCount(0, name.length) <= 120 && name.none(Char::isISOControl)) { "Invalid device name" }
        validate(profiles(config))
        if (config["agents"] == snapshot.config["agents"]) {
            store.saveForBridge(snapshot, config)
            return
        }
        // Revocation must not wait for an ACP worker. Preserve the exact old profiles in
        // the first transaction; a later profile write must still match its new epoch.
        val previouslyEnabled = (snapshot.config["enabled"] as? JsonPrimitive)?.booleanOrNull == true
        val expected = if (previouslyEnabled && !enabled) {
            val revoked = JsonObject(snapshot.config + ("enabled" to JsonPrimitive(false)))
            val epoch = store.saveForBridge(snapshot, revoked)
            store.readForBridge().also { current ->
                check(current.epoch == epoch && current.identity == snapshot.identity &&
                    current.session == snapshot.session && current.config == revoked && current.templatesSeeded) {
                    "Cloud session or settings changed"
                }
            }
        } else snapshot
        configurationWrite { store.saveForBridge(expected, config); Unit }
    }

    private fun profiles(config: JsonObject): List<AcpProfile> =
        json.decodeFromJsonElement(config["agents"]?.let { it as? JsonArray ?: error("Invalid ACP profiles") } ?: JsonArray(emptyList()))

    private fun validate(profiles: List<AcpProfile>) {
        val ids = mutableSetOf<String>()
        require(profiles.size <= 50 && profiles.all { profile ->
            profile.id.isNotEmpty() && profile.id.encodeToByteArray().size <= 80 && ids.add(profile.id) &&
                (!profile.providerTakeover || profile.id in setOf("codex", "claude-code", "opencode")) &&
                profile.name.isNotBlank() && profile.name.encodeToByteArray().size <= 120 &&
                profile.description.encodeToByteArray().size <= 4_000 &&
                (profile.avatar == null || profile.avatar.encodeToByteArray().size <= 4_096 && '\u0000' !in profile.avatar) &&
                profile.command.isNotBlank() && '\u0000' !in profile.command &&
                (profile.cwd == null || runCatching { Paths.get(profile.cwd).isAbsolute }.getOrDefault(false)) &&
                profile.args.all { '\u0000' !in it } && profile.env.all { (key, value) -> key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value }
        }) { "Invalid ACP agent profile" }
    }

    private companion object {
        const val TICKET = "_roomSettingsTicket"
        val defaults = listOf(
            AcpProfile("codex", "Codex", "OpenAI coding agent via ACP", "pnpm", listOf("dlx", "@agentclientprotocol/codex-acp"), enabled = false),
            AcpProfile("claude-code", "Claude Code", "Claude coding agent via ACP", "pnpm", listOf("dlx", "@agentclientprotocol/claude-agent-acp"), enabled = false),
            AcpProfile("opencode", "OpenCode", "OpenCode coding agent via ACP", "opencode", listOf("acp"), enabled = false),
        )
    }
}
