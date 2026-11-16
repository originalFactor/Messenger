/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.system.ErrnoException
import java.io.FileDescriptor
import java.nio.file.Path
import java.security.MessageDigest

/** Isolated primitives; production requires an independently proven lifetime ownership gate. */
internal object AndroidRoomFiles {
    private fun same(a: StructStat, b: StructStat) = a.st_dev == b.st_dev && a.st_ino == b.st_ino
    private fun validFile(s: StructStat) = OsConstants.S_ISREG(s.st_mode) && s.st_uid == Os.getuid() &&
        s.st_nlink == 1L && (s.st_mode and 63) == 0
    private fun flags(base: Int) = base or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC

    private inline fun <T> opened(path: Path, flags: Int, mode: Int = 0, block: (FileDescriptor) -> T): T {
        val fd = Os.open(path.toString(), flags(flags), mode)
        var failure: Throwable? = null
        try { return block(fd) } catch (error: Throwable) { failure = error; throw error }
        finally {
            try { Os.close(fd) } catch (closeFailure: Throwable) {
                if (failure == null) throw closeFailure else failure.addSuppressed(closeFailure)
            }
        }
    }

    fun syncPrivateDirectory(parent: Path) {
        val before = Os.lstat(parent.toString())
        require(OsConstants.S_ISDIR(before.st_mode) && before.st_uid == Os.getuid() && (before.st_mode and 63) == 0)
        opened(parent, OsConstants.O_RDONLY) { fd ->
            val actual = Os.fstat(fd)
            check(OsConstants.S_ISDIR(actual.st_mode) && same(before, actual))
            Os.fsync(fd)
            check(same(actual, Os.lstat(parent.toString())))
        }
    }

    /** Never overwrite; failure intentionally leaves target for explicit offline recovery. */
    fun copyExclusive(source: Path, target: Path) {
        require(source.parent != null && target.parent != null)
        syncPrivateDirectory(target.parent)
        opened(source, OsConstants.O_RDONLY) { input ->
            val initial = Os.fstat(input)
            check(validFile(initial) && same(initial, Os.lstat(source.toString())))
            opened(target, OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_RDWR, 384) { output ->
                val created = Os.fstat(output)
                check(validFile(created) && same(created, Os.lstat(target.toString())))
                val expected = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                var length = 0L
                while (true) {
                    val n = Os.read(input, buffer, 0, buffer.size)
                    if (n == 0) break
                    check(n > 0)
                    expected.update(buffer, 0, n)
                    var offset = 0
                    while (offset < n) {
                        val written = Os.write(output, buffer, offset, n - offset)
                        check(written > 0)
                        offset += written
                    }
                    length += n
                }
                val after = Os.fstat(input)
                check(same(initial, after) && initial.st_size == after.st_size &&
                    initial.st_mtime == after.st_mtime && initial.st_ctime == after.st_ctime && length == after.st_size)
                Os.fsync(output)
                check(Os.lseek(output, 0, OsConstants.SEEK_SET) == 0L)
                val actual = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                while (true) {
                    val n = Os.read(output, buffer, 0, buffer.size)
                    if (n == 0) break
                    check(n > 0)
                    actual.update(buffer, 0, n)
                    copied += n
                }
                check(copied == length && MessageDigest.isEqual(expected.digest(), actual.digest()))
                val afterCopy = Os.fstat(output)
                check(validFile(afterCopy) && same(created, afterCopy) && same(afterCopy, Os.lstat(target.toString())))
            }
        }
        syncPrivateDirectory(target.parent)
    }

