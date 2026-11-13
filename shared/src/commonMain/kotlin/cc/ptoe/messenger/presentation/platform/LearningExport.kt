package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.Composable

internal interface LearningExportLauncher {
    fun launch(filename: String, bytes: ByteArray, mimeType: String = "application/json")
}

/** Bounded UTF-8 JSON package picker, matching Core's 96 MiB request limit. */
@Composable
internal expect fun rememberLearningImportLauncher(onPicked: (String) -> Unit, onError: (String) -> Unit): FilePickerLauncher

/** User-selected destination; resource bytes never enter saved-state Bundles. */
@Composable
internal expect fun rememberLearningExportLauncher(onError: (String) -> Unit, onSaved: () -> Unit = {}): LearningExportLauncher

@Composable
internal fun rememberLearningAssetExportLauncher(onError: (String) -> Unit): (String, String, ByteArray) -> Unit {
    val launcher = rememberLearningExportLauncher(onError)
    return { filename, mimeType, bytes -> launcher.launch(filename, bytes, mimeType) }
}
