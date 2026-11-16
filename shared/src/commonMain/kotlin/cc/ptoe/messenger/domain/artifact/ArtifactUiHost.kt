/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.artifact

import kotlinx.coroutines.flow.StateFlow

/** The package and message capability belong to one native sandbox instance. */
interface ArtifactUiBinding {
    val files: Map<String, String>
    val entry: String
    val stateJson: String
    /** Must be compared by reference identity, never by value. */
    val identity: Any
    /** False when the binding is disposed or its revision or device grants change. */
    val active: StateFlow<Boolean>

    /** Routes only SDK messages from the native sandbox associated with [identity]. */
    fun onMessage(raw: String, reply: (String) -> Unit)

    /** Synchronously rejects new work; the host owns and joins asynchronous cleanup. */
    fun stop()
}

/** Trusted device UI only; generated code receives only [ArtifactUiBinding.onMessage]. */
interface ArtifactUiHost {
    /** Observe changes to this application's metadata and permissions until cancelled. */
    suspend fun observeChanges(id: String, onChange: () -> Unit)

    /** Reads authorization for the current revision without starting its code. */
    suspend fun isRevisionAuthorized(id: String, revision: Long): Boolean

    /** Requires explicit device-user approval for the specified current revision. */
    suspend fun authorizeRevision(id: String, revision: Long)

    /** Requires a separate device-user decision for the requested domains. */
    suspend fun grantDomains(id: String, revision: Long, domains: List<String>)

    /** Trusted UI reset; a stale revision or state version must fail. */
    suspend fun resetState(id: String, revision: Long, stateVersion: Long)

    /** Fails unless this precise current revision is authorized on this device. */
    suspend fun open(id: String, revision: Long): ArtifactUiBinding

    /** Stops and joins [previous] before creating a new native sandbox capability. */
    suspend fun restart(previous: ArtifactUiBinding): ArtifactUiBinding
}
