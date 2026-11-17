/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.artifact

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

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

    /** Reads device authorization for a specific revision without starting its code. */
    suspend fun isRevisionAuthorized(id: String, revision: Long): Boolean

    /** Requires explicit device-user approval for the specified revision. */
    suspend fun authorizeRevision(id: String, revision: Long)

    /** Approve only the exact revision content shown by the trusted UI. */
    suspend fun authorizeRevision(id: String, revision: Long, expectedContent: JsonObject) {
        error("Content-bound artifact authorization is unavailable")
    }

    /** Requires a separate device-user decision for the requested domains. */
    suspend fun grantDomains(id: String, revision: Long, domains: List<String>)

    /** Trusted UI reset; a stale revision or state version must fail. */
    suspend fun resetState(id: String, revision: Long, stateVersion: Long)

    /** Historical previews are read-only and have no network access. */
    suspend fun open(id: String, revision: Long): ArtifactUiBinding

    /** Stops and joins [previous] before creating a new native sandbox capability. */
    suspend fun restart(previous: ArtifactUiBinding): ArtifactUiBinding
}
