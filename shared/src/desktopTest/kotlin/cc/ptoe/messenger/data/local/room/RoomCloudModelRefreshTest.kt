/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.room

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.cloud.BUILTIN_PROVIDER_ID
import cc.ptoe.messenger.data.cloud.CloudImageRehydrator
import cc.ptoe.messenger.data.cloud.RoomAvatarCache
import cc.ptoe.messenger.data.cloud.RoomCloudFacade
import cc.ptoe.messenger.data.cloud.RoomCloudHttpClient
import cc.ptoe.messenger.data.local.AppPreferences
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomCloudModelRefreshTest {
    private class MemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun refreshLoadsModelsAndPreservesSessionAndCatalogOnAiKeyRejection(): Unit = runBlocking {
        val failModels = AtomicBoolean(false)
        val received = ConcurrentLinkedQueue<Pair<String?, String?>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun com.sun.net.httpserver.HttpExchange.respond(status: Int, text: String) {
            val bytes = text.toByteArray()
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
        val user = """{"user":{"id":"account","email":"a@example.test","aiApiKey":"secret-key"}}"""
        server.createContext("/api/auth/login") { exchange ->
            exchange.requestBody.readBytes()
            exchange.responseHeaders.add("Set-Cookie", "messenger_session=session; Path=/; HttpOnly")
            exchange.respond(200, user)
        }
        server.createContext("/api/auth/me") { it.respond(200, user) }
        server.createContext("/v1/models") { exchange ->
            received.add(exchange.requestHeaders.getFirst("Authorization") to exchange.requestHeaders.getFirst("Cookie"))
            if (failModels.get()) exchange.respond(401, "key rejected")
            else exchange.respond(200, """{"data":[{"id":"model-one","display_name":"Model One"}]}""")
        }
        server.start()
        val dir = Files.createTempDirectory("room-cloud-model-refresh")
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val facade = RoomCloudFacade.open(db, AppPreferences(MemoryPreferences()), RoomCloudHttpClient(),
            RoomAvatarCache(dir.resolve("avatars").toString().toPath()), CloudImageRehydrator(dir.resolve("images")),
            "http://127.0.0.1:${server.address.port}")
        try {
            facade.login("a@example.test", "password")
            assertEquals("account", facade.refreshUser().id)
            assertEquals(listOf("model-one"), db.cloudSync().providerModels(BUILTIN_PROVIDER_ID).map { it.modelId })
            assertEquals(listOf("Bearer secret-key" to null), received.toList())
            assertEquals(1L, facade.syncBuiltinModels(force = true))
            failModels.set(true)
            assertFailsWith<IllegalStateException> { facade.syncBuiltinModels(force = true) }
            assertFailsWith<IllegalStateException> { facade.refreshUser() }
            assertEquals("account", facade.user.first()?.id)
            assertFalse(facade.sessionExpired.value)
            assertEquals(listOf("model-one"), db.cloudSync().providerModels(BUILTIN_PROVIDER_ID).map { it.modelId })
            assertTrue(facade.syncError.value.orEmpty().contains("401"))
            assertFalse(facade.syncError.value.orEmpty().contains("secret-key"))
            assertEquals(4, received.size)
        } finally {
            facade.shutdown(); db.close(); server.stop(0); dir.toFile().deleteRecursively()
        }
    }
}
