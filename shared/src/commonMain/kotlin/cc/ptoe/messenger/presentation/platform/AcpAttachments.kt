package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.Composable

internal data class AcpAttachment(val name: String, val mimeType: String, val bytes: ByteArray)
@Composable
internal expect fun rememberAcpFilePicker(onPicked: (AcpAttachment) -> Unit, onError: (String) -> Unit): FilePickerLauncher
internal expect suspend fun readAcpClipboardAttachments(): List<AcpAttachment>
