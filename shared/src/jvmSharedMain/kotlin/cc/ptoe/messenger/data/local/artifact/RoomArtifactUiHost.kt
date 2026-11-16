/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.artifact

import cc.ptoe.messenger.data.migration.RoomManagedShutdown
import cc.ptoe.messenger.domain.artifact.ArtifactUiBinding
import cc.ptoe.messenger.domain.artifact.ArtifactUiHost
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Implemented by trusted UI; each call must represent the device user's explicit decision. */
internal interface ArtifactUiConsent {
    suspend fun authorizeRevision(id: String, revision: Long)
    suspend fun grantDomains(id: String, revision: Long, domains: List<String>)
}

/**
 * One Room-owner-scoped host. Native sandboxes receive only per-instance bindings; they never
 * receive this host, credentials, arbitrary file access or authority to grant permissions.
 * The owner must close native entry points before shutdown and separately prove helper processes
 * stopped where that is required; a stopped binding alone is not process-stop evidence.
 */
internal class RoomArtifactUiHost private constructor(
    private val artifacts: RoomArtifactService,
    private val sandbox: ArtifactSandboxHost,
    private val consent: ArtifactUiConsent,
) : ArtifactUiHost {
    private val lock = Any()
    private val shutdownLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val admitted = mutableSetOf<Job>()
    private val bindings = mutableSetOf<Binding>()
    private var stopping = false
    private var stopped = false
    private var cleanupFailure: Throwable? = null

    companion object {
        /** Register cleanup before making the host available to trusted UI. */
        suspend fun attach(artifacts: RoomArtifactService, sandbox: ArtifactSandboxHost,
            consent: ArtifactUiConsent, shutdown: RoomManagedShutdown): RoomArtifactUiHost {
            val host = RoomArtifactUiHost(artifacts, sandbox, consent)
            try { withContext(NonCancellable) { shutdown.registerStop { host.shutdown() } } }
            catch (failure: Throwable) {
                try { host.shutdown() } catch (cleanup: Throwable) { if (failure !== cleanup) failure.addSuppressed(cleanup) }
                throw failure
            }
            return host
        }
    }

    private suspend fun <T> admission(block: suspend () -> T): T = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        synchronized(lock) {
            check(!stopping) { "Artifact host is shutting down" }
            check(cleanupFailure == null) { "Artifact host requires cleanup recovery" }
            admitted.add(job)
            job.invokeOnCompletion { synchronized(lock) { admitted.remove(job) } }
        }
        block()
    }

    override suspend fun observeChanges(id: String, onChange: () -> Unit): Unit = admission {
        artifacts.observeUiChanges(id).collect { onChange() }
    }

    override suspend fun isRevisionAuthorized(id: String, revision: Long): Boolean = admission {
        require(revision > 0) { "Invalid artifact revision" }
        artifacts.isExecutionAuthorized(id, revision)
    }

    override suspend fun authorizeRevision(id: String, revision: Long) = admission {
        require(revision > 0) { "Invalid artifact revision" }
        consent.authorizeRevision(id, revision)
        currentCoroutineContext().ensureActive()
        artifacts.authorizeExecution(id, revision)
    }

    override suspend fun grantDomains(id: String, revision: Long, domains: List<String>) = admission {
        require(revision > 0) { "Invalid artifact revision" }
        val requested = domains.toList()
        consent.grantDomains(id, revision, requested)
        currentCoroutineContext().ensureActive()
        artifacts.grantDomains(id, requested, revision)
        Unit
    }

    override suspend fun resetState(id: String, revision: Long, stateVersion: Long) = admission {
        require(revision > 0 && stateVersion >= 0) { "Invalid artifact revision or state version" }
        sandbox.resetState(id, revision, stateVersion)
    }

    override suspend fun open(id: String, revision: Long): ArtifactUiBinding = admission { openBinding(id, revision) }

    private suspend fun openBinding(id: String, revision: Long): Binding {
        require(revision > 0) { "Invalid artifact revision" }
        val native = sandbox.open(id, revision)
        val binding = Binding(this, id, revision, native)
        try {
            binding.listenForInvalidation()
            synchronized(lock) {
                check(!stopping) { "Artifact host is shutting down" }
                check(cleanupFailure == null) { "Artifact host requires cleanup recovery" }
                bindings.add(binding)
            }
            // A revision can change between package preparation and listener registration.
            artifacts.withExecutionAuthorization(id, revision) { check(binding.active.value) { "Artifact binding changed" } }
            currentCoroutineContext().ensureActive()
            return binding
        } catch (failure: Throwable) {
            try { withContext(NonCancellable) { binding.closeNow() } }
            catch (cleanup: Throwable) { if (failure !== cleanup) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    override suspend fun restart(previous: ArtifactUiBinding): ArtifactUiBinding = admission {
        val old = previous as? Binding ?: error("Binding belongs to another artifact host")
        require(old.owner === this@RoomArtifactUiHost) { "Binding belongs to another artifact host" }
        check(old.restartClaimed.compareAndSet(false, true)) { "Binding has already been restarted" }
        old.stop()
        // A new helper must not receive a capability until the old binding's work has stopped.
        withContext(NonCancellable) { old.closeNow() }
        currentCoroutineContext().ensureActive()
        openBinding(old.artifactId, old.revision)
    }

    private fun disposed(binding: Binding) {
        synchronized(lock) {
            if (!stopping && cleanupFailure == null && binding in bindings) {
                scope.launch { try { binding.closeNow() } catch (_: Exception) { /* shutdown retries */ } }
            }
        }
    }

    private fun closed(binding: Binding) = synchronized(lock) { bindings.remove(binding); Unit }
    private fun isStopping(): Boolean = synchronized(lock) { stopping || cleanupFailure != null }
    private fun failedCleanup(failure: Throwable) {
        val current = synchronized(lock) {
            if (cleanupFailure == null) cleanupFailure = failure
            bindings.toList()
        }
        current.forEach { it.stop() }
    }

    /** Retains failed bindings for a retry; Room must remain open until this returns. */
    suspend fun shutdown() = withContext(NonCancellable) {
        shutdownLock.withLock {
            if (stopped) return@withLock
            val (calls, beforeJoin) = synchronized(lock) { stopping = true; admitted.toList() to bindings.toList() }
            // Native callbacks lose authority immediately, even while an admitted UI call drains.
            beforeJoin.forEach { it.stop() }
            calls.forEach { it.cancel() }
            calls.joinAll()
            val active = synchronized(lock) { bindings.toList() }
            active.forEach { it.stop() }
            var failure: Throwable? = null
            for (binding in active) try { binding.closeNow() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
            failure?.let { throw it }
            scope.coroutineContext[Job]?.cancelAndJoin()
            stopped = true
        }
    }

    private class Binding(
        val owner: RoomArtifactUiHost,
        val artifactId: String,
        val revision: Long,
        private val sandbox: ArtifactSandboxBinding,
    ) : ArtifactUiBinding {
        private val lock = Any()
        private val closeLock = Mutex()
        private var stopped = false
        private var closed = false
        private val mutableActive = MutableStateFlow(true)
        private val invalidated: () -> Unit = { stop() }
        val restartClaimed = AtomicBoolean(false)
        override val files = sandbox.sandboxPackage.files
        override val entry = sandbox.sandboxPackage.entry
        override val stateJson = sandbox.sandboxPackage.stateJson
        override val identity = Any()
        override val active = mutableActive.asStateFlow()

        fun listenForInvalidation() { owner.artifacts.addListener(artifactId, invalidated) }

        override fun onMessage(raw: String, reply: (String) -> Unit) {
            synchronized(lock) {
                if (stopped || owner.isStopping()) return
                owner.scope.launch {
                    try { sandbox.dispatch(raw) { value -> synchronized(lock) { if (!stopped) reply(value) } } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) {
                        val id = runCatching {
                            if (raw.length > 1_048_576 || raw.encodeToByteArray().size > 1_048_576) null
                            else (Json.parseToJsonElement(raw) as? JsonObject)?.get("id") as? JsonPrimitive
                        }
                            .getOrNull()?.takeIf { it.isString && it.content.isNotEmpty() && it.content.encodeToByteArray().size <= 128 && it.content.none(Char::isISOControl) }
                        if (id != null) synchronized(lock) {
                            if (!stopped) reply(buildJsonObject { put("id", id); put("error", "Application request failed") }.toString())
                        }
                    }
                }
            }
        }

        override fun stop() {
            val first = synchronized(lock) {
                if (stopped) false else { stopped = true; mutableActive.value = false; true }
            }
            if (first) { sandbox.stop(); owner.disposed(this) }
        }

        suspend fun closeNow() = withContext(NonCancellable) {
            closeLock.withLock {
                if (closed) return@withLock
                stop()
                try { sandbox.close() } catch (failure: Throwable) {
                    owner.failedCleanup(failure)
                    throw failure
                }
                owner.artifacts.removeListener(artifactId, invalidated)
                closed = true
                owner.closed(this@Binding)
            }
        }
    }
}
