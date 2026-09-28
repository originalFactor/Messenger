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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ShellCommandPolicyTest {
    @Test
    fun `allows bounded inspection commands`() {
        assertNull(ShellCommandPolicy.rejectionReason("ls -la"))
        assertNull(ShellCommandPolicy.rejectionReason("grep -n 'TODO' src/Main.kt"))
        assertNull(ShellCommandPolicy.rejectionReason("Get-Content 'notes with spaces.txt'"))
        assertNull(ShellCommandPolicy.rejectionReason("find . -type f"))
    }

    @Test
    fun `rejects shell composition and redirection`() {
        assertNotNull(ShellCommandPolicy.rejectionReason("cat notes.txt > copy.txt"))
        assertNotNull(ShellCommandPolicy.rejectionReason("cat notes.txt; rm notes.txt"))
        assertNotNull(ShellCommandPolicy.rejectionReason("cat ${'$'}(pwd)/notes.txt"))
        assertNotNull(ShellCommandPolicy.rejectionReason("cat notes.txt | head"))
    }

    @Test
    fun `rejects interpreters write commands and path escapes`() {
        assertNotNull(ShellCommandPolicy.rejectionReason("python -c 'open(\"x\", \"w\")'"))
        assertNotNull(ShellCommandPolicy.rejectionReason("find . -delete"))
        assertNotNull(ShellCommandPolicy.rejectionReason("sed -i s/old/new/ notes.txt"))
        assertNotNull(ShellCommandPolicy.rejectionReason("cat ../private.txt"))
        assertNotNull(ShellCommandPolicy.rejectionReason("cat /etc/passwd"))
    }

    @Test
    fun `rejects commands that write through output options`() {
        assertNotNull(ShellCommandPolicy.rejectionReason("sort -o result.txt input.txt"))
        assertNotNull(ShellCommandPolicy.rejectionReason("uniq --output=result.txt input.txt"))
    }

    @Test
    fun `rejects malformed quoting`() {
        assertNotNull(ShellCommandPolicy.rejectionReason("cat 'notes.txt"))
        assertNotNull(ShellCommandPolicy.rejectionReason(""))
    }
}
