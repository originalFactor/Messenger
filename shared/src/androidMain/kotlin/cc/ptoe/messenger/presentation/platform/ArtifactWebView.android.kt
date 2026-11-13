package cc.ptoe.messenger.presentation.platform

import android.annotation.SuppressLint
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.artifact_host_unavailable
import org.jetbrains.compose.resources.stringResource
import java.io.ByteArrayInputStream
import java.util.UUID

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun ArtifactWebView(
    files: Map<String, String>,
    entry: String,
    stateJson: String,
    onMessage: (String, (String) -> Unit) -> Unit,
    modifier: Modifier,
) {
    val callback = rememberUpdatedState(onMessage)
    val sessionResult = remember(files, entry) {
        runCatching {
            check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                "The installed Android WebView does not support secure messaging"
            }
            ArtifactWebSession(files, entry, stateJson, UUID.randomUUID().toString().replace("-", ""), """
                const send=raw=>artifactHost.postMessage(raw);
                artifactHost.onmessage=e=>reply(e.data);
            """.trimIndent())
        }
    }
    val session = sessionResult.getOrNull()
    var failure by remember(session) { mutableStateOf(sessionResult.exceptionOrNull()?.message) }
    if (session == null || failure != null) {
        Text(stringResource(Res.string.artifact_host_unavailable,
            failure.orEmpty()), modifier,
            color = MaterialTheme.colorScheme.error)
        return
    }
    key(session) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                WebView(context).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        domStorageEnabled = false
                        databaseEnabled = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(true)
                        mediaPlaybackRequiresUserGesture = true
                        setGeolocationEnabled(false)
                    }
                    setDownloadListener { _, _, _, _, _ -> }
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                        override fun onCreateWindow(view: WebView?, dialog: Boolean, gesture: Boolean,
                            message: android.os.Message?): Boolean = false
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.isForMainFrame || request.url.toString() != "about:srcdoc"
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) failure = "${error.errorCode}: ${error.description}"
                        }
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            failure = "WebView renderer stopped (crashed=${detail.didCrash()})"
                            return true
                        }
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                            val target = request.url.toString()
                            val result = if (request.method == "GET") {
                                if (target == session.url && request.isForMainFrame) "text/html" to session.html.encodeToByteArray()
                                else session.resource(target)
                            } else null
                            return WebResourceResponse(
                                result?.first ?: "text/plain", "UTF-8",
                                if (result == null) 403 else 200, if (result == null) "Forbidden" else "OK",
                                session.headers,
                                ByteArrayInputStream(result?.second ?: "Blocked".encodeToByteArray()),
                            )
                        }
                    }
                    WebViewCompat.addWebMessageListener(this, "artifactHost", setOf(session.origin)) {
                            view, message, sourceOrigin, isMainFrame, reply ->
                        val raw = message.data ?: return@addWebMessageListener
                        if (!isMainFrame || sourceOrigin.toString() != session.origin || !session.validMessage(raw)) {
                            return@addWebMessageListener
                        }
                        session.dispatchMessage(raw, callback.value) { response ->
                            view.post { if (view.url == session.url) reply.postMessage(response) }
                        }
                    }
                    loadUrl(session.url)
                }
            },
            onRelease = { view ->
                WebViewCompat.removeWebMessageListener(view, "artifactHost")
                view.stopLoading()
                view.removeAllViews()
                view.destroy()
            },
        )
    }
}
