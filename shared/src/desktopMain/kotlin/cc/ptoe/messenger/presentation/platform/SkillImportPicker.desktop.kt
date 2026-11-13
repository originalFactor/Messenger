package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

@Composable
internal actual fun rememberSkillImportPicker(
    onPicked: (SkillImportBundle) -> Unit,
    onError: (String) -> Unit,
): FilePickerLauncher {
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    return remember(scope) { object : FilePickerLauncher {
        override fun launch() {
            scope.launch {
                try {
                    val file = suspendCancellableCoroutine<java.io.File?> { continuation ->
                        EventQueue.invokeLater {
                            if (continuation.isActive) {
                                try {
                                    val dialog = FileDialog(null as Frame?, "SKILL.md / ZIP", FileDialog.LOAD)
                                    continuation.invokeOnCancellation { EventQueue.invokeLater { dialog.dispose() } }
                                    val selected = try {
                                        dialog.setFilenameFilter { _, name -> name.endsWith(".zip", true) || name.endsWith(".md", true) }
                                        dialog.isVisible = true
                                        dialog.files.firstOrNull()
                                    } finally { dialog.dispose() }
                                    if (continuation.isActive) continuation.resume(selected)
                                } catch (failure: Exception) {
                                    if (continuation.isActive) continuation.resumeWithException(failure)
                                }
                            }
                        }
                    } ?: return@launch
                    val files = withContext(Dispatchers.IO) { file.inputStream().use { decodeSkillImport(file.name, it) } }
                    picked(files)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failed(failure.message ?: failure.toString())
                }
            }
        }
    } }
}
