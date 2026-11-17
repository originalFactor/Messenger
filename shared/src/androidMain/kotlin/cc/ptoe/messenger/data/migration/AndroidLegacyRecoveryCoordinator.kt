/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.migration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.action_cancel
import cc.ptoe.messenger.generated.resources.legacy_recovery_cancelled
import cc.ptoe.messenger.generated.resources.legacy_recovery_applying
import cc.ptoe.messenger.generated.resources.legacy_recovery_done
import cc.ptoe.messenger.generated.resources.legacy_recovery_failed
import cc.ptoe.messenger.generated.resources.legacy_recovery_running
import cc.ptoe.messenger.generated.resources.legacy_recovery_saved
import cc.ptoe.messenger.generated.resources.legacy_recovery_uncertain
import cc.ptoe.messenger.generated.resources.legacy_review_title
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** UI state does not itself grant access to any source database. */
internal sealed interface AndroidLegacyRecoveryState {
    data object Idle : AndroidLegacyRecoveryState
    data object Preparing : AndroidLegacyRecoveryState
    data class Review(val review: LegacyRecoveryReview) : AndroidLegacyRecoveryState
    data object Applying : AndroidLegacyRecoveryState
    data class Completed(val applied: LegacyRecoveryApplied) : AndroidLegacyRecoveryState
    data object Cancelled : AndroidLegacyRecoveryState
    data class Failed(val detail: String) : AndroidLegacyRecoveryState
    data object Uncertain : AndroidLegacyRecoveryState
}

/**
 * Bridges an Android UI to the existing offline session. The supplied owner must already prove
 * lifetime exclusion of every old Core, Runtime, legacy and Room writer and path replacement;
 * this coordinator and its screen do not supply that proof. The caller must launch [run] in a
 * dedicated supervised coroutine and keep it alive through configuration changes and cleanup.
 * A failed or cancelled ownership block can retain its durable marker and requires separately
 * verified platform recovery; this coordinator does not release that marker. Not installed
 * in production: no Android owner currently provides the required platform guarantees.
 */
internal class AndroidLegacyRecoveryCoordinator(
    private val core: Path,
    private val legacy: Path,
    private val destination: Path,
    private val scratchParent: Path,
    private val ownership: RoomStoreOwnership,
    private val openRoom: (Path) -> LocalDatabase,
    private val now: () -> Long,
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<AndroidLegacyRecoveryState>(AndroidLegacyRecoveryState.Idle)
    val state = mutableState.asStateFlow()
    private var worker: Job? = null
    private var pending: CompletableDeferred<LegacyRecoverySelection?>? = null

    /** One call per coordinator; no caller may enter a second session during cleanup. */
    suspend fun run(): LegacyRecoveryApplied? {
        val job = checkNotNull(currentCoroutineContext()[Job]) { "Recovery requires an owning job" }
        synchronized(lock) {
            check(mutableState.value == AndroidLegacyRecoveryState.Idle) { "Recovery is already used" }
            worker = job
            mutableState.value = AndroidLegacyRecoveryState.Preparing
        }
        return try {
            val result = withContext(Dispatchers.IO) {
                LegacyRoomRecoverySession.run(core, legacy, destination, scratchParent, ownership, openRoom, now) { review ->
                    val choice = CompletableDeferred<LegacyRecoverySelection?>()
                    synchronized(lock) {
                        pending = choice
                        mutableState.value = AndroidLegacyRecoveryState.Review(review)
                    }
                    try { choice.await() } finally { synchronized(lock) { if (pending === choice) pending = null } }
                }
            }
            synchronized(lock) { mutableState.value = result?.let(AndroidLegacyRecoveryState::Completed) ?: AndroidLegacyRecoveryState.Cancelled }
            result
        } catch (cancelled: CancellationException) {
            synchronized(lock) { mutableState.value = if (mutableState.value == AndroidLegacyRecoveryState.Applying)
                AndroidLegacyRecoveryState.Uncertain else AndroidLegacyRecoveryState.Cancelled }
            throw cancelled
        } catch (error: Exception) {
            synchronized(lock) { mutableState.value = if (mutableState.value == AndroidLegacyRecoveryState.Applying)
                AndroidLegacyRecoveryState.Uncertain else AndroidLegacyRecoveryState.Failed(error.message ?: "Recovery failed") }
            throw error
        } finally { synchronized(lock) { pending = null; worker = null } }
    }

    /** The selection is accepted only for the currently displayed review and its exact values. */
    fun apply(review: LegacyRecoveryReview, selection: LegacyRecoverySelection): Boolean = synchronized(lock) {
        val displayed = mutableState.value as? AndroidLegacyRecoveryState.Review ?: return@synchronized false
        if (displayed.review !== review) return@synchronized false
        val expected = LegacyRecoveryChoices(review).selection(selection.projects, selection.associations, selection.providerFormats)
        if (expected == null || expected != selection) return@synchronized false
        val choice = pending ?: return@synchronized false
        if (!choice.complete(selection)) return@synchronized false
        mutableState.value = AndroidLegacyRecoveryState.Applying
        true
    }

    /** Cancel preparation or decline a review; application already being committed is not cancelled. */
    fun cancel(): Boolean = synchronized(lock) {
        when (mutableState.value) {
            AndroidLegacyRecoveryState.Preparing -> worker?.let { it.cancel(); true } ?: false
            is AndroidLegacyRecoveryState.Review -> pending?.complete(null) == true
            else -> false
        }
    }
}

