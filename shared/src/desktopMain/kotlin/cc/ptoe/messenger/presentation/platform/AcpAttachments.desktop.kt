package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

private fun attachment(file: File): AcpAttachment {
    require(file.length() <= 16 * 1024 * 1024) { "Attachment exceeds 16 MB" }
    return AcpAttachment(file.name, Files.probeContentType(file.toPath()) ?: "application/octet-stream", file.readBytes())
}
@Composable
internal actual fun rememberAcpFilePicker(onPicked: (AcpAttachment) -> Unit, onError: (String) -> Unit): FilePickerLauncher {
    val scope = rememberCoroutineScope()
    return remember(onPicked) { object : FilePickerLauncher {
        override fun launch() { scope.launch {
            val files = withContext(Dispatchers.IO) { FileDialog(null as Frame?, "Add files", FileDialog.LOAD).let {
                it.isMultipleMode = true; it.isVisible = true; val selected = it.files; it.dispose(); selected
            } }
            try { files.forEach { file -> onPicked(withContext(Dispatchers.IO) { attachment(file) }) } } catch (failure: Exception) { onError(failure.message.orEmpty()) }
        } }
    } }
}
internal actual suspend fun readAcpClipboardAttachments(): List<AcpAttachment> = withContext(Dispatchers.IO) {
    val clip = Toolkit.getDefaultToolkit().systemClipboard
    when {
        clip.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) -> (clip.getData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().map(::attachment)
        clip.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
            val source = clip.getData(DataFlavor.imageFlavor) as java.awt.Image
            val image = BufferedImage(source.getWidth(null), source.getHeight(null), BufferedImage.TYPE_INT_ARGB)
            image.createGraphics().let { it.drawImage(source, 0, 0, null); it.dispose() }
            val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            listOf(AcpAttachment("Clipboard.png", "image/png", bytes))
        }
        else -> emptyList()
    }
}
