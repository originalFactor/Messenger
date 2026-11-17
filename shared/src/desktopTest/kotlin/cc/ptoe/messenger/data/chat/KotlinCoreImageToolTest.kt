/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.image.*
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.repository.room.RoomAgentRepository
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.Agent
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.test.*

class KotlinCoreImageToolTest {
    private class Gate : CoreAssetGate {
        private val lock = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T) = lock.withLock { block() }
    }
    private suspend fun seed(db: LocalDatabase, base: String) {
        val rows = db.rows()
        rows.upsertProviders(listOf(ProviderRow("p", "provider", base, "secret-key", "chat", false, 1, 1)))
        rows.upsertModels(listOf(ModelRow("m", "p", "model", "Model", true, 10000, null, null, null, null, "text", "text", true, false, false, false, 1, null, null)))
        RoomAgentRepository(db).ensureDefaultAgent(Agent("a", "Agent", systemPrompt = "system", defaultModelId = "m", temperature = 0.7f, topP = 1f, maxTokens = null, isDefault = true, toolsEnabled = true, createdAt = 1, updatedAt = 1))
        rows.upsertConversations(listOf(ConversationRow("c", "Chat", "p", "a", null, null, null, null, null, null, null, null, false, 1, 1, null, null, null, 0, 0, 0)))
        db.imageJobs().putProfile(ImageProfile("profile", "Profile", "p", "image-model", enabled = true))
        db.imageJobs().putProfile(ImageProfile("off", "Off", "p", "image-model", enabled = false))
        db.imageJobs().setEnabled(true)
    }
    private suspend fun begin(store: RoomKotlinTurnStore, call: ToolCallData): String {
        val id = assertIs<KotlinTurnAdmission.Accepted>(store.admit(KotlinTurnCommand.Send("c", "draw", null))).turnId
        assertTrue(store.claim(id, false)); store.beginProvider(id); store.completeProvider(id, listOf(call), null)
        store.decideApproval(id, call.callId, true); assertEquals(call, store.beginTool(id))
        return id
    }

    @Test fun failedPaidCallBlocksAnotherRequestInSameTurn() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val calls = AtomicInteger()
        server.createContext("/v1/images/generations") { exchange ->
            calls.incrementAndGet(); exchange.requestBody.readBytes(); exchange.responseHeaders.add("Retry-After", "0")
            val body = "unavailable".toByteArray(); exchange.sendResponseHeaders(503, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val dir = Files.createTempDirectory("core-image-tool")
        val assetsDir = Files.createDirectory(dir.resolve("assets"))
        Files.setPosixFilePermissions(assetsDir, PosixFilePermissions.fromString("rwx------"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        try {
            seed(db, "http://127.0.0.1:${server.address.port}/v1")
            val service = ImageGenerationService(db, CoreAssetService(assetsDir, db.assetMetadata(), Gate()), http, SafeImageDownloader(), dir, ImageGenerationAuthorization {}, { 1000 })
            lateinit var store: RoomKotlinTurnStore
            val tool = coreImageTool(db, "c", { store }, service, ImageGenerationAuthorization { })
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tool), tool)
            val first = ToolCallData("one", "messenger_generate_image", """{"requestId":"request-one","profileId":"profile","prompt":"draw"}""")
            val id = begin(store, first)
            val failed = tool.execute(id, first)
            assertTrue(failed.isError)
            assertTrue(failed.output.contains("unknown"))
            assertEquals(1, calls.get())
            store.completeTool(id, first, failed)
            store.beginProvider(id)
            val second = ToolCallData("two", "messenger_generate_image", """{"requestId":"request-two","profileId":"profile","prompt":"draw again"}""")
            store.completeProvider(id, listOf(second), null); store.decideApproval(id, second.callId, true); store.beginTool(id)
            val blocked = tool.execute(id, second)
            assertTrue(blocked.isError && blocked.output.contains("previous image"))
            assertEquals(1, calls.get(), "failed billable requests must never be repeated automatically")
            store.completeTool(id, second, blocked); store.cancel(id); store.release(id)
        } finally { http.close(); db.close(); server.stop(0); dir.toFile().deleteRecursively() }
    }

    @Test fun profileDiscoveryContainsNoCredentialsOrDisabledProfiles() = runBlocking {
        val dir = Files.createTempDirectory("core-image-profiles")
        val assetsDir = Files.createDirectory(dir.resolve("assets"))
        Files.setPosixFilePermissions(assetsDir, PosixFilePermissions.fromString("rwx------"))
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("store.db").toString()).setDriver(BundledSQLiteDriver()).build()
        val http = ImagesHttpClient()
        try {
            seed(db, "https://example.test/v1")
            val service = ImageGenerationService(db, CoreAssetService(assetsDir, db.assetMetadata(), Gate()), http, SafeImageDownloader(), dir, ImageGenerationAuthorization {}, { 1000 })
            lateinit var store: RoomKotlinTurnStore
            val tool = coreImageTool(db, "c", { store }, service, ImageGenerationAuthorization { })
            store = RoomKotlinTurnStore(db, KotlinProviderRequestPreparer(tool), tool)
            val call = ToolCallData("list", "messenger_generate_image", """{"action":"listProfiles"}""")
            val id = begin(store, call)
            val result = tool.execute(id, call)
            assertFalse(result.isError)
            assertTrue(result.output.contains("profile") && result.output.contains("image-model"))
            assertFalse(result.output.contains("secret-key") || result.output.contains("\"off\""))
            store.completeTool(id, call, result); store.cancel(id); store.release(id)
        } finally { http.close(); db.close(); dir.toFile().deleteRecursively() }
    }
}
