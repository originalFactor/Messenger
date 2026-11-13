package cc.ptoe.messenger.presentation.platform

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.artifact_host_external
import cc.ptoe.messenger.generated.resources.artifact_host_open
import cc.ptoe.messenger.generated.resources.artifact_host_loading
import cc.ptoe.messenger.generated.resources.artifact_host_unavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.stringResource
import java.awt.Toolkit
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.io.encoding.Base64

@Composable
actual fun ArtifactWebView(
    files: Map<String, String>,
    entry: String,
    stateJson: String,
    onMessage: (String, (String) -> Unit) -> Unit,
    modifier: Modifier,
) {
    val callback = rememberUpdatedState(onMessage)
    val spec = remember(files, entry) { runCatching { ArtifactHelperSpec(files, entry, stateJson) } }
    var restart by remember(files, entry) { mutableStateOf(0) }
    var host by remember(files, entry) { mutableStateOf<ArtifactNativeProcess?>(null) }
    var ready by remember(files, entry) { mutableStateOf(false) }
    var closed by remember(files, entry) { mutableStateOf(false) }
    var failure by remember(files, entry) { mutableStateOf(spec.exceptionOrNull()?.message) }
    val title = stringResource(Res.string.artifact_host_open)
    LaunchedEffect(spec, restart) {
        val configuration = spec.getOrNull() ?: return@LaunchedEffect
        var created: ArtifactNativeProcess? = null
        failure = null; ready = false; closed = false
        try {
            withContext(Dispatchers.IO) {
                created = ArtifactNativeProcess(configuration, title,
                    onMessage = { raw, reply -> callback.value(raw, reply) },
                    onReady = { ready = true },
                    onError = { failure = it },
                    onClosed = { closed = true },
                )
            }
            host = created
            awaitCancellation()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: error.toString() }
        finally { created?.close(); host = null }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val error = failure
        when {
            error != null -> Text(stringResource(Res.string.artifact_host_unavailable, error), color = MaterialTheme.colorScheme.error)
            ready && !closed -> Text(stringResource(Res.string.artifact_host_external), style = MaterialTheme.typography.bodyMedium)
            closed -> Unit
            else -> {
                CircularProgressIndicator()
                Text(stringResource(Res.string.artifact_host_loading))
            }
        }
        Button(enabled = ready || closed || error != null, onClick = {
            if (ready && !closed && error == null) host?.focus() else restart++
        }) { Text(stringResource(Res.string.artifact_host_open)) }
    }
}

/** Wrapper capability is deliberately distinct from the nonce sent to the opaque child. */
internal class ArtifactHelperSpec(files: Map<String, String>, entry: String, stateJson: String) {
    val nonce = UUID.randomUUID().toString().replace("-", "")
    private val secret = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
    private val customOrigin = "messenger-artifact://$nonce.localhost"
    private val browserOrigin = if (System.getProperty("os.name").startsWith("Windows"))
        "https://messenger-artifact.$nonce.localhost" else customOrigin
    val session = ArtifactWebSession(files, entry, stateJson, nonce, """
        window.__artifactNativeReply=reply;
        const send=raw=>window.ipc.postMessage(JSON.stringify({secret:${ArtifactWebSession.quote(secret)},payload:raw}));
    """.trimIndent(), browserOrigin)
    val configuration: JsonObject = buildJsonObject {
        put("url", "$customOrigin/host.html")
        put("browserUrl", session.url)
        put("secret", secret)
        put("resources", buildJsonObject {
            fun resource(path: String, mime: String, body: ByteArray) {
                put(path, buildJsonObject { put("mime", mime); put("body", Base64.encode(body)) })
            }
            resource("/host.html", "text/html; charset=utf-8", session.html.encodeToByteArray())
            for (path in files.keys) {
                val data = requireNotNull(session.resource("${session.origin}/package/$path")) { "Unsupported artifact resource: $path" }
                resource("/package/$path", data.first, data.second)
            }
        })
    }
}

