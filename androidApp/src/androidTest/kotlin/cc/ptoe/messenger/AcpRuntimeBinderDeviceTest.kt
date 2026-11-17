/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import cc.ptoe.messenger.runtime.IMcpCallback
import cc.ptoe.messenger.runtime.IShellService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Runs against the separately installed, same-key Runtime companion on a disposable device. */
class AcpRuntimeBinderDeviceTest {
    private class Callback : IMcpCallback.Stub() {
        val output = LinkedBlockingQueue<String>()
        val closed = CountDownLatch(1)
        override fun onOutput(sessionId: Int, line: String?) { if (line != null) output.add(line) }
        override fun onError(sessionId: Int, error: String?) = Unit
        override fun onClosed(sessionId: Int, exitCode: Int) { closed.countDown() }
    }

    @Test fun staleCallbackCannotSendToOrStopReusedSessionIdAcrossUids() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotEquals(Process.myUid(), context.packageManager.getPackageUid("cc.ptoe.messenger.runtime", 0))
        val connected = CountDownLatch(1)
        var shell: IShellService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                shell = IShellService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent().setClassName("cc.ptoe.messenger.runtime", "cc.ptoe.messenger.runtime.ShellService")
        assertTrue("Install the same-key Runtime debug APK before this test", context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        val old = Callback()
        val current = Callback()
        val privateAsset = Files.createTempFile(context.filesDir.toPath(), "private-asset-boundary-", ".txt").toFile()
        val id = 41379
        try {
            privateAsset.writeText("owned fixture")
            // Even a world-readable file is inaccessible when its app-private parent is enforced.
            Os.chmod(privateAsset.absolutePath, 420)
            assertEquals("owned fixture", privateAsset.readText())
            assertTrue("Runtime service did not connect", connected.await(10, TimeUnit.SECONDS))
            val service = checkNotNull(shell)
            assertTrue(service.acpStopProtocol() >= 2)
            val workspace = checkNotNull(service.temporaryChatWorkspace("binder-device-test"))
            val script = """
                while IFS= read -r line; do
                    if [ "${'$'}line" = private-asset-probe ]; then
                        if [ -r "${'$'}1" ]; then printf 'exposed\n'; else printf 'denied\n'; fi
                    else printf '%s\n' "${'$'}line"; fi
                done
            """.trimIndent()
            val args = arrayOf("-c", script, "asset-boundary", privateAsset.absolutePath)
            fun start(callback: Callback) = service.startAcpProcess(id, "/system/bin/sh", args, null, workspace, callback)
            assertTrue("Could not start first Runtime child", start(old))
            assertTrue(service.sendAcpInputOwned(id, "first", old))
            assertEquals("first", old.output.poll(10, TimeUnit.SECONDS))
            // This tests this process's private-files boundary only. It is not a proof that
            // legacy Runtime descendants, remote effects, or old Core workers have stopped.
            assertTrue(service.sendAcpInputOwned(id, "private-asset-probe", old))
            assertEquals("denied", old.output.poll(10, TimeUnit.SECONDS))
            assertTrue(service.stopAcpProcessOwned(id, old))
            assertTrue(old.closed.await(10, TimeUnit.SECONDS))

            // Exit callback delivery and registry retirement may finish just after the stop reply.
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var started = false
            while (!started && SystemClock.elapsedRealtime() < deadline) {
                started = start(current)
                if (!started) Thread.sleep(20)
            }
            assertTrue("Could not reuse the retired session ID", started)
            assertFalse(service.sendAcpInputOwned(id, "wrong-session", old))
            assertFalse(service.stopAcpProcessOwned(id, old))
            assertNull(current.output.poll(200, TimeUnit.MILLISECONDS))
            assertTrue(service.sendAcpInputOwned(id, "second", current))
            assertEquals("second", current.output.poll(10, TimeUnit.SECONDS))
            assertTrue(service.stopAcpProcessOwned(id, current))
            assertTrue(current.closed.await(10, TimeUnit.SECONDS))
        } finally {
            shell?.let { service ->
                runCatching { service.stopAcpProcessOwned(id, old) }
                runCatching { service.stopAcpProcessOwned(id, current) }
            }
            privateAsset.delete()
            context.unbindService(connection)
        }
    }
}
