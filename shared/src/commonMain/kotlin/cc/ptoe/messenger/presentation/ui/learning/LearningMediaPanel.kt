package cc.ptoe.messenger.presentation.ui.learning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cc.ptoe.messenger.di.AppContainerHolder
import cc.ptoe.messenger.generated.resources.*
import cc.ptoe.messenger.presentation.platform.ArtifactWebView
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import org.jetbrains.compose.resources.stringResource

internal val mediaJson = Json { ignoreUnknownKeys = true }
private val activeInlineArtifact = mutableStateOf<String?>(null)
internal fun JsonElement.obj() = this as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.list(key: String) = (get(key) as? JsonArray)?.toList().orEmpty()
internal suspend fun api(method: String, path: String, body: JsonElement? = null): JsonElement =
    mediaJson.parseToJsonElement(AppContainerHolder.instance.coreBridge.learningRequest(method, path, body?.toString()))

@Composable
fun AssetImage(assetId: String, modifier: Modifier = Modifier) {
    var bytes by remember(assetId) { mutableStateOf<ByteArray?>(null) }
    var saveToLearning by remember(assetId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var error by remember(assetId) { mutableStateOf<String?>(null) }
    val export = cc.ptoe.messenger.presentation.platform.rememberLearningAssetExportLauncher { error = it }
    LaunchedEffect(assetId) {
        try { bytes = AppContainerHolder.instance.coreBridge.learningAsset(assetId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }
    if (error != null) Text(stringResource(Res.string.media_missing) + "\n" + error, color = MaterialTheme.colorScheme.error, modifier = modifier)
    else if (bytes != null) Column(modifier) {
        AsyncImage(model = bytes, contentDescription = stringResource(Res.string.media_image_description), modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 480.dp))
        Row {
            TextButton(onClick = { saveToLearning = true }) { Text(stringResource(Res.string.learning_save)) }
            TextButton(onClick = { scope.launch { try {
                val mime = api("GET", "/assets/$assetId").obj().text("mimeType")
                val extension = when (mime) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "bin" }
                export("messenger-${assetId.take(12)}.$extension", mime, bytes!!)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }) { Text(stringResource(Res.string.media_save)) }
        }
    }
    else CircularProgressIndicator(modifier = Modifier.size(24.dp))
    if (saveToLearning) SaveMediaDialog(title = stringResource(Res.string.media_image_description), assetId = assetId, onDismiss = { saveToLearning = false })
}

@Composable
fun LearningMediaPanel(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val revision by AppContainerHolder.instance.learningRevision.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var artifacts by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var jobs by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var artifactEnabled by remember { mutableStateOf(false) }
    var imageEnabled by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    var addProfile by remember { mutableStateOf(false) }
    var profileId by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) { error?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(refresh, revision) {
        try {
            artifacts = api("GET", "/artifacts").obj().list("items")
            profiles = (api("GET", "/image-generation/profiles") as? JsonArray)?.toList().orEmpty()
            jobs = (api("GET", "/image-generation/jobs") as? JsonArray)?.toList().orEmpty()
            artifactEnabled = api("GET", "/artifacts/settings").obj()["enabled"] == JsonPrimitive(true)
            imageEnabled = api("GET", "/image-generation/capability").obj()["enabled"] == JsonPrimitive(true)
            if (profiles.none { it.obj().text("id") == profileId }) profileId = profiles.firstOrNull()?.obj()?.text("id").orEmpty()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    val hasRunning = jobs.any { it.obj().text("status") in listOf("queued", "running") }
    LaunchedEffect(hasRunning) { if (hasRunning) while (true) { delay(1500); refresh++ } }
    fun action(block: suspend () -> Unit) { scope.launch { busy = true; error = null; try { block(); refresh++ } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false } } }
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text(stringResource(Res.string.media_artifacts)) })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text(stringResource(Res.string.media_images)) })
            TextButton(onClick = { refresh++ }) { Text(stringResource(Res.string.media_refresh)) }
        }
        Text(stringResource(Res.string.media_local_only), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tab == 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(Res.string.media_artifact_permission), modifier = Modifier.weight(1f))
                    Switch(checked = artifactEnabled, enabled = !busy, onCheckedChange = { enabled -> action { api("PUT", "/artifacts/settings", buildJsonObject { put("enabled", enabled) }) } })
                }
                Button(onClick = { create = true }) { Text(stringResource(Res.string.media_new)) }
                if (artifacts.isEmpty()) Text(stringResource(Res.string.media_empty))
                artifacts.forEach { entry ->
                    val item = entry.obj()
                    ElevatedCard(onClick = { selected = item.text("id") }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) { Text(item.text("title"), style = MaterialTheme.typography.titleMedium); Text(item.text("description")) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(Res.string.media_image_permission), modifier = Modifier.weight(1f))
                    Switch(checked = imageEnabled, enabled = !busy, onCheckedChange = { enabled -> action { api("PUT", "/image-generation/capability", buildJsonObject { put("enabled", enabled) }) } })
                }
                Text(stringResource(Res.string.media_profiles), style = MaterialTheme.typography.titleMedium)
                profiles.forEach { element ->
                    val profile = element.obj()
                    Row(Modifier.fillMaxWidth()) {
                        FilterChip(selected = profileId == profile.text("id"), onClick = { profileId = profile.text("id") }, label = { Text(profile.text("name") + " · " + profile.text("modelId")) })
                        TextButton(onClick = { action { api("DELETE", "/image-generation/profiles/${profile.text("id")}") } }) { Text(stringResource(Res.string.media_remove)) }
                    }
                }
                OutlinedButton(onClick = { addProfile = true }) { Text(stringResource(Res.string.media_new)) }
                if (profiles.isEmpty()) Text(stringResource(Res.string.media_no_profile))
                OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text(stringResource(Res.string.media_prompt)) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Button(enabled = imageEnabled && !busy && prompt.isNotBlank() && profileId.isNotBlank(), onClick = {
                    val selectedPrompt = prompt
                    action { api("POST", "/image-generation/jobs", buildJsonObject { put("requestId", cc.ptoe.messenger.data.util.randomUuid()); put("profileId", profileId); put("prompt", selectedPrompt); put("parameters", JsonObject(emptyMap())) }) }
                }) { Text(stringResource(Res.string.media_generate)) }
                jobs.sortedByDescending { it.obj().text("createdAt") }.forEach { element ->
                    val job = element.obj()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(job["request"]?.obj()?.text("prompt").orEmpty(), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(Res.string.media_job_status, job.text("status")))
                            if (job.text("status") in listOf("queued", "running")) TextButton(onClick = { action { api("POST", "/image-generation/jobs/${job.text("id")}/cancel") } }) { Text(stringResource(Res.string.media_cancel)) }
                            if (job.text("status") in listOf("unknown", "cancelled")) Text(stringResource(Res.string.media_job_uncertain))
                            (job["error"] as? JsonPrimitive)?.contentOrNull?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            job.list("assetIds").forEach { asset -> AssetImage(asset.jsonPrimitive.content) }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar)
    }
    if (create) ArtifactEditor(onDismiss = { create = false }, onSaved = { selected = it; create = false; refresh++ })
    if (addProfile) ImageProfileEditor(onDismiss = { addProfile = false }, onSaved = { addProfile = false; refresh++ })
    selected?.let { id -> ArtifactDialog(id, onDismiss = { selected = null }) }
}