    /** Call only after verifying the copied database and its unique ID. No overwrite or auto-delete. */
    fun createReceiptExclusive(receipt: Path, transactionId: String) {
        require(transactionId.matches(Regex("[0-9a-f]{32}")))
        val bytes = ("messenger-room-v1:" + transactionId + "\n").toByteArray(Charsets.US_ASCII)
        opened(receipt, OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_WRONLY, 384) { fd ->
            val created = Os.fstat(fd)
            check(validFile(created) && same(created, Os.lstat(receipt.toString())))
            var offset = 0
            while (offset < bytes.size) {
                val n = Os.write(fd, bytes, offset, bytes.size - offset)
                check(n > 0)
                offset += n
            }
            Os.fsync(fd)
            check(same(created, Os.lstat(receipt.toString())))
        }
        syncPrivateDirectory(requireNotNull(receipt.parent))
    }

    /** A caller must compare the returned ID with Room's transactional marker before admission. */
    fun readReceipt(receipt: Path): String {
        val prefix = "messenger-room-v1:"
        val expectedSize = prefix.length + 32 + 1
        return opened(receipt, OsConstants.O_RDONLY) { fd ->
            val before = Os.fstat(fd)
            check(validFile(before) && before.st_size == expectedSize.toLong() && same(before, Os.lstat(receipt.toString())))
            val actual = ByteArray(expectedSize)
            var offset = 0
            while (offset < actual.size) {
                val read = Os.read(fd, actual, offset, actual.size - offset)
                check(read > 0)
                offset += read
            }
            val after = Os.fstat(fd)
            check(same(before, after) && after.st_size == before.st_size && same(after, Os.lstat(receipt.toString())))
            val text = actual.toString(Charsets.US_ASCII)
            check(text.startsWith(prefix) && text.endsWith("\n"))
            val id = text.substring(prefix.length, text.length - 1)
            check(id.matches(Regex("[0-9a-f]{32}")))
            id
        }
    }

    fun verifyReceipt(receipt: Path, transactionId: String) {
        require(transactionId.matches(Regex("[0-9a-f]{32}")))
        check(readReceipt(receipt) == transactionId)
    }

    private fun requireAbsent(path: Path) {
        try {
            Os.lstat(path.toString())
            error("Destination already exists: $path")
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ENOENT) throw error
        }
    }

    /**
     * Caller holds lifetime writer exclusion. verifyAndFinalize must check SQLite integrity,
     * Room schema and default-agent invariant, compare the transactional ID, checkpoint and
     * close every connection. On any failure target/work remain for explicit recovery.
     */
    suspend fun publishExclusive(
        staged: Path,
        target: Path,
        receipt: Path,
        transactionId: String,
        verifyAndFinalize: suspend (Path, String) -> Unit,
    ) {
        require(transactionId.matches(Regex("[0-9a-f]{32}")))
        require(staged.toAbsolutePath().normalize() != target.toAbsolutePath().normalize())
        require(target.parent.toAbsolutePath().normalize() == receipt.parent.toAbsolutePath().normalize())
        require(target.toAbsolutePath().normalize() != receipt.toAbsolutePath().normalize())
        syncPrivateDirectory(target.parent)
        requireAbsent(receipt)
        copyExclusive(staged, target) // exclusive creation + byte hash, file fsync, directory fsync
        val copied = Os.lstat(target.toString())
        check(validFile(copied))
        verifyAndFinalize(target, transactionId)
        opened(target, OsConstants.O_RDWR) { fd ->
            val final = Os.fstat(fd)
            check(validFile(final) && same(copied, final) && same(final, Os.lstat(target.toString())))
            Os.fsync(fd)
        }
        createReceiptExclusive(receipt, transactionId)
    }

    /** Caller holds the same lifetime gate, and validates both Room and its transaction ID. */
    suspend fun verifyExisting(
        target: Path,
        receipt: Path,
        verifyDatabase: suspend (Path, String) -> Unit,
    ) {
        require(target.parent.toAbsolutePath().normalize() == receipt.parent.toAbsolutePath().normalize())
        val id = readReceipt(receipt) // never open an incomplete target when receipt is absent
        val before = Os.lstat(target.toString())
        check(validFile(before))
        verifyDatabase(target, id)
        val after = Os.lstat(target.toString())
        check(validFile(after) && same(before, after))
        syncPrivateDirectory(target.parent)
    }
}
