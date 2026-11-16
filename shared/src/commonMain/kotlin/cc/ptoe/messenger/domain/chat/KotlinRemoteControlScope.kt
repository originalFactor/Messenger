/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.chat

import kotlinx.serialization.Serializable

/** Immutable, credential-free identity of a remote-control connection. */
@Serializable
data class KotlinRemoteControlScope(
    val accountId: String,
    val generation: String,
    val baseUrl: String,
    val deviceId: String,
    /** A revoked grant never becomes valid again when remote control is re-enabled. */
    val grantEpoch: Long? = null,
)