/** Each visible preview owns one helper, transient profile and pair of private pipes. */
internal class ArtifactNativeProcess(
    private val spec: ArtifactHelperSpec,
    title: String,
    onMessage: (String, (String) -> Unit) -> Unit,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    onClosed: () -> Unit,
) : AutoCloseable {
    private val disposed = AtomicBoolean(false)
    private val signalledError = AtomicBoolean(false)
    private val writerExecutor = Executors.newSingleThreadExecutor { work -> Thread(work, "artifact-host-writer").apply { isDaemon = true } }
    private val directory: Path = Files.createTempDirectory("messenger-artifact-")
    private val process: Process
    private val writer: BufferedWriter

    init {
        var started: Process? = null
        try {
            val override = System.getProperty("messenger.artifact.helper")
            val binary = if (override != null) Path.of(override) else {
                val os = System.getProperty("os.name").lowercase()
                val arch = System.getProperty("os.arch").lowercase()
                val platform = when {
                    os.contains("linux") && arch in listOf("amd64", "x86_64") -> "linux-x86_64"
                    os.contains("windows") && arch in listOf("amd64", "x86_64") -> "windows-x86_64"
                    os.contains("mac") && arch in listOf("aarch64", "arm64") -> "macos-aarch64"
                    else -> error("No packaged system webview host for $os/$arch")
                }
                val name = "messenger-artifact-webview" + if (os.contains("windows")) ".exe" else ""
                val target = directory.resolve(name)
                val resource = javaClass.getResourceAsStream("/artifact-webview/$platform/$name")
                    ?: error("The system webview helper is missing from this installation")
                resource.use { Files.copy(it, target) }
                if (!os.contains("windows")) Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"))
                target
            }
            val builder = ProcessBuilder(binary.toAbsolutePath().toString()).directory(directory.toFile())
            // Never hand the renderer provider keys, Core tokens, loader overrides,
            // browser debug flags or sandbox overrides from the application's env.
            val allowedEnvironment = setOf("PATH", "PATHEXT", "HOME", "USERPROFILE", "LANG", "TMPDIR", "TMP", "TEMP",
                "DISPLAY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS", "XAUTHORITY",
                "XDG_CURRENT_DESKTOP", "XDG_SESSION_TYPE", "XDG_DATA_DIRS", "XDG_CONFIG_DIRS",
                "SYSTEMROOT", "WINDIR", "APPDATA", "LOCALAPPDATA", "PROGRAMFILES", "PROGRAMFILES(X86)",
                "COMMONPROGRAMFILES", "PROGRAMDATA", "__CF_USER_TEXT_ENCODING", "SECURITYSESSIONID")
            builder.environment().keys.toList().forEach { name ->
                if (name.uppercase() !in allowedEnvironment && !name.startsWith("LC_")) builder.environment().remove(name)
            }
            if (System.getProperty("os.name").lowercase().contains("linux")) {
                // This affects only the helper, never the running Compose/AWT backend.
                builder.environment()["GDK_BACKEND"] = if (Toolkit.getDefaultToolkit().javaClass.name.contains("WLToolkit")) "wayland" else "x11"
            }
            process = builder.start()
            started = process
            writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
            writer.write(JsonObject(spec.configuration + ("title" to JsonPrimitive(title))).toString())
            writer.newLine(); writer.flush()
        } catch (error: Exception) {
            started?.destroyForcibly()
            writerExecutor.shutdownNow()
            directory.toFile().deleteRecursively()
            throw error
        }
        val stderr = StringBuilder()
        Thread({
            process.errorStream.bufferedReader().useLines { lines -> lines.forEach { line -> synchronized(stderr) {
                if (stderr.length > 4096) stderr.delete(0, stderr.length - 2048)
                stderr.append(line).append('\n')
            } } }
        }, "artifact-host-errors").apply { isDaemon = true; start() }
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                    if (line.length > 1_048_576 || disposed.get()) return@forEach
                    val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
                    when (message["type"]?.jsonPrimitive?.content) {
                        "ready" -> SwingUtilities.invokeLater { if (!disposed.get()) onReady() }
                        "error" -> {
                            signalledError.set(true)
                            val detail = message["detail"]?.jsonPrimitive?.content.orEmpty()
                            SwingUtilities.invokeLater { if (!disposed.get()) onError(detail) }
                        }
                        "request" -> {
                            val raw = message["payload"]?.jsonPrimitive?.content ?: return@forEach
                            if (spec.session.validMessage(raw)) SwingUtilities.invokeLater {
                                if (!disposed.get()) spec.session.dispatchMessage(raw, onMessage) { response ->
                                    send(buildJsonObject { put("type", "reply"); put("payload", response) })
                                }
                            }
                        }
                    }
                } }
                val code = process.waitFor()
                SwingUtilities.invokeLater {
                    if (!disposed.get()) {
                        if (code != 0 && !signalledError.get()) onError("System webview exited ($code): " + synchronized(stderr) { stderr.toString() })
                        onClosed()
                    }
                }
            } catch (error: Exception) {
                SwingUtilities.invokeLater { if (!disposed.get()) onError(error.message ?: error.toString()) }
            }
        }, "artifact-host-reader").apply { isDaemon = true; start() }
    }

    private fun send(message: JsonObject) {
        if (disposed.get() || !process.isAlive) return
        writerExecutor.execute {
            runCatching { writer.write(message.toString()); writer.newLine(); writer.flush() }
        }
    }
    fun focus() = send(buildJsonObject { put("type", "focus") })
    override fun close() {
        if (!disposed.compareAndSet(false, true)) return
        writerExecutor.execute {
            runCatching { writer.write("{\"type\":\"close\"}"); writer.newLine(); writer.flush(); writer.close() }
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.descendants().forEach { it.destroy() }
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
            }
            directory.toFile().deleteRecursively()
            writerExecutor.shutdown()
        }
    }
}
