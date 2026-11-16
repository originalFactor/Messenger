/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TrackedGrepProtocolTest {
    @Test fun fixedWrapperAnnouncesExecPidAndPassesPatternAsLiteralArgument() {
        val root = Files.createTempDirectory("tracked-grep-protocol").toFile()
        try {
            val pidFile = root.resolve("pid")
            val argsFile = root.resolve("args")
            val unexpectedFile = root.resolve("injected")
            val executable = root.resolve("fake-rg")
            fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
            executable.writeText("""
                |#!/bin/sh
                |printf '%s' "${'$'}${'$'}" > ${quote(pidFile.absolutePath)}
                |printf '%s\n' "${'$'}@" > ${quote(argsFile.absolutePath)}
                |printf 'file\0001:found\n'
                |""".trimMargin())
            assertTrue(executable.setExecutable(true))
            val pattern = "${'$'}(touch ${unexpectedFile.absolutePath})"
            var announced = -1L
            val result = WorkspaceOps.grep(root, pattern, ".", null, true, false, 10, executable,
                onStarted = { announced = it }, trackedShell = "/bin/sh")
            assertFalse(result.isError)
            assertEquals(pidFile.readText().toLong(), announced)
            assertTrue(argsFile.readLines().contains(pattern))
            assertFalse(unexpectedFile.exists())
            assertTrue(result.output.contains("file:1:found"))
        } finally { root.deleteRecursively() }
    }

    @Test fun timeoutKillsAndJoinsTheStartedExecutableBeforeReturning() {
        val root = Files.createTempDirectory("tracked-grep-timeout").toFile()
        try {
            val executable = root.resolve("slow-rg")
            executable.writeText("#!/bin/sh\nexec /bin/sleep 30\n")
            assertTrue(executable.setExecutable(true))
            var announced = -1L
            val start = System.nanoTime()
            val result = WorkspaceOps.grep(root, "pattern", ".", null, true, false, 10, executable,
                onStarted = { announced = it }, trackedShell = "/bin/sh", timeoutMillis = 300)
            assertTrue(result.isError)
            assertTrue(result.output.contains("timed out"))
            assertTrue("The call must finish after its timeout", (System.nanoTime() - start) < 5_000_000_000L)
            assertTrue(announced > 0)
            assertFalse("The announced process must already be gone", ProcessHandle.of(announced).map { it.isAlive }.orElse(false))
        } finally { root.deleteRecursively() }
    }

    @Test fun drainsLargeStderrWhileReadingSearchOutput() {
        val root = Files.createTempDirectory("tracked-grep-stderr").toFile()
        try {
            val executable = root.resolve("noisy-rg")
            executable.writeText("""
                |#!/bin/sh
                |i=0
                |while [ "${'$'}i" -lt 2048 ]; do
                |  printf '%0128d' 0 >&2
                |  i=${'$'}((i+1))
                |done
                |printf 'file\0001:found\n'
                |""".trimMargin())
            assertTrue(executable.setExecutable(true))
            val result = WorkspaceOps.grep(root, "pattern", ".", null, true, false, 10, executable,
                onStarted = { }, trackedShell = "/bin/sh", timeoutMillis = 5000)
            assertFalse(result.output, result.isError)
            assertTrue(result.output.contains("file:1:found"))
        } finally { root.deleteRecursively() }
    }

    @Test fun failureAfterChildStartStillTerminatesTheChild() {
        val root = Files.createTempDirectory("tracked-grep-failure").toFile()
        try {
            val executable = root.resolve("slow-rg")
            executable.writeText("#!/bin/sh\nexec /bin/sleep 30\n")
            assertTrue(executable.setExecutable(true))
            var announced = -1L
            val result = WorkspaceOps.grep(root, "pattern", ".", null, true, false, 10, executable,
                onStarted = { announced = it; error("host interrupted") }, trackedShell = "/bin/sh", timeoutMillis = 5000)
            assertTrue(result.isError)
            assertTrue(announced > 0)
            assertFalse(ProcessHandle.of(announced).map { it.isAlive }.orElse(false))
        } finally { root.deleteRecursively() }
    }
}
