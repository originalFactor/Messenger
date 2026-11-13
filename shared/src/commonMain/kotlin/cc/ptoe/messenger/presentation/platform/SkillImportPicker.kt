package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.Composable

data class SkillImportBundle(
    val files: Map<String, String>,
    val binaryFiles: Map<String, String> = emptyMap(),
)

/** Reads a ZIP package or a standalone SKILL.md without executing its contents. */
@Composable
internal expect fun rememberSkillImportPicker(
    onPicked: (SkillImportBundle) -> Unit,
    onError: (String) -> Unit,
): FilePickerLauncher
