/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okio.ByteString.Companion.encodeUtf8

/** Only called after Room validates the session and skips conversations with pending edits. */
internal class CloudImageRehydrator(private val directory: Path) {
    companion object {
        /** Low-level callers must supply a stager for image-bearing cloud conversations. */
        fun rejectUnstagedImages(doc: JsonObject): JsonObject {
            for (element in (doc["messages"] as? JsonArray).orEmpty()) {
                val message = element as? JsonObject ?: error("Invalid cloud message")
                val raw = (message["partsJson"] as? JsonPrimitive)?.contentOrNull
                if (raw.isNullOrBlank()) continue
                val parts = Json.parseToJsonElement(raw) as? JsonArray ?: error("Invalid cloud message parts")
                check(parts.none { ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "image" }) {
                    "Cloud image storage is required"
                }
            }
            return doc
        }
    }
    internal fun stage(doc: JsonObject, created: MutableList<Path>): JsonObject {
        val messages = doc["messages"] as? JsonArray ?: return doc
        val rewritten = messages.map { element ->
            val message = element as? JsonObject ?: error("Invalid cloud message")
            val partsText = (message["partsJson"] as? JsonPrimitive)?.contentOrNull
            if (partsText.isNullOrBlank()) return@map message
            val parts = Json.parseToJsonElement(partsText) as? JsonArray ?: error("Invalid cloud message parts")
            val id = (message["id"] as? JsonPrimitive)?.contentOrNull ?: error("Cloud message is missing id")
            val staged = parts.mapIndexed partMap@ { index, item ->
                val part = item as? JsonObject ?: return@partMap item
                if ((part["type"] as? JsonPrimitive)?.contentOrNull != "image") return@partMap item
                val uri = (part["dataUri"] as? JsonPrimitive)?.contentOrNull ?: error("Cloud image has no data URI")
                val comma = uri.indexOf(',')
                check(comma in 1..100 && uri.length <= 24 * 1024 * 1024) { "Invalid cloud image data URI" }
                val extension = when (uri.substring(0, comma).lowercase()) {
                    "data:image/png;base64" -> ".png"
                    "data:image/jpeg;base64", "data:image/jpg;base64" -> ".jpg"
                    "data:image/webp;base64" -> ".webp"
                    "data:image/gif;base64" -> ".gif"
                    else -> error("Unsupported cloud image format")
                }
                val bytes = Base64.getDecoder().decode(uri.substring(comma + 1))
                check(bytes.isNotEmpty() && bytes.size <= 16 * 1024 * 1024) { "Invalid cloud image size" }
                Files.createDirectories(directory)
                check(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Invalid chat image directory" }
                val hash = "$id|$index|$uri".encodeUtf8().sha256().hex()
                val target = directory.resolve("sync_$hash$extension")
                if (!Files.exists(target, NOFOLLOW_LINKS)) {
                    var used = 0L
                    Files.newDirectoryStream(directory).use { entries ->
                        for (path in entries) {
                            val attrs = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                            check(attrs.isRegularFile && !attrs.isSymbolicLink) { "Invalid chat image file" }
                            used = Math.addExact(used, attrs.size())
                        }
                    }
                    check(used <= 512L * 1024 * 1024 - bytes.size) { "Chat image storage quota exceeded" }
                    val temp = Files.createTempFile(directory, ".sync-", ".tmp")
                    created.add(temp)
                    FileChannel.open(temp, StandardOpenOption.WRITE).use { channel ->
                        val buffer = java.nio.ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) check(channel.write(buffer) > 0) { "Image write made no progress" }
                        channel.force(true)
                    }
                    try {
                        Files.createLink(target, temp)
                        created.add(target)
                    } catch (_: java.nio.file.FileAlreadyExistsException) { /* Already staged by another writer. */ }
                    Files.deleteIfExists(temp)
                    created.remove(temp)
                }
                check(Files.isRegularFile(target, NOFOLLOW_LINKS) && Files.size(target) == bytes.size.toLong() && Files.readAllBytes(target).contentEquals(bytes)) {
                    "Cloud image destination is invalid"
                }
                JsonObject(part + ("localPath" to JsonPrimitive(target.toAbsolutePath().toString())))
            }
            JsonObject(message + ("partsJson" to JsonPrimitive(JsonArray(staged).toString())))
        }
        return JsonObject(doc + ("messages" to JsonArray(rewritten)))
    }

    internal fun syncDirectory() = FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    internal fun cleanCreated(created: List<Path>) {
        for (path in created.asReversed()) Files.deleteIfExists(path)
    }
}
