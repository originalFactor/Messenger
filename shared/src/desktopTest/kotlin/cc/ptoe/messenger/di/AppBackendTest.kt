/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.core.CoreBridgeRegistry
import cc.ptoe.messenger.data.local.AppPreferences
import cc.ptoe.messenger.data.local.ChatImageStore
import cc.ptoe.messenger.data.local.ThemePreferences
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.repository.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppBackendTest {
    private inline fun <reified T> fake(noinline method: (String) -> Any? = { null }): T =
        T::class.java.cast(Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, call, _ -> method(call.name) })

    @Test fun injectedBackendUsesOneBridgeAndOwnsDataClearing() = runBlocking {
        val prefs = object : DataStore<Preferences> {
            private val state = MutableStateFlow<Preferences>(emptyPreferences())
            override val data: Flow<Preferences> = state
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(state.value).also { state.value = it }
        }
        val cloud = fake<CloudFacade> { if (it == "getUser") flowOf(null) else null }
        val agent = Agent("default", "Default", systemPrompt = "", defaultModelId = null, temperature = 0.7f, topP = 1f,
            maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1)
        val agents = fake<AgentRepository> { if (it == "getAll") flowOf(listOf(agent)) else null }
        val core = fake<CoreBridge> {
            when (it) { "learningRequest" -> "{\"tools\":[]}"; "agentDelegationEnabled" -> true; else -> null }
        }
        var cleared = false
        var shutdowns = 0
        val backend = AppBackend(LocalStores(cloud, AppPreferences(prefs), ThemePreferences(prefs)), core,
            fake(), fake(), agents, fake(), fake(), fake(), fake(), fake(),
            shutdown = { shutdowns++ }, clearLocalData = { cleared = true })
        val dirs = AppDirs("/unused/files".toPath(), "/unused/cache".toPath())
        val previous = CoreBridgeRegistry.bridge
        try {
            val unrelatedBridge = fake<CoreBridge> { if (it == "agentDelegationEnabled") false else null }
            CoreBridgeRegistry.bridge = unrelatedBridge
            val container = AppContainer(dirs, fake<ChatImageStore>(), backend)
            withTimeout(5_000) { container.mcpManager.agentDelegationEnabled.first { it } }
            assertSame(backend.stores, container.stores)
            assertSame(core, container.coreBridge)
            assertSame(backend.providers, container.providerRepository)
            assertSame(agents, container.agentRepository)
            assertSame(backend.learning, container.learningRepository)
            container.clearAllDataAndReinit()
            assertTrue(cleared)
            container.shutdown()
            container.shutdown()
            assertSame(unrelatedBridge, CoreBridgeRegistry.bridge)
            assertTrue(shutdowns == 1)
            // Equal identity does not make an injected container the registry owner.
            CoreBridgeRegistry.bridge = core
            val second = AppContainer(dirs, fake<ChatImageStore>(), backend)
            second.shutdown()
            assertSame(core, CoreBridgeRegistry.bridge)
            assertTrue(shutdowns == 2)
        } finally { CoreBridgeRegistry.bridge = previous }
    }

    @Test fun failedShutdownDoesNotPoisonOwnerButStoppedOwnerRejectsClear(): Unit = runBlocking {
        val prefs = object : DataStore<Preferences> {
            private val state = MutableStateFlow<Preferences>(emptyPreferences())
            override val data: Flow<Preferences> = state
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(state.value).also { state.value = it }
        }
        val cloud = fake<CloudFacade> { if (it == "getUser") flowOf(null) else null }
        val agent = Agent("default", "Default", systemPrompt = "", defaultModelId = null, temperature = 0.7f, topP = 1f,
            maxTokens = null, isDefault = true, createdAt = 1, updatedAt = 1)
        var failShutdown = true
        var shutdowns = 0
        val backend = AppBackend(LocalStores(cloud, AppPreferences(prefs), ThemePreferences(prefs)),
            fake<CoreBridge> { if (it == "learningRequest") "{\"tools\":[]}" else null },
            fake(), fake(), fake<AgentRepository> { if (it == "getAll") flowOf(listOf(agent)) else null },
            fake(), fake(), fake(), fake(), fake(),
            shutdown = { shutdowns++; check(!failShutdown) { "worker stop is unverified" } },
            clearLocalData = { error("clear must not run after shutdown") })
        val container = AppContainer(AppDirs("/unused/files".toPath(), "/unused/cache".toPath()),
            fake<ChatImageStore>(), backend)
        assertFalse(container.shutdownCompleted.value)
        assertFailsWith<IllegalStateException> { container.shutdown() }
        assertFalse(container.shutdownCompleted.value)
        failShutdown = false
        container.shutdown()
        assertTrue(container.shutdownCompleted.value)
        container.shutdown()
        assertFailsWith<IllegalStateException> { container.clearAllDataAndReinit() }
        assertTrue(shutdowns == 2)
    }
}
