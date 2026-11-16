/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.acp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Keeps active output bounded; completed buffers remain available to detached observers. */
internal class AcpTurnCoordinator(private val journal: RoomAcpSessionJournal, private val now: () -> Long) {
    private class Turn(val binding: AcpWorkerBinding, val broker: AcpPermissionBroker) {
        val replay = AcpEventBuffer()
        val text = StringBuilder()
        val transcript = mutableListOf<JsonElement>()
        var transcriptBytes = 0L
        var completed = false
    }
    private class Restore { var projector: AcpReplayProjector? = null; var completed: JsonArray? = null }
    private val gate = Mutex()
    private val turns = linkedMapOf<String, Turn>()
    private val mutableActive = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeTurns: StateFlow<Map<String, String>> = mutableActive
    private val restores = mutableMapOf<String, Restore>()

    fun eventsFor(binding: AcpWorkerBinding): AcpStdioEvents = eventsFor { binding }

    internal fun eventsFor(binding: () -> AcpWorkerBinding): AcpStdioEvents = object : AcpStdioEvents {
        override suspend fun restoreStarted(replay: Boolean) {
            val binding = binding()
            gate.withLock { restores[binding.conversationId] = Restore().also { if (replay) it.projector = AcpReplayProjector(now) } }
        }
        override suspend fun restoreFinished(success: Boolean) {
            val binding = binding()
            gate.withLock {
                val state = restores[binding.conversationId] ?: return@withLock
                state.completed = if (success) state.projector?.snapshot() else null
                state.projector = null
            }
        }
        override suspend fun update(turnId: String?, update: JsonObject) {
            val binding = binding()
            gate.withLock {
                if (turnId == null) {
                    restores[binding.conversationId]?.projector?.accept(update)
                    return@withLock
                }
                val turn = turns[turnId]?.takeIf { it.binding == binding && !it.completed } ?: return@withLock
                val kind = (update["sessionUpdate"] as? JsonPrimitive)?.contentOrNull ?: return@withLock
                val text = ((update["content"] as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
                val event = when (kind) {
                    "agent_message_chunk" -> buildJsonObject { put("type", "TextDelta"); put("text", text) }
                    "agent_thought_chunk" -> buildJsonObject { put("type", "ThoughtDelta"); put("text", text) }
                    "tool_call", "tool_call_update" -> buildJsonObject { put("type", "ToolUpdate"); put("update", update) }
                    "config_option_update", "current_mode_update", "usage_update", "available_commands_update" ->
                        buildJsonObject { put("type", "SessionSettings"); put("update", update) }
                    else -> return@withLock
                }
                if (kind == "agent_message_chunk") {
                    require(turn.text.length.toLong() + text.length <= 4L * 1024 * 1024) { "ACP answer exceeds limit" }
                    turn.text.append(text)
                }
                if (kind in setOf("agent_message_chunk", "agent_thought_chunk", "tool_call", "tool_call_update")) {
                    turn.transcriptBytes += event.toString().encodeToByteArray().size
                    require(turn.transcriptBytes <= 8 * 1024 * 1024 && turn.transcript.size < 10_000) { "ACP transcript exceeds limit" }
                    turn.transcript.add(event)
                }
                turn.replay.append(event)
            }
        }
        override suspend fun permission(turnId: String, prompt: AcpPermissionPrompt?) {
            val binding = binding()
            gate.withLock {
                val turn = turns[turnId]?.takeIf { it.binding == binding && !it.completed } ?: return@withLock
                turn.replay.permission(prompt)
                val event = if (prompt == null) buildJsonObject { put("type", "PermissionResolved") } else prompt.toUiEvent()
                turn.replay.append(event)
            }
        }
    }

    suspend fun begin(binding: AcpWorkerBinding, turnId: String, content: JsonArray, broker: AcpPermissionBroker) {
        val turn = Turn(binding, broker)
        val replay = gate.withLock {
            check(turnId !in turns) { "ACP turn already recorded" }
            while (turns.size >= 64) {
                val oldest = turns.entries.firstOrNull { it.value.completed } ?: error("Too many active ACP turns")
                turns.remove(oldest.key)
            }
            check(binding.conversationId !in mutableActive.value) { "ACP conversation already has an active recorded turn" }
            turns[turnId] = turn
            mutableActive.value = mutableActive.value + (binding.conversationId to turnId)
            restores[binding.conversationId]?.completed
        }
        try {
            if (replay != null) {
                journal.replaceReplay(binding.conversationId, turnId, replay, now())
                gate.withLock { restores[binding.conversationId]?.completed = null }
            }
            val text = content.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                if ((obj["type"] as? JsonPrimitive)?.contentOrNull != "text") null else (obj["text"] as? JsonPrimitive)?.contentOrNull
            }.joinToString("\n")
            journal.begin(binding.conversationId, turnId, text, content, now())
            turn.replay.append(buildJsonObject { put("type", "TurnStarted") })
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                gate.withLock {
                    turn.completed = true
                    if (mutableActive.value[binding.conversationId] == turnId) mutableActive.value = mutableActive.value - binding.conversationId
                }
                turn.replay.append(buildJsonObject { put("type", "Error"); put("message", "ACP turn could not be recorded") })
                turn.replay.complete()
                gate.withLock { if (mutableActive.value[binding.conversationId] == turnId) mutableActive.value = mutableActive.value - binding.conversationId }
            }
            throw failure
        }
    }

