package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Replies are JSON objects containing the request id and either data or error. */
@Composable
expect fun ArtifactWebView(
    files: Map<String, String>,
    entry: String,
    stateJson: String,
    onMessage: (String, (String) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
)

/** The origin and nonce are generated afresh for each host, never from package input. */
internal class ArtifactWebSession(
    private val files: Map<String, String>,
    entry: String,
    stateJson: String,
    val nonce: String,
    nativeSend: String,
    syntheticOrigin: String? = null,
) {
    val origin = syntheticOrigin ?: "https://artifact-$nonce.messenger.invalid"
    val url = "$origin/host.html"
    private val resourcePrefix = "$origin/package/"
    private val childPolicy = "default-src 'none'; script-src 'unsafe-inline' $origin; " +
        "style-src 'unsafe-inline' $origin; img-src $origin data:; font-src $origin data:; " +
        "connect-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; " +
        "object-src 'none'; media-src $origin; base-uri $origin; form-action 'none';"
    val headers = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer",
        "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), usb=(), payment=()",
    )
    val html: String

    init {
        require(validPath(entry) && files.containsKey(entry)) { "Artifact entry is missing or invalid" }
        require(files.keys.all(::validPath)) { "Artifact contains an invalid resource path" }
        val state = Json.parseToJsonElement(stateJson)
        val sdk = """
            (() => {
              const nonce = ${quote(nonce)}, pending = new Map(); let seq = 0;
              let state = ${quote(state.toString())}; state = JSON.parse(state);
              const send = (method, params) => new Promise((resolve,reject) => {
                const id = String(++seq);
                const timer = setTimeout(() => { pending.delete(id); reject(new Error('Artifact request timed out')); },30000);
                pending.set(id,{resolve,reject,timer}); parent.postMessage({nonce,id,method,params},'*');
              });
              addEventListener('message', e => {
                if(e.source !== parent || e.data?.nonce !== nonce) return;
                const r = e.data.reply, p = pending.get(r?.id); if(!p) return;
                pending.delete(r.id); clearTimeout(p.timer);
                if('error' in r) p.reject(new Error(typeof r.error === 'string' ? r.error : JSON.stringify(r.error)));
                else p.resolve(r.data);
              });
              Object.defineProperty(window,'messenger',{value:Object.freeze({
                getState:() => send('getState',{}),
                setState:value => send('setState',{state:value}).then(result => {state=value; return result;}),
                fetch:(url,options={}) => send('fetch',{url, ...options}),
                initialState:state
              }),writable:false,configurable:false});
              // WebRTC transports do not consistently obey connect-src in embedded engines.
              for(const name of ['RTCPeerConnection','webkitRTCPeerConnection','WebTransport'])
                Object.defineProperty(window,name,{value:undefined,writable:false,configurable:false});
            })();
        """.trimIndent()
        val directory = entry.substringBeforeLast('/', "") .let { if (it.isEmpty()) "" else "$it/" }
        val child = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"$childPolicy\">" +
            "<base href=\"$resourcePrefix$directory\"><script>$sdk</script></head>" + files.getValue(entry)
        // Package text is a JS string, never interpolated as markup or executable JS.
        html = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline' $origin; style-src 'unsafe-inline' $origin; img-src $origin data:; font-src $origin data:; media-src $origin; frame-src 'self'; worker-src 'none'; connect-src 'none'; base-uri $origin; form-action 'none'">
            <style nonce="$nonce">html,body,iframe{margin:0;width:100%;height:100%;border:0;overflow:hidden}</style>
            </head><body><iframe sandbox="allow-scripts" referrerpolicy="no-referrer" title="Artifact"></iframe>
            <script nonce="$nonce">(() => {
              const nonce=${quote(nonce)}, frame=document.querySelector('iframe');
              const reply = raw => { try { frame.contentWindow.postMessage({nonce,reply:JSON.parse(raw)},'*'); } catch(_){} };
              $nativeSend
              addEventListener('message',e => {
                if(e.source!==frame.contentWindow || e.origin!=='null' || e.data?.nonce!==nonce) return;
                const {id,method,params}=e.data;
                if(typeof id!=='string' || !['getState','setState','fetch'].includes(method)) return;
                send(JSON.stringify({id,method,params}));
              });
              frame.srcdoc=${quote(child)};
            })();</script></body></html>
        """.trimIndent()
    }

    fun resource(requestUrl: String): Pair<String, ByteArray>? {
        if (!requestUrl.startsWith(resourcePrefix)) return null
        val path = requestUrl.removePrefix(resourcePrefix)
        if (!validPath(path)) return null
        val content = files[path] ?: return null
        if (content.startsWith("data:")) {
            val header = content.substringBefore(',')
            val mime = header.removePrefix("data:").removeSuffix(";base64")
            if (header != "data:$mime;base64" || mime !in setOf(
                "image/png", "image/jpeg", "image/webp", "image/gif", "image/svg+xml",
                "audio/mpeg", "audio/wav", "video/mp4", "font/woff2",
            ) || content.length > 44_739_244) return null
            return runCatching { mime to Base64.decode(content.substringAfter(',')) }.getOrNull()
        }
        val mime = when (path.substringAfterLast('.', "").lowercase()) {
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "json", "map" -> "application/json"
            // HTML resources are never navigable documents; entry markup lives only in srcdoc.
            else -> "text/plain"
        }
        return mime to content.encodeToByteArray()
    }

    fun validMessage(raw: String): Boolean = runCatching {
        if (raw.length > 1_048_576) return false
        val body = Json.parseToJsonElement(raw).jsonObject
        body["id"]?.jsonPrimitive?.isString == true &&
            body["method"]?.jsonPrimitive?.content in setOf("getState", "setState", "fetch")
    }.getOrDefault(false)

    fun dispatchMessage(raw: String, handler: (String, (String) -> Unit) -> Unit, reply: (String) -> Unit) {
        try {
            handler(raw, reply)
        } catch (error: Exception) {
            val id = Json.parseToJsonElement(raw).jsonObject.getValue("id").jsonPrimitive.content
            reply("{\"id\":${quote(id)},\"error\":${quote(error.message ?: "Artifact request failed")}}")
        }
    }

    companion object {
        internal fun quote(value: String): String = JsonPrimitive(value).toString()
            .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

        internal fun validPath(path: String): Boolean = path.isNotEmpty() &&
            !path.startsWith('/') && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
            path.all { it.isLetterOrDigit() && it.code < 128 || it in "_./-" }
    }
}
