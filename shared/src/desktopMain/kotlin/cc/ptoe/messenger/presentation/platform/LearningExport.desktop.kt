package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.*
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.learning_export
import cc.ptoe.messenger.generated.resources.learning_import
import cc.ptoe.messenger.generated.resources.learning_package_limit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
internal actual fun rememberLearningExportLauncher(onError: (String) -> Unit, onSaved: () -> Unit): LearningExportLauncher {
    val scope = rememberCoroutineScope()
    val failed by rememberUpdatedState(onError)
    val saved by rememberUpdatedState(onSaved)
    val title = stringResource(Res.string.learning_export)
    return remember(scope) { object : LearningExportLauncher {
        override fun launch(filename: String, bytes: ByteArray, mimeType: String) {
            scope.launch {
                try {
                    val file = suspendCancellableCoroutine<java.io.File?> { continuation ->
                        EventQueue.invokeLater {
                            if (continuation.isActive) {
                                try {
                                    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
                                    continuation.invokeOnCancellation { EventQueue.invokeLater { dialog.dispose() } }
                                    val selected = try {
                                        dialog.file = filename
                                        dialog.isVisible = true
                                        dialog.files.firstOrNull()
                                    } finally { dialog.dispose() }
                                    if (continuation.isActive) continuation.resume(selected)
                                } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                            }
                        }
                    } ?: return@launch
                    withContext(Dispatchers.IO) { file.outputStream().use { it.write(bytes) } }
                    saved()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { failed(failure.message ?: failure.toString()) }
            }
        }
    } }
}

@Composable
internal actual fun rememberLearningImportLauncher(onPicked: (String) -> Unit, onError: (String) -> Unit): FilePickerLauncher {
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    val title = stringResource(Res.string.learning_import)
    val limit = stringResource(Res.string.learning_package_limit)
    return remember(scope) { object : FilePickerLauncher {
        override fun launch() {
            scope.launch {
                try {
                    val file = suspendCancellableCoroutine<java.io.File?> { continuation ->
                        EventQueue.invokeLater {
                            if (continuation.isActive) {
                                try {
                                    val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
                                    continuation.invokeOnCancellation { EventQueue.invokeLater { dialog.dispose() } }
                                    val selected = try {
                                        dialog.isVisible = true
                                        dialog.files.firstOrNull()
                                    } finally { dialog.dispose() }
                                    if (continuation.isActive) continuation.resume(selected)
                                } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                            }
                        }
                    } ?: return@launch
                    val text = withContext(Dispatchers.IO) {
                        require(file.length() <= 96L * 1024 * 1024) { limit }
                        file.inputStream().use { input ->
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
    } }
}