    suspend fun finish(turnId: String, result: JsonObject?, error: String?) {
        val (turn, snapshot) = gate.withLock {
            val current = turns[turnId]?.takeIf { !it.completed } ?: return
            current.completed = true
            current to Triple(current.text.toString(), JsonArray(current.transcript.toList()), current.binding)
        }
        val agentError = error ?: if ((result?.get("type") as? JsonPrimitive)?.contentOrNull == "Error")
            (result?.get("message") as? JsonPrimitive)?.contentOrNull ?: "ACP agent failed" else null
        try {
            journal.finish(snapshot.third.conversationId, turnId, snapshot.first, snapshot.second, agentError, now())
            turn.replay.append(result ?: buildJsonObject { put("type", if (error == null) "Cancelled" else "Error"); if (error != null) put("message", error) })
        } catch (failure: Throwable) {
            turn.replay.append(buildJsonObject { put("type", "Error"); put("message", "ACP transcript could not be saved") })
            throw failure
        } finally {
            turn.replay.complete()
            gate.withLock { if (mutableActive.value[turn.binding.conversationId] == turnId) mutableActive.value = mutableActive.value - turn.binding.conversationId }
        }
    }

    suspend fun decide(turnId: String, option: String): Boolean {
        val broker = gate.withLock { turns[turnId]?.takeUnless { it.completed }?.broker } ?: return false
        return broker.choose(turnId, option)
    }
    suspend fun snapshot(turnId: String, after: Long): AcpEventSnapshot =
        (gate.withLock { turns[turnId]?.replay } ?: error("ACP turn is unavailable")).snapshot(after)
    suspend fun changes(turnId: String): StateFlow<Long> = gate.withLock { turns[turnId]?.replay?.changes ?: error("ACP turn is unavailable") }
}

/** Adds durable input, transcript and replay without changing the ACP wire implementation. */
internal class RecordingAcpWorker(initialBinding: AcpWorkerBinding, private val worker: AcpWorkerProcess, private val events: AcpTurnCoordinator,
    private val onBind: (AcpWorkerBinding) -> Unit = {}) : AcpWorkerProcess, AcpTurnFinalizer, AcpNewSessionBinding {
    @Volatile private var binding = initialBinding
    private val begun = mutableSetOf<String>()
    override suspend fun start(binding: AcpWorkerBinding, configuration: AcpWorkerConfiguration, gateway: AcpGatewayCore): AcpWorkerReady {
        check(binding == this.binding) { "ACP worker binding changed" }
        return worker.start(binding, configuration, gateway)
    }
    override fun bindCreatedSession(binding: AcpWorkerBinding) {
        val old = this.binding
        check(old.agentSessionId.isEmpty() && binding.copy(agentSessionId = "") == old && binding.agentSessionId.isNotBlank()) { "ACP created session binding changed" }
        (worker as? AcpNewSessionBinding ?: error("ACP worker cannot adopt a new session")).bindCreatedSession(binding)
        this.binding = binding
        onBind(binding)
    }
    override suspend fun prompt(turnId: String, content: JsonArray, permissions: AcpPermissionBroker): JsonObject {
        withContext(NonCancellable) { events.begin(binding, turnId, content, permissions); begun.add(turnId) }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return worker.prompt(turnId, content, permissions)
    }
    override suspend fun finishTurn(turnId: String, result: JsonObject?, error: String?) {
        if (begun.remove(turnId)) events.finish(turnId, result, error)
    }
    override suspend fun cancelAndConfirm() = worker.cancelAndConfirm()
    override suspend fun stopAndAwait() = worker.stopAndAwait()
}

/** Constructs real stdio workers while requiring the platform to supply a stop-confirming transport. */
internal fun recordingAcpWorkerFactory(
    coordinator: AcpTurnCoordinator,
    transports: (AcpWorkerBinding) -> AcpLineTransport,
    endpoints: AcpGatewayEndpointFactory,
    discovery: suspend (AcpWorkerBinding) -> String?,
): AcpWorkerFactory = AcpWorkerFactory { binding ->
    val current = java.util.concurrent.atomic.AtomicReference(binding)
    val wire = AcpStdioWorker(transports(binding), endpoints, { discovery(current.get()) }, coordinator.eventsFor { current.get() })
    RecordingAcpWorker(binding, wire, coordinator) { current.set(it) }
}
