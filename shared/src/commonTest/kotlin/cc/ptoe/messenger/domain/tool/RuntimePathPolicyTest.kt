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
