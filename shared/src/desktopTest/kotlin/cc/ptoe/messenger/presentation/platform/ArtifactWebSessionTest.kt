package cc.ptoe.messenger.presentation.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactWebSessionTest {
    private fun session(files: Map<String, String> = mapOf("index.html" to "<h1>Hello</h1>")) =
        ArtifactWebSession(files, "index.html", "{\"count\":7}", "testnonce", "const send = raw => {};")

    @Test
    fun packageMarkupCannotEscapeTrustedWrapperScript() {
        val host = session(mapOf("index.html" to "</script><script>parent.stolen=true</script>\u2028"))
        assertFalse(host.html.contains("<script>parent.stolen"))
        assertTrue(host.html.contains("\\u003c/script\\u003e"))
        assertTrue(host.html.contains("sandbox=\"allow-scripts\""))
        assertFalse(host.html.contains("allow-same-origin"))
        assertTrue(host.html.contains("e.source!==frame.contentWindow"))
        assertTrue(host.html.contains("e.origin!=='null'"))
        assertTrue(host.html.contains("e.data?.nonce!==nonce"))
        assertTrue(host.html.contains("connect-src 'none'"))
        assertTrue(host.html.contains("worker-src 'none'"))
        assertTrue(host.html.contains("frame-src 'none'"))
        assertTrue(host.html.contains("form-action 'none'"))
        assertTrue(host.html.contains("initialState:state"))
        assertTrue(host.html.contains("'RTCPeerConnection','webkitRTCPeerConnection','WebTransport'"))
        assertTrue(host.html.contains("value:undefined,writable:false,configurable:false"))
    }

    @Test
    fun resourceResolutionAllowsOnlyExactPackagePaths() {
        val host = session(mapOf("index.html" to "<h1>Hello</h1>", "app.mjs" to "export default 1"))
        assertEquals("text/javascript", host.resource("${host.origin}/package/app.mjs")?.first)
        assertNull(host.resource("https://example.com/app.mjs"))
        assertNull(host.resource("http://localhost:8080/app.mjs"))
        assertNull(host.resource("${host.origin}/package/../host.html"))
        assertNull(host.resource("${host.origin}/package/%2e%2e/host.html"))
        assertNull(host.resource("${host.origin}/package/app.mjs?redirect=https://example.com"))
        assertNull(host.resource("${host.origin}/package/app.mjs/"))
        assertNull(host.resource("${host.origin}.attacker.com/package/app.mjs"))
        assertEquals("*", host.headers["Access-Control-Allow-Origin"])
    }

    @Test
    fun binaryResourcesAreDecodedAndMimeAllowlisted() {
        val host = session(mapOf("index.html" to "Hello", "pixel.png" to "data:image/png;base64,AQID",
            "evil.js" to "data:text/javascript;base64,YWxlcnQoMSk="))
        val image = assertNotNull(host.resource("${host.origin}/package/pixel.png"))
        assertEquals("image/png", image.first)
        assertTrue(image.second.contentEquals(byteArrayOf(1, 2, 3)))
        assertNull(host.resource("${host.origin}/package/evil.js"))
    }

    @Test
    fun bridgeRejectsUnknownMethodsAndNonStringIds() {
        val host = session()
        assertTrue(host.validMessage("{\"id\":\"1\",\"method\":\"fetch\",\"params\":{\"url\":\"https://example.com\"}}"))
        assertFalse(host.validMessage("{\"id\":\"1\",\"method\":\"readFile\"}"))
        assertFalse(host.validMessage("{\"id\":1,\"method\":\"getState\"}"))
        assertFalse(host.validMessage("not json"))
    }
}
