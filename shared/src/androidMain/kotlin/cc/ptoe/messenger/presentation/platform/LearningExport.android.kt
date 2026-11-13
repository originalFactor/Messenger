package cc.ptoe.messenger.presentation.platform

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.learning_export_expired
import cc.ptoe.messenger.generated.resources.learning_package_limit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
internal actual fun rememberLearningExportLauncher(onError: (String) -> Unit, onSaved: () -> Unit): LearningExportLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val failed by rememberUpdatedState(onError)
    val saved by rememberUpdatedState(onSaved)
    val expired = stringResource(Res.string.learning_export_expired)
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val bytes = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (bytes == null || uri == null) failed(expired)
            else scope.launch {
                try {
                    withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) } }
                    saved()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { failed(failure.message ?: failure.toString()) }
            }
        }
    }
    return remember(picker) { object : LearningExportLauncher {
        override fun launch(filename: String, bytes: ByteArray, mimeType: String) {
            pending = bytes
            try {
                picker.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = mimeType; putExtra(Intent.EXTRA_TITLE, filename)
                })
            } catch (failure: Exception) { pending = null; failed(failure.message ?: failure.toString()) }
        }
    } }
}

@Composable
internal actual fun rememberLearningImportLauncher(onPicked: (String) -> Unit, onError: (String) -> Unit): FilePickerLauncher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    val limit = stringResource(Res.string.learning_package_limit)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(65536)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            require(output.size().toLong() + count <= 96L * 1024 * 1024) { limit }
                            output.write(chunk, 0, count)
                        }
                        output.toByteArray().decodeToString(throwOnInvalidSequence = true)
                    }
                }
                picked(text)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(failure.message ?: failure.toString()) }
        }
    }
    return remember(picker) { object : FilePickerLauncher {
        override fun launch() { picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
    } }
}