/** Caller must keep the owner coroutine alive until this screen reaches a terminal state. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AndroidLegacyRecoveryScreen(coordinator: AndroidLegacyRecoveryCoordinator, onExit: () -> Unit,
    modifier: Modifier = Modifier) {
    val state by coordinator.state.collectAsState()
    val reviewing = state as? AndroidLegacyRecoveryState.Review
    BackHandler(enabled = state is AndroidLegacyRecoveryState.Preparing || reviewing != null || state is AndroidLegacyRecoveryState.Applying) {
        coordinator.cancel()
    }
    if (reviewing != null) {
        LegacyRecoveryReviewScreen(reviewing.review, onCancel = { coordinator.cancel() },
            onApply = { if (!coordinator.apply(reviewing.review, it)) coordinator.cancel() }, modifier = modifier)
        return
    }
    Scaffold(modifier = modifier.fillMaxSize(), topBar = { TopAppBar(title = { Text(stringResource(Res.string.legacy_review_title)) }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val current = state) {
                AndroidLegacyRecoveryState.Idle, AndroidLegacyRecoveryState.Preparing, AndroidLegacyRecoveryState.Applying -> {
                    CircularProgressIndicator()
                    Text(stringResource(if (current == AndroidLegacyRecoveryState.Applying) Res.string.legacy_recovery_applying else Res.string.legacy_recovery_running))
                    if (current == AndroidLegacyRecoveryState.Preparing) Button(onClick = { coordinator.cancel() }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                }
                AndroidLegacyRecoveryState.Cancelled -> {
                    Text(stringResource(Res.string.legacy_recovery_cancelled))
                    Button(onClick = onExit) { Text(stringResource(Res.string.legacy_recovery_done)) }
                }
                AndroidLegacyRecoveryState.Uncertain -> {
                    Text(stringResource(Res.string.legacy_recovery_uncertain), color = MaterialTheme.colorScheme.error)
                    Button(onClick = onExit) { Text(stringResource(Res.string.legacy_recovery_done)) }
                }
                is AndroidLegacyRecoveryState.Completed -> {
                    Text(stringResource(Res.string.legacy_recovery_saved, current.applied.projects,
                        current.applied.associations, current.applied.providerFormats))
                    Button(onClick = onExit) { Text(stringResource(Res.string.legacy_recovery_done)) }
                }
                is AndroidLegacyRecoveryState.Failed -> {
                    Text(stringResource(Res.string.legacy_recovery_failed, current.detail), color = MaterialTheme.colorScheme.error)
                    Button(onClick = onExit) { Text(stringResource(Res.string.legacy_recovery_done)) }
                }
                is AndroidLegacyRecoveryState.Review -> Unit
            }
        }
    }
}
