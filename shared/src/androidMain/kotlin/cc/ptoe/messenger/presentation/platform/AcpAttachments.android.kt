package cc.ptoe.messenger.presentation.platform

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*

private var clipboardContext: Context? = null
private suspend fun attachment(context: Context, uri: Uri): AcpAttachment = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)!!.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() <= 16 * 1024 * 1024) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.toByteArray() }
    require(bytes.size <= 16 * 1024 * 1024) { "Attachment exceeds 16 MB" }
    var name = uri.lastPathSegment ?: "Attachment"
    resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
    AcpAttachment(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
}
@Composable
internal actual fun rememberAcpFilePicker(onPicked: (AcpAttachment) -> Unit, onError: (String) -> Unit): FilePickerLauncher {
    val context = LocalContext.current
    clipboardContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> scope.launch { try { uris.forEach { onPicked(attachment(context, it)) } } catch (failure: Exception) { onError(failure.message.orEmpty()) } } }
    return remember(picker) { object : FilePickerLauncher { override fun launch() { picker.launch(arrayOf("*/*")) } } }
}
internal actual suspend fun readAcpClipboardAttachments(): List<AcpAttachment> {
    val context = clipboardContext ?: return emptyList()
    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return emptyList()
    return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.map { attachment(context, it) }
}
