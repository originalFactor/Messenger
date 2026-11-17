/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.domain.chat

/** Local-only details of an MCP call whose remote effects remain unknown. */
data class UncertainMcpCall(val turnId: String, val callId: String, val toolName: String,
    val argumentsJson: String, val argumentsTruncated: Boolean, val canAcknowledge: Boolean) {
    override fun toString(): String = "UncertainMcpCall(turnId=$turnId, callId=$callId, toolName=$toolName, arguments=[redacted], argumentsTruncated=$argumentsTruncated, canAcknowledge=$canAcknowledge)"
}
