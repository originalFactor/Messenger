package cc.ptoe.messenger.presentation.platform

import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real packaged protocol and native window; no browser security bypasses. */
class ArtifactNativeHostTest {
    @Test
    fun actualHelperRunsOpaqueSdkAndRejectsDirectNetworkAndNativeIpc() {
        assumeTrue("Requires built helper and graphical session", System.getProperty("messenger.artifact.helper") != null)
        val trap = ServerSocket(0).apply { soTimeout = 100 }
        val stop = AtomicBoolean(false)
        val hits = AtomicInteger(0)
        val monitor = Thread({
            while (!stop.get()) {
                try { trap.accept().use { hits.incrementAndGet() } }
                catch (_: SocketTimeoutException) { }
                catch (_: java.io.IOException) { break }
            }
        }, "artifact-test-network-trap").apply { isDaemon = true; start() }
        val result = AtomicReference<JsonObject?>()
        val failure = AtomicReference<String?>()
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val port = trap.localPort
        val spec = ArtifactHelperSpec(mapOf(
            "index.html" to """
                <!doctype html><body><h1>Messenger native host regression</h1>
                <button onclick="this.textContent='Interaction works'">Check interaction</button>
                <script type="module">
                import {value} from './app.mjs';
                try {
                  const rtcUnavailable=typeof RTCPeerConnection==='undefined' && typeof webkitRTCPeerConnection==='undefined'
                    && Object.getOwnPropertyDescriptor(window,'RTCPeerConnection').configurable===false;
                  let blocked=false; try {await fetch('http://127.0.0.1:$port/fetch')} catch(e) {blocked=true;}
                  // Raw native handlers are reachable on some engines, but the wrapper
                  // capability is unavailable to this opaque frame.
                  const forged=JSON.stringify({secret:'bad',payload:'{"id":"attack","method":"getState","params":{}}'});
                  if(window.ipc) window.ipc.postMessage(forged);
                  else if(window.webkit?.messageHandlers?.artifact) window.webkit.messageHandlers.artifact.postMessage(forged);
                  const state=await messenger.getState();
                  await messenger.setState({value,blocked,rtcUnavailable,count:state.count,initial:messenger.initialState.count});
                  const image=new Image(); image.src='http://127.0.0.1:$port/image'; document.body.append(image);
                  try {new WebSocket('ws://127.0.0.1:$port/socket')} catch(e){}
                  try {new Worker('http://127.0.0.1:$port/worker')} catch(e){}
                  const frame=document.createElement('iframe'); frame.src='http://127.0.0.1:$port/frame'; document.body.append(frame);
                  setTimeout(()=>location.href='http://127.0.0.1:$port/navigation',100);
                } catch(e) {await messenger.setState({error:String(e)})}
                </script></body>
            """.trimIndent(),
            "app.mjs" to "export const value = 42;",
        ), "index.html", "{\"count\":7}")
        val helper = ArtifactNativeProcess(spec, "Messenger native host regression", { raw, reply ->
            val message = Json.parseToJsonElement(raw).jsonObject
            val id = message.getValue("id").jsonPrimitive.content
            if (id == "attack") { failure.set("Forged native IPC was accepted"); done.countDown() }
            if (message["method"]?.jsonPrimitive?.content == "setState") {
                result.set(message.getValue("params").jsonObject.getValue("state").jsonObject)
                done.countDown()
            }
            reply(buildJsonObject { put("id", id); put("data", buildJsonObject { put("count", 7) }) }.toString())
        }, { ready.countDown() }, { failure.set(it); ready.countDown(); done.countDown() }, { })
        try {
            assertTrue(ready.await(20, TimeUnit.SECONDS), "Native host never became ready")
            assertTrue(done.await(20, TimeUnit.SECONDS), "SDK never reached Kotlin: ${failure.get()}")
            assertNull(failure.get())
            val state = result.get() ?: error("No SDK result")
            assertEquals("42", state["value"]?.jsonPrimitive?.content, state.toString())
            assertEquals("7", state["count"]?.jsonPrimitive?.content)
            assertEquals("7", state["initial"]?.jsonPrimitive?.content)
            assertEquals("true", state["blocked"]?.jsonPrimitive?.content)
            assertEquals("true", state["rtcUnavailable"]?.jsonPrimitive?.content)
            // Keep the listener through deferred iframe/navigation attempts.
            Thread.sleep(400)
            assertEquals(0, hits.get(), "Artifact reached the direct-network trap")
        } finally {
            helper.close()
            stop.set(true)
            trap.close()
            monitor.join(1000)
        }
    }
}
