/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.di

import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.util.synchronizedBlock
import cc.ptoe.messenger.domain.mcp.McpManager

/** Single Room-owner lifetime; a second container must not share its manager. */
class McpManagerOwner internal constructor(
    private val manager: McpManager,
    private val preferences: AppPreferences,
    private val ownerRunning: () -> Boolean,
) {
    private val lock = Any()
    private var claimed = false
    private var closing = false

    internal fun start() = synchronizedBlock(lock) {
        check(!closing && ownerRunning()) { "MCP owner is unavailable" }
        manager.start()
    }

    internal fun claim(candidate: AppPreferences): McpManager = synchronizedBlock(lock) {
        check(!closing && ownerRunning() && !claimed) { "MCP owner is unavailable or already claimed" }
        require(candidate === preferences) { "MCP manager belongs to another preferences owner" }
        claimed = true
        manager
    }

    internal suspend fun stopAndAwait() {
        synchronizedBlock(lock) { closing = true }
        manager.shutdown()
    }
}