@Composable
private fun ImageProfileEditor(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val providers by AppContainerHolder.instance.providerRepository.getAll().collectAsState(emptyList())
    var name by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var providerId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(stringResource(Res.string.media_profiles)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.media_profile_name)) })
            Text(stringResource(Res.string.media_provider))
            if (providers.isEmpty()) Text(stringResource(Res.string.media_no_provider))
            providers.filter { it.id != "builtin-messenger-cloud-ai" }.forEach { provider ->
                FilterChip(selected = providerId == provider.id, onClick = { providerId = provider.id }, label = { Text(provider.name) })
            }
            OutlinedTextField(model, { model = it }, label = { Text(stringResource(Res.string.media_model)) }, supportingText = { Text(stringResource(Res.string.media_model_hint)) })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && providerId.isNotEmpty() && model.isNotBlank() && name.isNotBlank(), onClick = {
            scope.launch { busy = true; try {
                val id = cc.ptoe.messenger.data.util.randomUuid()
                api("PUT", "/image-generation/profiles/$id", buildJsonObject {
                    put("id", id); put("name", name.trim()); put("providerId", providerId); put("modelId", model.trim()); put("adapter", "openai_images"); put("enabled", true)
                    put("supportedParameters", JsonArray(emptyList())); put("defaults", JsonObject(emptyMap()))
                }); onSaved()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false } }
        }) { Text(stringResource(Res.string.media_save)) }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.media_cancel)) } })
}

