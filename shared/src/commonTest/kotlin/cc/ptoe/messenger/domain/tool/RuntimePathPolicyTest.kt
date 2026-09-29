/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package cc.ptoe.messenger.domain.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimePathPolicyTest {
    @Test
    fun `rejects archive traversal and absolute paths`() {
        assertNull(RuntimePathPolicy.normalizeArchivePath("../escape"))
        assertNull(RuntimePathPolicy.normalizeArchivePath("/absolute"))
        assertNull(RuntimePathPolicy.normalizeArchivePath("C:/absolute"))
        assertEquals("bin/sh", RuntimePathPolicy.normalizeArchivePath("./bin/sh"))
    }

    @Test
    fun `accepts symlinks pointing at directories`() {
        // 真实 bootstrap 形状：lib/terminfo → ../share/terminfo，
        // share/terminfo 是目录（由其下的 terminfo 文件隐含）。
        val links = listOf(
            RuntimePathPolicy.BootstrapSymlink("bin/sh", "bin/busybox"),
            RuntimePathPolicy.BootstrapSymlink("lib/terminfo", "share/terminfo")
        )
        val regular = setOf("bin/busybox", "share/terminfo/x/xterm-256color")
        val directories = setOf("bin", "share", "share/terminfo", "share/terminfo/x")
        RuntimePathPolicy.validateBootstrapLinks(links, regular, directories)
    }

    @Test
    fun `rejects dangling targets and cycles and overwrites`() {
        val regular = setOf("bin/busybox")
        val directories = setOf("bin")

        val dangling = listOf(RuntimePathPolicy.BootstrapSymlink("lib/terminfo", "share/missing"))
        val danglingError = assertFailsWith<IllegalStateException> {
            RuntimePathPolicy.validateBootstrapLinks(dangling, regular, directories)
        }
        assertTrue("Dangling bootstrap symlink: lib/terminfo" in danglingError.message ?: "")

        val cycle = listOf(
            RuntimePathPolicy.BootstrapSymlink("a/b", "c/d"),
            RuntimePathPolicy.BootstrapSymlink("c/d", "a/b")
        )
        val cycleError = assertFailsWith<IllegalStateException> {
            RuntimePathPolicy.validateBootstrapLinks(cycle, regular, directories)
        }
        assertTrue("Cyclic bootstrap symlink" in cycleError.message ?: "")

        val overwrite = listOf(RuntimePathPolicy.BootstrapSymlink("bin/busybox", "bin/busybox"))
        assertTrue(
            "Symlink overwrites archive file" in (assertFailsWith<IllegalStateException> {
                RuntimePathPolicy.validateBootstrapLinks(overwrite, regular, directories)
            }.message ?: "")
        )

        val duplicate = listOf(
            RuntimePathPolicy.BootstrapSymlink("bin/sh", "bin/busybox"),
            RuntimePathPolicy.BootstrapSymlink("bin/sh", "bin/busybox")
        )
        assertTrue(
            "duplicate symlink records" in (assertFailsWith<IllegalStateException> {
                RuntimePathPolicy.validateBootstrapLinks(duplicate, regular, directories)
            }.message ?: "")
        )
    }

    @Test
    fun `remaps Termux absolute and relative symlinks`() {
        assertEquals(
            "share/keyrings/keyring.gpg",
            RuntimePathPolicy.resolveSymlinkTarget(
                "/data/data/com.termux/files/usr/share/keyrings/keyring.gpg",
                "./etc/apt/keyrings/keyring.gpg"
            )
        )
        assertEquals(
            "share/terminfo",
            RuntimePathPolicy.resolveSymlinkTarget("../share/terminfo", "./lib/terminfo")
        )
        assertEquals("dash", RuntimePathPolicy.relativeSymlinkTarget("bin/sh", "bin/dash"))
    }

    @Test
    fun `rejects symlink targets outside Termux prefix`() {
        assertNull(RuntimePathPolicy.resolveSymlinkTarget("/data/data/other/files/usr/bin/sh", "bin/sh"))
        assertNull(RuntimePathPolicy.resolveSymlinkTarget("../../../../escape", "bin/sh"))
    }

    @Test
    fun `workspace check accepts only workspace descendants`() {
        assertTrue(RuntimePathPolicy.isWithinWorkspace("/app/agent-runtime/workspace/file", "/app/agent-runtime/workspace"))
        assertFalse(RuntimePathPolicy.isWithinWorkspace("/app/agent-runtime/other", "/app/agent-runtime/workspace"))
        assertFalse(RuntimePathPolicy.isWithinWorkspace("/app/agent-runtime/workspace/../other", "/app/agent-runtime/workspace"))
    }
}
