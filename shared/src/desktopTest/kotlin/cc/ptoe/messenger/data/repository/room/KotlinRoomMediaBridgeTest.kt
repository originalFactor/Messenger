/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.asset.CoreAssetGate
import cc.ptoe.messenger.data.local.asset.CoreAssetOwner
import cc.ptoe.messenger.data.local.asset.CoreAssetService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.ProviderRow
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinRoomMediaBridgeTest {
    private class Gate : CoreAssetGate {
        private val mutex = Mutex()
        override suspend fun <T> withExclusiveAssets(block: suspend () -> T): T = mutex.withLock { block() }
    }

    @Test fun mediaDataAndSettingsUseRoomWithoutGivingSandboxExecution(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-media-bridge")
        val root = Files.createDirectory(dir.resolve("assets"))
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        val delegate = CoreBridge::class.java.cast(Proxy.newProxyInstance(CoreBridge::class.java.classLoader,
            arrayOf(CoreBridge::class.java)) { _, _, _ -> error("Unexpected fallback to another backend") })
        val assets = CoreAssetService(root, db.assetMetadata(), Gate())
        val bridge = KotlinRoomMediaBridge(delegate, db, assets, RoomArtifactService(db, assets)) { 123L }
        fun parse(value: String) = Json.parseToJsonElement(value)
        try {
            db.rows().upsertProviders(listOf(ProviderRow("p", "Provider", "https://example.com/v1", "secret-key", "chat", false, 1, 1)))
            val asset = assets.importFile("resource".encodeToByteArray(), "text/plain", CoreAssetOwner("learning", "a"), 1)
            assertContentEquals("resource".encodeToByteArray(), bridge.learningAsset(asset.id))
            val reference = bridge.learningRequest("POST", "/assets/${asset.id}/references", """{"ownerType":"learning","ownerId":"b"}""")
            assertEquals(2, parse(reference).jsonObject.getValue("references").jsonArray.size)
            assertEquals("text/plain", parse(bridge.learningRequest("GET", "/assets/${asset.id}")).jsonObject.getValue("mimeType").jsonPrimitive.content)

            val submission = """{"title":"Example","files":{"index.html":"<p>hello</p>"}}"""
            val saved = parse(bridge.learningRequest("POST", "/artifacts", submission)).jsonObject
            val id = saved.getValue("id").jsonPrimitive.content
            val list = parse(bridge.learningRequest("GET", "/artifacts")).jsonObject.getValue("items").jsonArray
            assertEquals(id, list.single().jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("Example", parse(bridge.learningRequest("GET", "/artifacts/$id")).jsonObject.getValue("title").jsonPrimitive.content)
            bridge.learningRequest("PUT", "/artifacts/settings", """{"enabled":true}""")
            assertEquals("true", parse(bridge.learningRequest("GET", "/artifacts/settings")).jsonObject.getValue("enabled").jsonPrimitive.content)
            assertFailsWith<IllegalArgumentException> {
                bridge.learningRequest("PUT", "/artifacts/$id", """{"id":"other","title":"Bad","files":{"index.html":"x"}}""")
            }
            assertFailsWith<IllegalStateException> { bridge.learningRequest("POST", "/artifacts/$id/fetch", "{}") }
            assertFailsWith<IllegalStateException> { bridge.learningRequest("POST", "/artifacts/$id/authorize-run", "{}") }

            val profile = """{"id":"profile","name":"Images","providerId":"p","modelId":"image-model","enabled":true}"""
            bridge.learningRequest("PUT", "/image-generation/profiles/profile", profile)
            val profiles = bridge.learningRequest("GET", "/image-generation/profiles")
            assertEquals("profile", parse(profiles).jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
            assertFalse(profiles.contains("secret-key"))
            bridge.learningRequest("PUT", "/image-generation/capability", """{"enabled":true}""")
            assertEquals("true", parse(bridge.learningRequest("GET", "/image-generation/capability")).jsonObject.getValue("enabled").jsonPrimitive.content)
            assertTrue(parse(bridge.learningRequest("GET", "/image-generation/jobs")).jsonArray.isEmpty())
            assertFailsWith<IllegalStateException> { bridge.learningRequest("POST", "/image-generation/jobs", "{}") }
            bridge.learningRequest("DELETE", "/image-generation/profiles/profile")
            assertTrue(parse(bridge.learningRequest("GET", "/image-generation/profiles")).jsonArray.isEmpty())
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }

    @Test fun referenceRequiresVerifiedBackingBytes(): Unit = runBlocking {
        val dir = Files.createTempDirectory("room-media-reference")
        val root = Files.createDirectory(dir.resolve("assets"))
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        }
        val db = Room.databaseBuilder<LocalDatabase>(name = dir.resolve("room.db").toString())
            .setDriver(BundledSQLiteDriver()).build()
        try {
            val assets = CoreAssetService(root, db.assetMetadata(), Gate())
            val asset = assets.importFile("original".encodeToByteArray(), "text/plain", CoreAssetOwner("learning", "a"), 1)
            Files.writeString(root.resolve(asset.id), "modified")
            assertFailsWith<IllegalStateException> { assets.retain(asset.id, CoreAssetOwner("learning", "b")) }
            assertEquals(listOf(CoreAssetOwner("learning", "a")), assets.metadata(asset.id).references)
        } finally { db.close(); dir.toFile().deleteRecursively() }
    }
}
