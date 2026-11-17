/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import androidx.test.platform.app.InstrumentationRegistry
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Comparator
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Filesystem portability check only; both directories belong to the same Runtime UID. */
class WorkspaceTransferDeviceTest {
    @Test fun importsCandidateWithoutChangingOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.filesDir.toPath(), "workspace-transfer-device-")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val nested = Files.createDirectory(source.resolve("nested"))
            val script = nested.resolve("script")
            Files.write(script, "echo hello\n".toByteArray())
            assertTrue(script.toFile().setExecutable(true, true))
            val bytes = ByteArrayOutputStream()
            val sourceFd = Os.open(source.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try { WorkspaceTransfer.export(sourceFd, bytes) } finally { Os.close(sourceFd) }
            val target = root.resolve("candidate")
            WorkspaceTransfer.import(ByteArrayInputStream(bytes.toByteArray()), target)
            assertEquals("echo hello\n", String(Files.readAllBytes(target.resolve("nested/script"))))
            assertTrue(Files.isExecutable(target.resolve("nested/script")))
            assertEquals("echo hello\n", String(Files.readAllBytes(script)))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun rejectsSymlinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.filesDir.toPath(), "workspace-links-device-")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val outside = root.resolve("outside")
            Files.write(outside, "outside".toByteArray())
            val fd = Os.open(source.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try {
                val link = source.resolve("link")
                Files.createSymbolicLink(link, outside)
                try { WorkspaceTransfer.export(fd, ByteArrayOutputStream()); fail("Expected symlink rejection") }
                catch (_: IllegalStateException) { }
            } finally { Os.close(fd) }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun rejectsHardlinkFromPrivilegedTestFixture() {
        val fixture = InstrumentationRegistry.getArguments().getString("workspaceHardlinkFixture")
        assumeNotNull(fixture)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = java.nio.file.Paths.get(checkNotNull(fixture)).toAbsolutePath().normalize()
        require(source.parent.parent == context.filesDir.toPath().toAbsolutePath().normalize())
        val stat = Os.lstat(source.resolve("link").toString())
        assertTrue("Fixture must be a real app-owned hardlink", OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink > 1 && stat.st_uid == Os.getuid())
        val fd = Os.open(source.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try {
            try { WorkspaceTransfer.export(fd, ByteArrayOutputStream()); fail("Expected hardlink rejection") }
            catch (_: IllegalArgumentException) { }
        } finally { Os.close(fd) }
    }

    @Test fun renamedAncestorCannotRedirectPinnedDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.filesDir.toPath(), "workspace-swap-device-")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val inner = Files.createDirectory(source.resolve("inner"))
            Files.write(inner.resolve("file"), "inside".toByteArray())
            val outside = Files.createDirectory(root.resolve("outside"))
            Files.write(outside.resolve("file"), "outside".toByteArray())
            val output = ByteArrayOutputStream()
            val swapping = object : java.io.FilterOutputStream(output) {
                var swapped = false
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (!swapped && String(bytes, offset, length).contains("inner")) {
                        swapped = true
                        Files.move(inner, root.resolve("moved"))
                        Files.createSymbolicLink(inner, outside)
                    }
                    out.write(bytes, offset, length)
                }
            }
            val fd = Os.open(source.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try { WorkspaceTransfer.export(fd, swapping) } finally { Os.close(fd) }
            assertTrue(swapping.swapped)
            val target = root.resolve("candidate")
            WorkspaceTransfer.import(ByteArrayInputStream(output.toByteArray()), target)
            assertEquals("inside", String(Files.readAllBytes(target.resolve("inner/file"))))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test fun excessivelyDeepWorkspaceIsRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.filesDir.toPath(), "workspace-depth-device-")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            var directory = source
            repeat(WorkspaceTransfer.MAX_DEPTH + 1) { directory = Files.createDirectory(directory.resolve("d")) }
            val fd = Os.open(source.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            try {
                try { WorkspaceTransfer.export(fd, ByteArrayOutputStream()); fail("Expected depth rejection") }
                catch (_: IllegalArgumentException) { }
            } finally { Os.close(fd) }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
