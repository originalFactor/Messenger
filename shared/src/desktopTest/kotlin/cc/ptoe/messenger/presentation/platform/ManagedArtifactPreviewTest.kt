/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import cc.ptoe.messenger.domain.artifact.ArtifactUiBinding
import cc.ptoe.messenger.domain.artifact.ArtifactUiHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class ManagedArtifactPreviewTest {
    private class Binding : ArtifactUiBinding {
        override val files = mapOf("index.html" to "same")
        override val entry = "index.html"
        override val stateJson = "null"
        override val identity = Any()
        override val active = MutableStateFlow(false)
        var stops = 0
        override fun onMessage(raw: String, reply: (String) -> Unit) = Unit
        override fun stop() { stops++ }
    }

    @Test fun restartUsesANewBindingAndDisposalRevokesIt() = runComposeUiTest {
        val first = Binding()
        val second = Binding()
        var opened = 0
        var restarted = 0
        val visible = mutableStateOf(true)
        val host = object : ArtifactUiHost {
            override suspend fun observeChanges(id: String, onChange: () -> Unit): Unit = awaitCancellation()
            override suspend fun isRevisionAuthorized(id: String, revision: Long) = true
            override suspend fun authorizeRevision(id: String, revision: Long) = Unit
            override suspend fun grantDomains(id: String, revision: Long, domains: List<String>) = Unit
            override suspend fun resetState(id: String, revision: Long, stateVersion: Long) = Unit
            override suspend fun open(id: String, revision: Long): ArtifactUiBinding { opened++; return first }
            override suspend fun restart(previous: ArtifactUiBinding): ArtifactUiBinding {
                assertSame(first, previous)
                first.stop()
                restarted++
                return second
            }
        }
        setContent { if (visible.value) ManagedArtifactPreview(host, "artifact", 1) }
        waitUntil(timeoutMillis = 5_000) { opened == 1 }
        onNodeWithText("Open interactive preview").performClick()
        waitUntil(timeoutMillis = 5_000) { restarted == 1 }
        runOnIdle { visible.value = false }
        runOnIdle { assertEquals(1, first.stops); assertEquals(1, second.stops) }
    }

    @Test fun changingArtifactWhileLoadingCancelsTheOldOpen() = runComposeUiTest {
        val selected = mutableStateOf("first")
        val opens = AtomicInteger()
        val oldCancelled = AtomicBoolean(false)
        val host = object : ArtifactUiHost {
            override suspend fun observeChanges(id: String, onChange: () -> Unit): Unit = awaitCancellation()
            override suspend fun isRevisionAuthorized(id: String, revision: Long) = true
            override suspend fun authorizeRevision(id: String, revision: Long) = Unit
            override suspend fun grantDomains(id: String, revision: Long, domains: List<String>) = Unit
            override suspend fun resetState(id: String, revision: Long, stateVersion: Long) = Unit
            override suspend fun open(id: String, revision: Long): ArtifactUiBinding {
                opens.incrementAndGet()
                if (id == "first") try { awaitCancellation() } finally { oldCancelled.set(true) }
                return Binding()
            }
            override suspend fun restart(previous: ArtifactUiBinding): ArtifactUiBinding = error("Unexpected restart")
        }
        setContent { ManagedArtifactPreview(host, selected.value, 1) }
        waitUntil(timeoutMillis = 5_000) { opens.get() == 1 }
        runOnIdle { selected.value = "second" }
        waitUntil(timeoutMillis = 5_000) { opens.get() == 2 && oldCancelled.get() }
    }

    @Test fun changingArtifactCancelsAnInFlightRestart() = runComposeUiTest {
        val selected = mutableStateOf("first")
        val opens = AtomicInteger()
        val restarting = AtomicBoolean(false)
        val restartCancelled = AtomicBoolean(false)
        val first = Binding()
        val host = object : ArtifactUiHost {
            override suspend fun observeChanges(id: String, onChange: () -> Unit): Unit = awaitCancellation()
            override suspend fun isRevisionAuthorized(id: String, revision: Long) = true
            override suspend fun authorizeRevision(id: String, revision: Long) = Unit
            override suspend fun grantDomains(id: String, revision: Long, domains: List<String>) = Unit
            override suspend fun resetState(id: String, revision: Long, stateVersion: Long) = Unit
            override suspend fun open(id: String, revision: Long): ArtifactUiBinding {
                opens.incrementAndGet()
                return if (id == "first") first else Binding()
            }
            override suspend fun restart(previous: ArtifactUiBinding): ArtifactUiBinding {
                restarting.set(true)
                try { awaitCancellation() } finally { restartCancelled.set(true) }
            }
        }
        setContent { ManagedArtifactPreview(host, selected.value, 1) }
        waitUntil(timeoutMillis = 5_000) { opens.get() == 1 }
        onNodeWithText("Open interactive preview").performClick()
        waitUntil(timeoutMillis = 5_000) { restarting.get() }
        runOnIdle { selected.value = "second" }
        waitUntil(timeoutMillis = 5_000) { opens.get() == 2 && restartCancelled.get() }
    }
}
