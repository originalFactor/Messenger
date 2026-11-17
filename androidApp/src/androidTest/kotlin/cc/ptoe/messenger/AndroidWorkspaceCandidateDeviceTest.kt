/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import cc.ptoe.messenger.runtime.IMcpCallback
import cc.ptoe.messenger.runtime.IShellService
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A scoped, caller-selected test archive is staged by the app, not exposed as a live workspace. */
class AndroidWorkspaceCandidateDeviceTest {
    @Test fun stagesRuntimeArchiveInSeparateUidAndLeavesOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotEquals(Process.myUid(), context.packageManager.getPackageUid("cc.ptoe.messenger.runtime", 0))
        val ready = CountDownLatch(1)
        var service: IShellService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IShellService.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent().setClassName("cc.ptoe.messenger.runtime", "cc.ptoe.messenger.runtime.ShellService")
        assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        val bytes = "workspace candidate".toByteArray()
        val archive = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).apply {
                writeInt(0x4d575331)
                writeByte(2); writeUTF("resource.txt"); writeBoolean(false); writeLong(bytes.size.toLong())
                write(bytes); write(MessageDigest.getInstance("SHA-256").digest(bytes)); writeByte(0)
            }
        }.toByteArray()
        val encoded = Base64.encodeToString(archive, Base64.NO_WRAP)
        val temp = Files.createTempFile(context.cacheDir.toPath(), "workspace-archive-device-", ".bin")
        var candidate: Path? = null
        val output = LinkedBlockingQueue<String>()
        val closed = CountDownLatch(1)
        val callback = object : IMcpCallback.Stub() {
            override fun onOutput(sessionId: Int, line: String?) { if (line != null) output.add(line) }
            override fun onError(sessionId: Int, error: String?) = Unit
            override fun onClosed(sessionId: Int, exitCode: Int) { closed.countDown() }
        }
        val sessionId = 41380
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val runtime = checkNotNull(service)
            val workspace = checkNotNull(runtime.temporaryChatWorkspace("candidate-device-test"))
            val file = "candidate-${UUID.randomUUID()}.txt"
            assertFalse(runtime.workspaceCreate(workspace, file, encoded, false).isError)
            fun source(): String {
                val result = runtime.workspaceRead(workspace, file, 1, 1)
                assertFalse(result.isError)
                return result.output.removePrefix("1: ")
            }
            assertEquals(encoded, source())
            Files.write(temp, Base64.decode(source(), Base64.NO_WRAP))
            ParcelFileDescriptor.open(temp.toFile(), ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                candidate = runBlocking { AndroidWorkspaceCandidateStager(context).stageAuthorizedArchive(descriptor) }.directory
            }
            val imported = checkNotNull(candidate).resolve("resource.txt")
            assertArrayEquals(bytes, Files.readAllBytes(imported))
            assertEquals(encoded, source())

            // The Runtime child sees the path but cannot read this app-private candidate.
            Os.chmod(imported.toString(), 420)
            val command = "read line; if [ -r \"${'$'}1\" ]; then printf 'exposed\\n'; else printf 'denied\\n'; fi; read line"
            assertTrue(runtime.startAcpProcess(sessionId, "/system/bin/sh", arrayOf("-c", command, "probe", imported.toString()), null, workspace, callback))
            assertTrue(runtime.sendAcpInputOwned(sessionId, "probe", callback))
            assertEquals("denied", output.poll(10, TimeUnit.SECONDS))
            assertTrue(runtime.stopAcpProcessOwned(sessionId, callback))
            assertTrue(closed.await(10, TimeUnit.SECONDS))
        } finally {
            service?.let { runCatching { it.stopAcpProcessOwned(sessionId, callback) } }
            candidate?.let { path -> Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
            Files.deleteIfExists(temp)
            context.unbindService(connection)
        }
    }
}