@Composable
private fun ArtifactEditor(existing: JsonObject? = null, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val last = existing?.list("revisions")?.lastOrNull()?.obj()
    var title by remember { mutableStateOf(existing?.text("title").orEmpty()) }
    var files by remember { mutableStateOf(last?.get("files")?.obj()?.mapValues { it.value.jsonPrimitive.content }.orEmpty().ifEmpty { mapOf("index.html" to "<!doctype html><html><body><h1>Interactive lesson</h1></body></html>") }) }
    var selectedFile by remember { mutableStateOf(last?.text("entry") ?: "index.html") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(16.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(Res.string.media_title)) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    files.keys.forEach { path -> FilterChip(selected = selectedFile == path, onClick = { selectedFile = path }, label = { Text(path) }) }
                }
                OutlinedTextField(files[selectedFile].orEmpty(), { files = files + (selectedFile to it) }, label = { Text(stringResource(Res.string.media_html)) }, modifier = Modifier.fillMaxWidth().weight(1f))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(Res.string.media_cancel)) }
                    Button(enabled = !busy && title.isNotBlank(), onClick = {
                        scope.launch { busy = true; try {
                            val body = buildJsonObject {
                                put("title", title); put("description", existing?.text("description").orEmpty()); put("files", JsonObject(files.mapValues { JsonPrimitive(it.value) })); put("entry", last?.text("entry") ?: "index.html")
                                put("expectedRevision", last?.get("revision") ?: JsonPrimitive(0)); put("stateVersion", last?.get("stateVersion") ?: JsonPrimitive(0)); put("assets", last?.get("assets") ?: JsonObject(emptyMap())); put("requestedDomains", existing?.get("requestedDomains") ?: JsonArray(emptyList()))
                            }
                            val id = existing?.text("id")
                            val saved = api(if (id == null) "POST" else "PUT", if (id == null) "/artifacts" else "/artifacts/$id", body).obj()
                            onSaved(saved.text("id"))
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false } }
                    }) { Text(stringResource(Res.string.media_save)) }
                }
            }
        }
    }
}

