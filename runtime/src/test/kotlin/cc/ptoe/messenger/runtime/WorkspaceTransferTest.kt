/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class WorkspaceTransferTest {
    @Test fun importsNestedFilesAndRejectsExistingDestination() {
        val root = Files.createTempDirectory("workspace-transfer-")
        val payload = "echo hello\n".toByteArray()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(0x4d575331)
            writeByte(1); writeUTF("folder")
            writeByte(1); writeUTF("folder/empty")
            writeByte(2); writeUTF("folder/.script"); writeBoolean(true); writeLong(payload.size.toLong())
            write(payload); write(java.security.MessageDigest.getInstance("SHA-256").digest(payload)); writeByte(0)
        }
        val target = root.resolve("candidate")
        WorkspaceTransfer.import(ByteArrayInputStream(bytes.toByteArray()), target)
        assertArrayEquals(payload, Files.readAllBytes(target.resolve("folder/.script")))
        assertTrue(Files.isExecutable(target.resolve("folder/.script")))
        assertTrue(Files.isDirectory(target.resolve("folder/empty")))
        try {
            WorkspaceTransfer.import(ByteArrayInputStream(bytes.toByteArray()), target)
            fail("Import must not overwrite an existing generation")
        } catch (_: java.nio.file.FileAlreadyExistsException) { }
    }

    @Test fun traversalDuplicatesAndCorruptionLeaveOnlyUnpublishedStaging() {
        val root = Files.createTempDirectory("workspace-transfer-invalid-")
        fun archive(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeInt(0x4d575331); block(); flush() }
        }.toByteArray()
        val invalid = listOf(
            archive { writeByte(1); writeUTF("../escape"); writeByte(0) },
            archive { writeByte(1); writeUTF("a"); writeByte(1); writeUTF("a"); writeByte(0) },
            archive { writeByte(2); writeUTF("file"); writeBoolean(false); writeLong(3); write("abc".toByteArray()); write(ByteArray(32)); writeByte(0) },
            archive { writeByte(2); writeUTF("file"); writeBoolean(false); writeLong(10); write("abc".toByteArray()) },
        )
        invalid.forEachIndexed { index, bytes ->
            val target = root.resolve("staging-$index")
            try { WorkspaceTransfer.import(ByteArrayInputStream(bytes), target); fail("Expected rejection") }
            catch (_: Exception) { }
            assertTrue(Files.isDirectory(target))
            assertFalse(Files.exists(root.resolve("escape")))
        }
    }

    @Test fun importRejectsExcessiveDirectoryDepth() {
        val root = Files.createTempDirectory("workspace-import-depth-")
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(0x4d575331)
            var path = ""
            repeat(WorkspaceTransfer.MAX_DEPTH + 1) {
                path = if (path.isEmpty()) "d" else "$path/d"
                writeByte(1); writeUTF(path)
            }
            writeByte(0)
        }
        try {
            WorkspaceTransfer.import(ByteArrayInputStream(bytes.toByteArray()), root.resolve("candidate"))
            fail("Expected depth rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
