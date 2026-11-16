/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.util.randomUuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path

/** Versioned account-scoped files; cache locations never derive directly from remote paths. */
internal class RoomAvatarCache(private val root: Path, private val fs: FileSystem = FileSystem.SYSTEM) {
    private val mutex = Mutex()
    private fun target(base: String, session: SyncSession, kind: String, id: String?, url: String, version: Long?): Path {
        val key = listOf(canonicalCloudBase(base), session.accountId, kind, id.orEmpty(), url, version?.toString().orEmpty()).joinToString("\u0000").encodeUtf8().sha256().hex()
        return root.resolve("$key.avatar")
    }
    fun cached(base: String, session: SyncSession, kind: String, id: String?, url: String?, version: Long?): String? {
        if (url.isNullOrBlank()) return null
        val target = target(base, session, kind, id, url, version)
        return target.toString().takeIf { fs.metadataOrNull(target)?.let { it.isRegularFile && (it.size ?: 0) > 0 } == true }
    }
    suspend fun get(api: RoomCloudApi, session: SyncSession, kind: String, id: String?, url: String?, version: Long?): String? = mutex.withLock {
        if (url.isNullOrBlank()) return@withLock null
        require(api.boundSession == session) { "Avatar session does not match credentials" }
        val target = target(api.boundBase, session, kind, id, url, version)
        val key = target.name
        if (fs.metadataOrNull(target)?.let { it.isRegularFile && (it.size ?: 0) > 0 } == true) return@withLock target.toString()
        val bytes = api.downloadAvatar(kind, id)
        fs.createDirectories(root)
        val temp = root.resolve(".$key.${randomUuid()}.part")
        try {
            fs.write(temp) { write(bytes) }
            fs.atomicMove(temp, target)
        } finally {
            if (fs.exists(temp)) fs.delete(temp)
        }
        target.toString()
    }
}