@Composable
fun ArtifactDialog(id: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val signal by AppContainerHolder.instance.learningRevision.collectAsState()
    var artifact by remember(id) { mutableStateOf<JsonObject?>(null) }
    var revisionIndex by remember(id) { mutableIntStateOf(-1) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var edit by remember(id) { mutableStateOf(false) }
    var showNetwork by remember(id) { mutableStateOf(false) }
    var saveToCourse by remember(id) { mutableStateOf(false) }
    var grants by remember(id) { mutableStateOf<Set<String>>(emptySet()) }
    var resolvedFiles by remember(id) { mutableStateOf<Map<String, String>?>(null) }
    var refresh by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, signal, refresh) {
        try { if (artifact == null) api("POST", "/artifacts/$id/authorize-run"); artifact = api("GET", "/artifacts/$id").obj(); grants = artifact!!.list("approvedDomains").map { it.jsonPrimitive.content }.toSet() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    val revisions = artifact?.list("revisions").orEmpty()
    val index = if (revisionIndex < 0) revisions.lastIndex else revisionIndex.coerceAtMost(revisions.lastIndex)
    val current = revisions.getOrNull(index)?.obj()
    val isLatest = index == revisions.lastIndex
    LaunchedEffect(current?.get("revision"), id) {
        resolvedFiles = null
        try {
            val files = current?.get("files")?.obj()?.mapValues { it.value.jsonPrimitive.content }?.toMutableMap() ?: return@LaunchedEffect
            current["assets"]?.obj()?.forEach { (path, value) ->
                val assetId = value.jsonPrimitive.content
                val metadata = api("GET", "/assets/$assetId").obj()
                val bytes = AppContainerHolder.instance.coreBridge.learningAsset(assetId)
                files[path] = "data:${metadata.text("mimeType")};base64,${Base64.encode(bytes)}"
            }
            resolvedFiles = files
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(artifact?.text("title") ?: stringResource(Res.string.media_preview), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.media_close)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = index > 0, onClick = { revisionIndex = index - 1 }) { Text(stringResource(Res.string.media_previous)) }
                    Text(stringResource(Res.string.media_version, index + 1), modifier = Modifier.padding(top = 12.dp))
                    TextButton(enabled = index < revisions.lastIndex, onClick = { revisionIndex = if (index + 1 == revisions.lastIndex) -1 else index + 1 }) { Text(stringResource(Res.string.media_next)) }
                    TextButton(onClick = { showNetwork = !showNetwork }) { Text(stringResource(Res.string.media_network)) }
                    TextButton(enabled = artifact != null, onClick = { edit = true }) { Text(stringResource(Res.string.media_edit)) }
                    TextButton(enabled = artifact != null, onClick = { saveToCourse = true }) { Text(stringResource(Res.string.learning_save)) }
                    TextButton(enabled = artifact != null && isLatest, onClick = { scope.launch { try { api("POST", "/artifacts/$id/state", buildJsonObject { put("stateVersion", artifact!!["stateVersion"]!!); put("state", JsonNull) }); refresh++ } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }) { Text(stringResource(Res.string.media_reset)) }
                }
                error?.let { Text(stringResource(Res.string.media_missing) + "\n" + it, color = MaterialTheme.colorScheme.error) }
                if (!isLatest) Text(stringResource(Res.string.media_state_readonly))
                if (showNetwork) {
                    Text(stringResource(Res.string.media_network_description), style = MaterialTheme.typography.bodySmall)
                    val requested = artifact?.list("requestedDomains").orEmpty().map { it.jsonPrimitive.content }
                    if (requested.isEmpty()) Text(stringResource(Res.string.media_no_network))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        requested.forEach { domain -> FilterChip(selected = domain in grants, onClick = { grants = if (domain in grants) grants - domain else grants + domain }, label = { Text(domain) }) }
                    }
                    TextButton(onClick = { val chosen = grants; scope.launch { try { api("POST", "/artifacts/$id/grants", buildJsonObject { put("domains", JsonArray(chosen.map(::JsonPrimitive))) }); showNetwork = false } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }) { Text(stringResource(Res.string.media_apply_network)) }
                }
                val files = resolvedFiles
                if (files != null && current != null && artifact != null) {
                    key(id, current["revision"], files) {
                        ArtifactWebView(files = files, entry = current.text("entry"), stateJson = (if (isLatest) artifact!!["state"] else JsonNull)?.toString() ?: "null", modifier = Modifier.fillMaxWidth().weight(1f), onMessage = { raw, reply ->
                            scope.launch {
                                var messageId: JsonElement = JsonNull
                                try {
                                    val message = mediaJson.parseToJsonElement(raw).obj(); messageId = message["id"] ?: JsonNull
                                    val params = message["params"]?.obj() ?: JsonObject(emptyMap())
                                    val data = when (message.text("method")) {
                                        "getState" -> if (isLatest) api("GET", "/artifacts/$id").obj()["state"] ?: JsonNull else JsonNull
                                        "setState" -> {
                                            check(isLatest) { "Previous revisions are read-only" }
                                            api("POST", "/artifacts/$id/state", buildJsonObject { put("stateVersion", current["stateVersion"] ?: JsonPrimitive(0)); put("revision", current["revision"]!!); put("state", params["state"] ?: JsonNull) })
                                        }
                                        "fetch" -> api("POST", "/artifacts/$id/fetch", params)
                                        else -> error("Unsupported application operation")
                                    }
                                    reply(buildJsonObject { put("id", messageId); put("data", data) }.toString())
                                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                    error = e.message
                                    reply(buildJsonObject { put("id", messageId); put("error", e.message ?: "Application request failed") }.toString())
                                }
                            }
                        })
                    }
                } else if (error == null) CircularProgressIndicator()
            }
        }
    }
    if (saveToCourse && artifact != null) SaveMediaDialog(title = artifact!!.text("title"), artifactId = id, onDismiss = { saveToCourse = false })
    if (edit && artifact != null) ArtifactEditor(existing = artifact, onDismiss = { edit = false }, onSaved = { edit = false; revisionIndex = -1; refresh++ })
}

@Composable
fun LearningResultCard(content: JsonElement, modifier: Modifier = Modifier) {
    val obj = content.obj()
    var open by remember(obj.text("id")) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (obj.text("type")) {
            "artifact" -> {
                val artifactId = obj.text("id")
                LaunchedEffect(artifactId) { activeInlineArtifact.value = artifactId }
                DisposableEffect(artifactId) { onDispose { if (activeInlineArtifact.value == artifactId) activeInlineArtifact.value = null } }
                Text(obj.text("title"), style = MaterialTheme.typography.titleMedium)
                if (activeInlineArtifact.value == artifactId && !open) ArtifactInlinePreview(artifactId, Modifier.fillMaxWidth().height(280.dp))
                Row {
                    TextButton(onClick = { activeInlineArtifact.value = artifactId }) { Text(stringResource(Res.string.media_preview)) }
                    TextButton(onClick = { activeInlineArtifact.value = null; open = true }) { Text(stringResource(Res.string.media_open)) }
                }
                if (open) ArtifactDialog(artifactId, onDismiss = { open = false })
            }
            "image", "imageGeneration" -> {
                obj.list("assetIds").forEach { AssetImage(it.jsonPrimitive.content) }
                obj.text("assetId").takeIf(String::isNotEmpty)?.let { AssetImage(it) }
            }
            "learningRecord" -> Text(stringResource(Res.string.media_learning_saved))
            else -> obj.list("assetIds").forEach { AssetImage(it.jsonPrimitive.content) }
        }
    }
}
