package cc.ptoe.messenger.presentation.ui.learning

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.domain.model.LearningRecord
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.platform.ArtifactWebView
import cc.ptoe.messenger.presentation.viewmodel.learningId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ArtifactInlinePreview(id: String, modifier: Modifier = Modifier) {
    val signal by AppContainerHolder.instance.learningRevision.collectAsState()
    val scope = rememberCoroutineScope()
    var artifact by remember(id) { mutableStateOf<JsonObject?>(null) }
    var files by remember(id) { mutableStateOf<Map<String, String>?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id, signal) {
        try {
            val loaded = api("GET", "/artifacts/$id").obj()
            artifact = loaded
            if (loaded["trustedOnDevice"] != JsonPrimitive(true)) return@LaunchedEffect
            val revision = loaded.list("revisions").lastOrNull()?.obj() ?: return@LaunchedEffect
            val resolved = revision.getValue("files").obj().mapValues { it.value.jsonPrimitive.content }.toMutableMap()
            revision["assets"]?.obj()?.forEach { (path, asset) ->
                val assetId = asset.jsonPrimitive.content
                val metadata = api("GET", "/assets/$assetId").obj()
                resolved[path] = "data:${metadata.text("mimeType")};base64,${Base64.encode(AppContainerHolder.instance.coreBridge.learningAsset(assetId))}"
            }
            files = resolved
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    val current = artifact?.list("revisions")?.lastOrNull()?.obj()
    if (error != null) Text(stringResource(Res.string.media_missing) + "\n" + error, color = MaterialTheme.colorScheme.error)
    else if (files != null && current != null) {
        key(id, current["revision"]) {
            ArtifactWebView(files!!, current.text("entry"), (artifact!!["state"] ?: JsonNull).toString(), modifier = modifier, onMessage = { raw, reply ->
                scope.launch {
                    var messageId: JsonElement = JsonNull
                    try {
                        val message = mediaJson.parseToJsonElement(raw).obj(); messageId = message["id"] ?: JsonNull
                        val params = message["params"]?.obj() ?: JsonObject(emptyMap())
                        val data = when (message.text("method")) {
                            "getState" -> api("GET", "/artifacts/$id").obj()["state"] ?: JsonNull
                            "setState" -> api("POST", "/artifacts/$id/state", buildJsonObject { put("state", params["state"] ?: JsonNull); put("stateVersion", current["stateVersion"] ?: JsonPrimitive(0)); put("revision", current["revision"]!!) })
                            "fetch" -> api("POST", "/artifacts/$id/fetch", params)
                            else -> throw IllegalArgumentException("Unsupported application operation")
                        }
                        reply(buildJsonObject { put("id", messageId); put("data", data) }.toString())
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        error = e.message
                        reply(buildJsonObject { put("id", messageId); put("error", e.message ?: "Application request failed") }.toString())
                    }
                }
            })
        }
    }
}

@Composable
internal fun SaveMediaDialog(title: String, artifactId: String? = null, assetId: String? = null, onDismiss: () -> Unit) {
    val container = AppContainerHolder.instance
    val scope = rememberCoroutineScope()
    var courses by remember { mutableStateOf<List<LearningRecord>>(emptyList()) }
    var course by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(title) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { var offset = 0; val all = mutableListOf<LearningRecord>(); do { val page = container.learningRepository.list("course", offset = offset); all += page.items; offset = all.size } while (offset < page.total && page.items.isNotEmpty()); courses = all }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(Res.string.learning_save)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.media_title)) })
            LearningPicker(stringResource(Res.string.learning_course), course, courses.map { it.id to it.title }, enabled = !busy, onSelect = { course = it })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && name.isNotBlank(), onClick = { scope.launch {
            busy = true
            try {
                val id = learningId()
                if (assetId != null) api("POST", "/assets/$assetId/references", buildJsonObject { put("ownerType", "learning"); put("ownerId", id) })
                container.learningRepository.save(LearningRecord(id, title = name, courseId = course, content = buildJsonObject {
                    put("kind", "material"); put("materialKind", if (artifactId != null) "artifact" else "image")
                    artifactId?.let { put("artifactId", it) }; assetId?.let { put("assetId", it) }
                }))
                onDismiss()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false }
        } }) { Text(stringResource(Res.string.media_save)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.media_cancel)) } })
}
