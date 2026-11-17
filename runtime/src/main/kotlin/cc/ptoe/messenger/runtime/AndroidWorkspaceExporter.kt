/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.DataOutputStream
import java.io.FileDescriptor
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest

/** Traverses only from a previously authorized directory descriptor, never from its pathname. */
internal object AndroidWorkspaceExporter {
    // Linux O_PATH pins an inode without opening a potentially dangerous device or FIFO.
    private const val O_PATH = 0x200000

    fun export(source: FileDescriptor, output: OutputStream) {
        require(OsConstants.S_ISDIR(Os.fstat(source).st_mode)) { "Workspace descriptor is not a directory" }
        val data = DataOutputStream(output)
        data.writeInt(WorkspaceTransfer.MAGIC)
        var entries = 0
        var total = 0L

        fun walk(directory: FileDescriptor, prefix: String) {
            ParcelFileDescriptor.dup(directory).use { pinnedDirectory ->
                val procDirectory = "/proc/self/fd/${pinnedDirectory.fd}"
                Files.newDirectoryStream(Paths.get(procDirectory)).use { names ->
                    for (entry in names) {
                        val name = entry.fileName.toString()
                        val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                        WorkspaceTransfer.validateRelative(relative)
                        require(++entries <= WorkspaceTransfer.MAX_ENTRIES) { "Too many workspace entries" }
                        // Every path component is opened from a still-live directory descriptor.
                        val pinned = Os.open("$procDirectory/$name", O_PATH or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
                        try {
                            val stat = Os.fstat(pinned)
                            when {
                                OsConstants.S_ISDIR(stat.st_mode) -> {
                                    data.writeByte(WorkspaceTransfer.DIRECTORY)
                                    data.writeUTF(relative)
                                    walk(pinned, relative)
                                }
                                OsConstants.S_ISREG(stat.st_mode) -> {
                                    require(stat.st_nlink == 1L) { "Hard-linked workspace files are unsupported" }
                                    require(stat.st_size in 0..WorkspaceTransfer.MAX_FILE_BYTES) { "Workspace file exceeds limit" }
                                    total = Math.addExact(total, stat.st_size)
                                    require(total <= WorkspaceTransfer.MAX_TOTAL_BYTES) { "Workspace transfer limit exceeded" }
                                    data.writeByte(WorkspaceTransfer.FILE)
                                    data.writeUTF(relative)
                                    data.writeBoolean((stat.st_mode and OsConstants.S_IXUSR) != 0)
                                    data.writeLong(stat.st_size)
                                    val digest = MessageDigest.getInstance("SHA-256")
                                    ParcelFileDescriptor.dup(pinned).use { pinnedFile ->
                                        // This procfs link names the pinned inode itself, not an attacker-controlled path.
                                        val reader = Os.open("/proc/self/fd/${pinnedFile.fd}", OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
                                        try {
                                            val opened = Os.fstat(reader)
                                            check(opened.st_dev == stat.st_dev && opened.st_ino == stat.st_ino &&
                                                OsConstants.S_ISREG(opened.st_mode) && opened.st_nlink == 1L) { "Workspace file changed" }
                                            var remaining = stat.st_size
                                            val buffer = ByteArray(64 * 1024)
                                            while (remaining > 0) {
                                                val count = Os.read(reader, buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                                check(count > 0) { "Workspace file changed during export" }
                                                data.write(buffer, 0, count)
                                                digest.update(buffer, 0, count)
                                                remaining -= count
                                            }
                                            val after = Os.fstat(reader)
                                            check(after.st_dev == opened.st_dev && after.st_ino == opened.st_ino &&
                                                after.st_size == stat.st_size && after.st_nlink == 1L &&
                                                after.st_mtim == stat.st_mtim && after.st_ctim == stat.st_ctim) {
                                                "Workspace file changed during export"
                                            }
                                        } finally { Os.close(reader) }
                                    }
                                    data.write(digest.digest())
                                }
                                else -> error("Workspace contains an unsupported entry")
                            }
                        } finally { Os.close(pinned) }
                    }
                }
            }
        }
        walk(source, "")
        data.writeByte(WorkspaceTransfer.END)
        data.flush()
    }
}
