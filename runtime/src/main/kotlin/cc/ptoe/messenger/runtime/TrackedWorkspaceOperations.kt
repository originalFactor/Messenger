/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** Durable at-most-once tracking. Missing or corrupt markers never prove a call stopped. */
internal class TrackedWorkspaceOperations(
    private val directory: File,
    private val epoch: String = PROCESS_EPOCH,
    /** Must return STOPPED only on a definitive kernel ESRCH; EPERM and errors are UNKNOWN. */
    private val processStatus: (Long) -> Int = { UNKNOWN },
    /** Stable boot and PID namespace identity; null means absence cannot prove exit. */
    private val processNamespace: () -> String? = { null },
) {
    companion object {
        const val UNKNOWN = 0
        const val RUNNING = 1
        const val STOPPED = 2
        private val PROCESS_EPOCH = UUID.randomUUID().toString()
        private val monitor = Any()
    }

    private fun marker(token: String): File {
        require(token.isNotBlank() && token.length <= 512) { "Invalid operation token" }
        return directory.resolve(digest(token))
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun <T> run(token: String, operation: () -> T): T = runTracked(token, "local") { operation() }

    /** The child must be a trusted non-spawning executable; pending identity is never stop proof. */
    fun <T> runChild(token: String, operation: ((Long) -> Unit) -> T): T = runTracked(token, "child-pending", operation)

    private fun <T> runTracked(token: String, initial: String, operation: ((Long) -> Unit) -> T): T {
        val file = marker(token)
        synchronized(monitor) {
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create operation journal" }
            Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                it.write("running:$epoch:$initial".toByteArray(StandardCharsets.UTF_8)); it.flush()
            }
            FileOutputStream(file, true).use { it.fd.sync() }
        }
        try {
            return operation { pid ->
                require(pid in 1..Int.MAX_VALUE.toLong()) { "Invalid child process ID" }
                val namespace = runCatching { processNamespace() }.getOrNull()?.takeIf { it.isNotBlank() }
                val namespaceHash = namespace?.let { digest(it) }
                synchronized(monitor) { writeMarker(file, if (namespaceHash == null) "running:$epoch:child-pending" else "running:$epoch:child:$pid:$namespaceHash") }
            }
        } finally {
            // A failed marker write remains unknown; never turn it into stop proof.
            synchronized(monitor) { runCatching { writeMarker(file, "stopped:$epoch") } }
        }
    }

    private fun writeMarker(file: File, value: String) {
        FileOutputStream(file).use { it.write(value.toByteArray(StandardCharsets.UTF_8)); it.fd.sync() }
    }

    fun status(token: String): Int = synchronized(monitor) {
        val text = try { marker(token).readText(StandardCharsets.UTF_8) } catch (_: Exception) { return@synchronized UNKNOWN }
        val fields = text.split(':')
        if (fields.size < 2 || runCatching { UUID.fromString(fields[1]) }.isFailure) return@synchronized UNKNOWN
        if (fields[0] == "stopped" && fields.size == 2) return@synchronized STOPPED
        if (fields[0] != "running") return@synchronized UNKNOWN
        if (fields[1] == epoch) return@synchronized RUNNING
        when {
            fields.size == 3 && fields[2] == "local" -> STOPPED
            fields.size == 2 -> STOPPED // v1 local-only markers from the same protocol.
            fields.size == 5 && fields[2] == "child" -> {
                val namespace = runCatching { processNamespace() }.getOrNull()?.takeIf { it.isNotBlank() }
                val pid = fields[3].toLongOrNull()?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
                if (namespace == null || digest(namespace) != fields[4] || pid == null) UNKNOWN
                else runCatching { processStatus(pid) }.getOrDefault(UNKNOWN)
            }
            else -> UNKNOWN
        }
    }
}
