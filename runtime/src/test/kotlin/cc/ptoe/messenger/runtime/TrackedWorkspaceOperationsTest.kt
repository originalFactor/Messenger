/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.runtime

import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TrackedWorkspaceOperationsTest {
    @Test fun runningAndDuplicateAreNeverReportedStopped() {
        val dir = Files.createTempDirectory("tracked-workspace").toFile()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val registry = TrackedWorkspaceOperations(dir)
            val otherServiceInstance = TrackedWorkspaceOperations(dir)
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val future = executor.submit<String> { registry.run("token") { started.countDown(); check(finish.await(5, TimeUnit.SECONDS)); "done" } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(TrackedWorkspaceOperations.RUNNING, otherServiceInstance.status("token"))
            assertThrows(Exception::class.java) { otherServiceInstance.run("token") { error("duplicate must not run") } }
            assertEquals(TrackedWorkspaceOperations.UNKNOWN, registry.status("missing"))
            finish.countDown()
            assertEquals("done", future.get(5, TimeUnit.SECONDS))
            assertEquals(TrackedWorkspaceOperations.STOPPED, otherServiceInstance.status("token"))
        } finally { executor.shutdownNow(); dir.deleteRecursively() }
    }

    @Test fun previousProcessGrepRequiresConfirmedChildExit() {
        val dir = Files.createTempDirectory("tracked-grep").toFile()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val child = ProcessBuilder("/bin/sh", "-c", "printf '%s\\n' \"\$\$\"; exec sleep 30").start()
        val childPid = child.inputStream.bufferedReader().readLine().toLong()
        try {
            val previous = TrackedWorkspaceOperations(dir, UUID.randomUUID().toString(), processNamespace = { "boot/namespace" })
            val next = TrackedWorkspaceOperations(dir, UUID.randomUUID().toString(), processStatus = { pid ->
                if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) TrackedWorkspaceOperations.RUNNING else TrackedWorkspaceOperations.STOPPED
            }, processNamespace = { "boot/namespace" })
            val operation = executor.submit { previous.runChild("grep") { record -> record(childPid); started.countDown(); check(finish.await(5, TimeUnit.SECONDS)) } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(TrackedWorkspaceOperations.RUNNING, next.status("grep"))
            val otherNamespace = TrackedWorkspaceOperations(dir, UUID.randomUUID().toString(), processStatus = { TrackedWorkspaceOperations.STOPPED }, processNamespace = { "other-boot/namespace" })
            assertEquals(TrackedWorkspaceOperations.UNKNOWN, otherNamespace.status("grep"))
            child.destroyForcibly(); child.waitFor()
            assertEquals(TrackedWorkspaceOperations.STOPPED, next.status("grep"))
            finish.countDown(); operation.get(5, TimeUnit.SECONDS)
        } finally { child.destroyForcibly(); finish.countDown(); executor.shutdownNow(); dir.deleteRecursively() }
    }

    @Test fun childWithoutRecordedPidAndProbeErrorsRemainUnknown() {
        val dir = Files.createTempDirectory("tracked-grep-unknown").toFile()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        try {
            val previous = TrackedWorkspaceOperations(dir, UUID.randomUUID().toString(), processNamespace = { "boot/namespace" })
            val next = TrackedWorkspaceOperations(dir, UUID.randomUUID().toString(), processStatus = { throw SecurityException("denied") }, processNamespace = { "boot/namespace" })
            val operation = executor.submit { previous.runChild("grep") { record -> started.countDown(); check(finish.await(5, TimeUnit.SECONDS)); record(123) } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals(TrackedWorkspaceOperations.UNKNOWN, next.status("grep"))
            finish.countDown(); operation.get(5, TimeUnit.SECONDS)
            // A malformed/legacy child marker never proves exit.
            dir.listFiles()!!.single().writeText("running:${UUID.randomUUID()}:child:123")
            assertEquals(TrackedWorkspaceOperations.UNKNOWN, next.status("grep"))
        } finally { finish.countDown(); executor.shutdownNow(); dir.deleteRecursively() }
    }

    @Test fun previousProcessMarkerCanOnlyProveInProcessWorkHasStopped() {
        val dir = Files.createTempDirectory("tracked-restart").toFile()
        try {
            val previous = TrackedWorkspaceOperations(dir, UUID.randomUUID().toString())
            val next = TrackedWorkspaceOperations(dir, UUID.randomUUID().toString())
            // A valid marker from a previous process epoch, with no operation alive in this test.
            previous.run("token") { "finished" }
            assertEquals(TrackedWorkspaceOperations.STOPPED, next.status("token"))
            // Simulate a durable in-flight marker left by a terminated process.
            dir.listFiles()!!.single().writeText("running:${UUID.randomUUID()}")
            assertEquals(TrackedWorkspaceOperations.STOPPED, next.status("token"))
            assertEquals(TrackedWorkspaceOperations.UNKNOWN, next.status("absent"))
            dir.listFiles()!!.single().writeText("broken")
            assertEquals(TrackedWorkspaceOperations.UNKNOWN, next.status("token"))
        } finally { dir.deleteRecursively() }
    }
}
