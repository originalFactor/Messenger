/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.domain.mcp.McpManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class McpManagerOwnerTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test fun ownerCannotStartAfterStopOrBeClaimedByAnotherContainer(): Unit = runBlocking {
        val preferences = AppPreferences(MemoryPreferences())
        val manager = McpManager(preferences, startImmediately = false, bridgeProvider = { null })
        var running = true
        val owner = McpManagerOwner(manager, preferences) { running }
        owner.start()
        assertFailsWith<IllegalArgumentException> { owner.claim(AppPreferences(MemoryPreferences())) }
        assertSame(manager, owner.claim(preferences))
        assertFailsWith<IllegalStateException> { owner.claim(preferences) }
        owner.stopAndAwait()
        running = false
        assertFailsWith<IllegalStateException> { owner.claim(preferences) }
        assertFailsWith<IllegalStateException> { owner.start() }

        val neverStarted = McpManagerOwner(McpManager(preferences, startImmediately = false, bridgeProvider = { null }), preferences) { true }
        neverStarted.stopAndAwait()
        assertFailsWith<IllegalStateException> { neverStarted.start() }
    }
}
