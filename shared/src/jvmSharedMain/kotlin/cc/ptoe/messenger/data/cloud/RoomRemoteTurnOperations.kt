/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.cloud

import cc.ptoe.messenger.data.chat.KotlinTurnPresentation
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.domain.chat.KotlinChatTurnRunner
import cc.ptoe.messenger.domain.chat.KotlinRemoteControlScope
import cc.ptoe.messenger.domain.chat.KotlinRemotePromptIdentity
import cc.ptoe.messenger.domain.chat.KotlinTurnAdmission
import cc.ptoe.messenger.domain.chat.KotlinTurnCommand
import cc.ptoe.messenger.domain.chat.KotlinTurnPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Must use the same runner and Room owner as local chat and lifecycle management. */
internal class RoomRemoteTurnOperations(private val store: RoomKotlinTurnStore, private val runner: KotlinChatTurnRunner) {
    suspend fun decide(scope: KotlinRemoteControlScope, conversationId: String, turnId: String, option: String) {
        require(!conversationId.startsWith("acp:")) { "ACP task decisions are unavailable" }
        val (verb, callId) = option.split(':', limit = 2).let { require(it.size == 2 && it[1].isNotBlank()); it[0] to it[1] }
        val approved = when (verb) { "approve" -> true; "deny" -> false; else -> error("Invalid approval option") }
        runner.respondRemoteApproval(scope, conversationId, turnId, callId, approved)
    }

    suspend fun cancel(scope: KotlinRemoteControlScope, conversationId: String, turnId: String) {
        require(!conversationId.startsWith("acp:")) { "ACP task cancellation is unavailable" }
        runner.cancelRemoteAndAwait(scope, conversationId, turnId)
    }

    /** A relay disconnect cancels and joins the admitted worker, as in the Rust remote path. */
    suspend fun prompt(
        scope: KotlinRemoteControlScope,
        conversationId: String,
        text: String,
        permissions: ReceiveChannel<String>,
        update: suspend (JsonObject) -> Unit,
        identity: KotlinRemotePromptIdentity? = null,
        admit: suspend (suspend () -> KotlinTurnAdmission) -> KotlinTurnAdmission = { it() },
    ): JsonObject = coroutineScope {
        var accepted: String? = null
        var terminal = false
        var decisions: Job? = null
        try {
            val admitted = admit { runner.submit(KotlinTurnCommand.Send(conversationId, text, null, disableTools = identity != null,
                remoteScope = scope, remotePrompt = identity)) { accepted = it } }
            val turnId = when (admitted) {
                is KotlinTurnAdmission.Accepted -> admitted.turnId
                KotlinTurnAdmission.ModelRequired -> return@coroutineScope errorEvent("A model is required")
                is KotlinTurnAdmission.Rejected -> return@coroutineScope errorEvent(admitted.reason)
            }
            decisions = launch { for (option in permissions) decide(scope, conversationId, turnId, option) }
            var previous: KotlinTurnPresentation? = null
            var result: JsonObject? = null
            store.observePresentation(turnId, conversationId).transformWhile { state ->
                emit(state)
                state.phase !in setOf(KotlinTurnPhase.FINISHED, KotlinTurnPhase.CANCELLED, KotlinTurnPhase.FAILED, KotlinTurnPhase.INTERRUPTED_TOOL)
            }.collect { state ->
                val prior = previous
                val oldContent = if (prior?.messageId == state.messageId) prior.content else ""
                check(state.content.startsWith(oldContent)) { "Streaming content changed unexpectedly" }
                val delta = state.content.substring(oldContent.length)
                for (chunk in chunks(delta, 8_000)) update(buildJsonObject { put("type", "TextDelta"); put("text", chunk) })
                if (state.summaryFailure != null && state.summaryFailure != prior?.summaryFailure) update(buildJsonObject {
                    put("type", "SummaryFailed"); put("message", limitedRemoteText(state.summaryFailure, 8_000))
                })
                if (state.approvalFailure != null && state.approvalFailure != prior?.approvalFailure) update(buildJsonObject {
                    put("type", "ApprovalFailed"); put("message", limitedRemoteText(state.approvalFailure, 8_000))
                })
                val pending = state.pendingTool?.takeIf { state.phase == KotlinTurnPhase.WAITING_APPROVAL }
                val oldPending = prior?.pendingTool?.takeIf { prior.phase == KotlinTurnPhase.WAITING_APPROVAL }
                if (pending != null && pending != oldPending) update(buildJsonObject {
                    put("type", "Permission")
                    put("options", JsonArray(listOf(
                        buildJsonObject { put("id", "approve:${pending.callId}"); put("name", "Approve") },
                        buildJsonObject { put("id", "deny:${pending.callId}"); put("name", "Deny") },
                    )))
                    put("tool", buildJsonObject { put("title", limitedRemoteText(pending.name, 180)); put("detail", limitedRemoteText(pending.arguments, 1_500)) })
                })
                result = when (state.phase) {
                    KotlinTurnPhase.FINISHED -> {
                        if (state.titleFailure != null) update(buildJsonObject { put("type", "TitleFailed"); put("message", limitedRemoteText(state.titleFailure, 8_000)) })
                        buildJsonObject { put("type", "Finished"); put("messageId", state.messageId) }
                    }
                    KotlinTurnPhase.CANCELLED -> buildJsonObject { put("type", "Cancelled") }
                    KotlinTurnPhase.FAILED, KotlinTurnPhase.INTERRUPTED_TOOL -> errorEvent(state.detail ?: "Chat turn failed")
                    else -> null
                }
                previous = state
            }
            val finished = checkNotNull(result) { "Turn ended without a terminal event" }
            runner.awaitStopped(turnId)
            terminal = true
            finished
        } finally {
            withContext(NonCancellable) {
                decisions?.cancelAndJoin()
                if (!terminal) accepted?.let { runner.cancelAndAwait(it) }
            }
        }
    }
}

private fun errorEvent(message: String) = buildJsonObject { put("type", "Error"); put("message", limitedRemoteText(message, 8_000)) }
private fun limitedRemoteText(value: String, max: Int): String =
    if (value.codePointCount(0, value.length) <= max) value else value.substring(0, value.offsetByCodePoints(0, max)) + "…"
private fun chunks(value: String, max: Int): List<String> = buildList {
    var start = 0
    while (start < value.length) {
        val count = value.codePointCount(start, value.length)
        val end = value.offsetByCodePoints(start, minOf(count, max))
        add(value.substring(start, end)); start = end
    }
}
