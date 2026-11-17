/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Unconnected workspace transfer primitive. The source may change between entries; a successful
 * export is a candidate for review, not an atomic snapshot or evidence that old workers stopped.
 * The caller must make the output independent of the source and keep the import destination
 * private from every other writer. A directory owned by the old Runtime UID cannot do that.
 * Hashes detect corruption but do not authenticate the archive or establish source completeness.
 * Neither function closes the caller's stream. Incomplete output and imports must not be used.
 */
internal object WorkspaceTransfer {
    internal const val MAGIC = 0x4d575331 // MWS1
    internal const val END = 0
    internal const val DIRECTORY = 1
    internal const val FILE = 2
    internal const val MAX_ENTRIES = 10_000
    internal const val MAX_PATH_BYTES = 4_096
    internal const val MAX_DEPTH = 64
    internal const val MAX_FILE_BYTES = 128L * 1024 * 1024
    internal const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

    /** The descriptor must already identify the caller-authorized source directory. */
    fun export(source: java.io.FileDescriptor, output: OutputStream) =
        AndroidWorkspaceExporter.export(source, output)

    /** Creates a new staging directory; on any failure it is retained and must not be published. */
    fun import(input: InputStream, destination: Path) {
        val target = destination.toAbsolutePath().normalize()
        val data = DataInputStream(input)
        require(data.readInt() == MAGIC) { "Invalid workspace transfer" }
        Files.createDirectory(target)
        val seen = mutableSetOf<String>()
        var total = 0L
        while (true) {
            val kind = data.readUnsignedByte()
            if (kind == END) {
                require(data.read() == -1) { "Trailing workspace transfer data" }
                return
            }
            require(kind == DIRECTORY || kind == FILE) { "Unsupported workspace entry" }
            val name = data.readUTF()
            validateRelative(name)
            require(seen.add(name) && seen.size <= MAX_ENTRIES) { "Duplicate or excessive workspace entries" }
            val path = target.resolve(name)
            require(Files.isDirectory(path.parent, NOFOLLOW_LINKS)) { "Missing workspace parent" }
            if (kind == DIRECTORY) {
                Files.createDirectory(path)
            } else {
                val executable = data.readBoolean()
                val size = data.readLong()
                require(size in 0..MAX_FILE_BYTES) { "Workspace file exceeds limit" }
                total = Math.addExact(total, size)
                require(total <= MAX_TOTAL_BYTES) { "Workspace transfer limit exceeded" }
                val hash = MessageDigest.getInstance("SHA-256")
                Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use {
                    copyExactly(data, it, size, hash)
                }
                val expected = ByteArray(32)
                data.readFully(expected)
                check(MessageDigest.isEqual(expected, hash.digest())) { "Workspace file hash mismatch" }
                if (executable) check(path.toFile().setExecutable(true, true)) { "Cannot restore executable flag" }
            }
        }
    }

    internal fun validateRelative(name: String) {
        require(name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size <= MAX_PATH_BYTES &&
            !name.startsWith('/') && !name.contains('\\') && !name.contains('\u0000') &&
            name.split('/').let { parts -> parts.size <= MAX_DEPTH && parts.all { it.isNotEmpty() && it != "." && it != ".." } }) {
            "Invalid workspace path"
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, size: Long, hash: MessageDigest) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            check(read > 0) { "Truncated workspace transfer" }
            output.write(buffer, 0, read)
            hash.update(buffer, 0, read)
            remaining -= read
        }
    }
}
