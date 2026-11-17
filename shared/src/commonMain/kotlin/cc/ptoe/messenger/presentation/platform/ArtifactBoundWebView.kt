/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import cc.ptoe.messenger.domain.artifact.ArtifactUiBinding
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.artifact_host_open
import org.jetbrains.compose.resources.stringResource

/**
 * A trusted UI owns exactly one binding for this native sandbox. On Desktop restart, the caller
 * must replace it with the result of ArtifactUiHost.restart; never reuse a stopped binding.
 */
@Composable
fun ArtifactBoundWebView(
    binding: ArtifactUiBinding,
    onRestart: (ArtifactUiBinding) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active by binding.active.collectAsState()
    if (active) {
        ArtifactWebView(
            files = binding.files,
            entry = binding.entry,
            stateJson = binding.stateJson,
            bindingIdentity = binding.identity,
            onMessage = binding::onMessage,
            onDisposeBinding = binding::stop,
            onRestartBinding = { onRestart(binding) },
            modifier = modifier,
        )
    } else {
        // A stopped binding cannot be re-used, even when its package bytes are unchanged.
        Button(onClick = { onRestart(binding) }, modifier = modifier) { Text(stringResource(Res.string.artifact_host_open)) }
    }
}
