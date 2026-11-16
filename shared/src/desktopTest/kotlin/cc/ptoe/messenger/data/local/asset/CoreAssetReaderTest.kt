/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.asset

import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreAssetReaderTest {
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val json = Json { encodeDefaults = true }
    @Test fun verifiesRustMetadataContentSizeAndHash() {
        val dir = Files.createTempDirectory("assets-test")
        try {
            val assets = Files.createDirectory(dir.resolve("assets"))
            val bytes = "retained image data".toByteArray()
            val id = hash(bytes)
            Files.write(assets.resolve(id), bytes)
            val raw = """{"id":"$id","sha256":"$id","mimeType":"application/octet-stream","size":${bytes.size},"width":null,"height":null,"createdAt":123,"references":[{"ownerType":"learning","ownerId":"note"}],"unreferencedAt":null}"""
            val reader = CoreAssetReader(assets)
            assertFailsWith<IllegalArgumentException> { reader.read("../$id", raw) }
            assertFailsWith<IllegalArgumentException> { reader.read(id, raw.replace("\"sha256\":\"$id\"", "\"sha256\":\"${"0".repeat(64)}\"")) }
            assertContentEquals(bytes, reader.read(id, raw))
            assertContentEquals(bytes, CoreAssetReader(assets, forcePortable = true).read(id, raw))
            assertFailsWith<IllegalStateException> { reader.read(id, raw.replace("\"size\":${bytes.size}", "\"size\":1")) }
            Files.write(assets.resolve(id), ByteArray(bytes.size) { 65 })
            assertFailsWith<IllegalStateException> { reader.read(id, raw) }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun rejectsSymlinkedFilesAndAssetDirectories() {
        val dir = Files.createTempDirectory("assets-test")
        try {
            val assets = Files.createDirectory(dir.resolve("assets"))
            val bytes = "data".toByteArray()
            val id = hash(bytes)
            val outside = Files.write(dir.resolve("outside"), bytes)
            val meta = CoreAssetMetadata(id, id, "text/plain", bytes.size.toLong(), createdAt = 1, references = emptyList())
            try { Files.createSymbolicLink(assets.resolve(id), outside) } catch (_: UnsupportedOperationException) { return }
            catch (_: java.io.IOException) { return } catch (_: SecurityException) { return }
            assertFailsWith<Exception> { CoreAssetReader(assets).read(id, json.encodeToString(meta)) }
            val linkedRoot = dir.resolve("linked-assets")
            Files.createSymbolicLink(linkedRoot, assets)
            assertFailsWith<Exception> { CoreAssetReader(linkedRoot).read(id, json.encodeToString(meta)) }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun retainingOwnerPreservesSharedReferencesAndNeverDeletes() {
        val id = "0".repeat(64)
        val first = CoreAssetOwner("learning", "first")
        val second = CoreAssetOwner("artifact", "second")
        val asset = CoreAssetMetadata(id, id, "image/png", 3, createdAt = 1, references = listOf(first), unreferencedAt = 12)
        val retained = asset.retainOwner(second)
        assertEquals(listOf(first, second), retained.references)
        assertEquals(null, retained.unreferencedAt)
        assertEquals(listOf(first), asset.references)
        assertEquals(retained, retained.retainOwner(second))
        assertFailsWith<IllegalArgumentException> { asset.retainOwner(CoreAssetOwner("learning", "")) }
        assertTrue(asset.retainOwner(first) === asset)
    }
}
