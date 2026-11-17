/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.platform

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cc.ptoe.messenger.domain.artifact.ArtifactUiBinding
import cc.ptoe.messenger.domain.artifact.ArtifactUiHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private class ArtifactPreviewOwner { val bindings = mutableSetOf<ArtifactUiBinding>() }

/**
 * Trusted UI preview for a revision that has already received explicit device authorization.
 * A binding is unique to one native view; disposal revokes it and the host joins its cleanup.
 * Callers must separately present and record the device user's execution/network decisions.
 */
@Composable
fun ManagedArtifactPreview(host: ArtifactUiHost, id: String, revision: Long, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val owner = remember(host, id, revision) { ArtifactPreviewOwner() }
    val previewJob = remember(owner) { SupervisorJob(scope.coroutineContext[Job]) }
    val previewScope = remember(owner) { CoroutineScope(scope.coroutineContext + previewJob) }
    val owned = owner.bindings
    var current by remember(owner) { mutableStateOf<ArtifactUiBinding?>(null) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var restarting by remember(owner) { mutableStateOf(false) }

    DisposableEffect(owner) {
        onDispose { previewJob.cancel(); owned.forEach(ArtifactUiBinding::stop); owned.clear() }
    }
    LaunchedEffect(owner) {
        try {
            val binding = host.open(id, revision)
            try {
                currentCoroutineContext().ensureActive()
                owned.add(binding)
                current = binding
            } catch (failure: Throwable) { binding.stop(); throw failure }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "Application is unavailable" }
    }
    val binding = current
    when {
        binding != null -> Column(modifier) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ArtifactBoundWebView(binding, onRestart = { previous ->
            if (!restarting) {
                restarting = true
                previewScope.launch {
                    var replacement: ArtifactUiBinding? = null
                    var published = false
                    try {
                        replacement = host.restart(previous)
                        currentCoroutineContext().ensureActive()
                        if (current === previous) {
                            owned.remove(previous)
                            owned.add(replacement)
                            current = replacement
                            error = null
                            published = true
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = failure.message ?: "Application is unavailable" }
                    finally { if (!published) replacement?.stop(); restarting = false }
                }
            }
            }, modifier = Modifier.fillMaxSize())
        }
        error != null -> Text(error.orEmpty(), modifier = modifier, color = MaterialTheme.colorScheme.error)
        else -> CircularProgressIndicator(modifier = modifier)
    }
}
