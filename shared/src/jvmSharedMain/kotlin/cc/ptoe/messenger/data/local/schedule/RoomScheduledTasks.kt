/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.local.schedule

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.LocalRowsDao
import cc.ptoe.messenger.data.local.room.CLOUD_REPLACEMENT_LOCK_KEY
import cc.ptoe.messenger.data.util.randomUuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

private const val TASK = "scheduled_task:"
private const val ACTIVE = "kotlin_scheduled_active_v1:"
private const val TOOL_SETTING = "scheduled_task_tool_enabled"
internal const val SCHEDULE_TOOL = "messenger_schedule_task"
private const val AGENTS_TOOL = "messenger_agents"
private val codec = Json { encodeDefaults = true; explicitNulls = false }

/** Matches Core's existing `scheduled_task:<id>` JSON; local-only and excluded from cloud sync. */
@Serializable
internal data class KotlinScheduledTask(
    val id: String, val name: String, val prompt: String, val agentId: String, val projectId: String? = null,
    val intervalMinutes: Long, val nextRunAt: Long, val enabled: Boolean = true,
    val lastRunAt: Long? = null, val lastError: String? = null, val lastConversationId: String? = null,
    val allowedTools: List<String>? = null,
)

@Serializable
internal data class ScheduledRunClaim(val taskId: String, val token: String, val startedAt: Long, val cancelRequested: Boolean = false)
internal class ScheduledTurnUnavailable(message: String) : kotlinx.coroutines.CancellationException(message)

/** Caller holds the same Room writer transaction that admits the turn. */
internal suspend fun requireScheduledTurnAdmission(rows: LocalRowsDao,
    claim: cc.ptoe.messenger.domain.chat.KotlinScheduledTurnClaim, conversation: cc.ptoe.messenger.data.local.room.ConversationRow,
    prompt: String, matchDefinition: Boolean = true) {
    check(rows.getValue(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
    val active = rows.getValue(ACTIVE + claim.taskId)?.value?.let { codec.decodeFromString<ScheduledRunClaim>(it) }
        ?: throw ScheduledTurnUnavailable("Scheduled run is unavailable")
    if (active.taskId != claim.taskId || active.token != claim.token || active.startedAt != claim.startedAt || active.cancelRequested)
        throw ScheduledTurnUnavailable("Scheduled run was cancelled or replaced")
    val task = rows.getValue(TASK + claim.taskId)?.value?.let { codec.decodeFromString<KotlinScheduledTask>(it) }
        ?: throw ScheduledTurnUnavailable("Scheduled task was deleted")
    if (conversation.projectId != claim.projectId) throw ScheduledTurnUnavailable("Scheduled project changed")
    claim.projectId?.let { projectId ->
        val project = rows.project(projectId) ?: throw ScheduledTurnUnavailable("Scheduled project is unavailable")
        val device = rows.getValue("device_id")?.value
        if (device.isNullOrBlank() || project.deviceId != device) throw ScheduledTurnUnavailable("Scheduled project is not local")
    }
    if (task.id != claim.taskId || !task.enabled || task.lastRunAt != claim.startedAt || task.lastConversationId != conversation.id ||
        (matchDefinition && (task.agentId != conversation.agentId || task.projectId != conversation.projectId || task.prompt != prompt)))
        throw ScheduledTurnUnavailable("Scheduled task changed before admission")
}

/** Fail closed on malformed or older unbound scheduled turns; deleted tasks can still have turns. */
private suspend fun LocalRowsDao.activeScheduledTaskIds(): Set<String> {
    val active = valuesByPrefix("kotlin_turn_active_v1:")
    check(active.size <= 10_000) { "Too many active turns for scheduled recovery" }
    return buildSet {
        for (entry in active) {
            val turn = getValue("kotlin_turn_v1:${entry.value}")?.value?.let { codec.parseToJsonElement(it) as? JsonObject }
                ?: error("Active scheduled turn cannot be inspected")
            val id = (turn["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val conversation = (turn["conversationId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            check(id == entry.value && conversation != null && entry.key == "kotlin_turn_active_v1:$conversation") { "Invalid active turn identity" }
            val scheduled = turn["scheduledRun"]?.let { (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.booleanOrNull
                ?: error("Invalid scheduled turn identity") } ?: false
            if (scheduled) {
                val claim = turn["scheduledClaim"] as? JsonObject ?: error("Scheduled turn requires separate recovery")
                val parsed = codec.decodeFromJsonElement<cc.ptoe.messenger.domain.chat.KotlinScheduledTurnClaim>(claim)
                fun validUuid(id: String) = runCatching { java.util.UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)
                check(validUuid(parsed.taskId) && validUuid(parsed.token) && parsed.startedAt > 0) { "Invalid scheduled turn claim" }
                add(parsed.taskId)
            }
        }
    }
}

/** Isolated persistence component. The scheduler must observe cancellation and wait for workers to stop. */
internal class RoomScheduledTasks(private val db: LocalDatabase) {
    private suspend fun <T> transaction(block: suspend (LocalRowsDao) -> T): T = db.useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block(db.rows()) }
    }
    private fun decode(raw: String) = codec.decodeFromString<KotlinScheduledTask>(raw)
    private suspend fun LocalRowsDao.task(id: String) = getValue(TASK + id)?.value?.let(::decode)
    private suspend fun LocalRowsDao.claim(id: String) = getValue(ACTIVE + id)?.value?.let { codec.decodeFromString<ScheduledRunClaim>(it) }
    private suspend fun LocalRowsDao.write(task: KotlinScheduledTask) = upsertValues(listOf(KeyValueRow(TASK + task.id, codec.encodeToString(task))))
    private suspend fun LocalRowsDao.write(claim: ScheduledRunClaim) = upsertValues(listOf(KeyValueRow(ACTIVE + claim.taskId, codec.encodeToString(claim))))

    private suspend fun LocalRowsDao.validate(task: KotlinScheduledTask) {
        require(runCatching { java.util.UUID.fromString(task.id).toString() == task.id.lowercase() }.getOrDefault(false)) { "Invalid scheduled task ID" }
        require(task.name.isNotBlank() && task.name.encodeToByteArray().size <= 256 && task.prompt.isNotBlank() &&
            task.prompt.encodeToByteArray().size <= 65536 && task.intervalMinutes in 1..525600 && task.nextRunAt > 0) { "Invalid scheduled task" }
        require(agent(task.agentId)?.role?.let { it != "title" } == true) { "Scheduled Agent is unavailable" }
        task.projectId?.let { id ->
            val project = requireNotNull(project(id)) { "Scheduled project is unavailable" }
            val device = getValue("device_id")?.value
            require(!device.isNullOrBlank() && project.deviceId == device) { "Scheduled project must belong to this device" }
        }
        require(task.allowedTools == null || task.allowedTools.distinct().size == task.allowedTools.size) { "Duplicate scheduled tool" }
    }
    suspend fun list(): List<KotlinScheduledTask> = transaction { rows ->
        rows.valuesByPrefix(TASK).map { row ->
            decode(row.value).also { check(row.key == TASK + it.id) { "Invalid scheduled task key" } }
        }.sortedWith(compareBy({ it.name }, { it.id }))
    }
    suspend fun toolEnabled(): Boolean = transaction { it.getValue(TOOL_SETTING)?.value == "true" }
    suspend fun setToolEnabled(enabled: Boolean) = transaction { it.upsertValues(listOf(KeyValueRow(TOOL_SETTING, enabled.toString()))) }
    suspend fun create(task: KotlinScheduledTask): KotlinScheduledTask = transaction { rows ->
        rows.validate(task)
        check(rows.task(task.id) == null && rows.claim(task.id) == null) { "Scheduled task already exists or is still stopping" }
        task.copy(lastRunAt = null, lastError = null, lastConversationId = null, allowedTools = null).also { rows.write(it) }
    }
    /** A stale editor cannot overwrite the run status or the tool-created authorization ceiling. */
    suspend fun update(task: KotlinScheduledTask): KotlinScheduledTask = transaction { rows ->
        rows.validate(task)
        val old = requireNotNull(rows.task(task.id)) { "Scheduled task not found" }
        check(task.lastRunAt == old.lastRunAt) { "Scheduled task ran since it was loaded" }
        val next = task.copy(lastRunAt = old.lastRunAt, lastError = old.lastError, lastConversationId = old.lastConversationId, allowedTools = old.allowedTools)
        rows.write(next)
        if (!next.enabled) rows.claim(task.id)?.let { rows.write(it.copy(cancelRequested = true)) }
        next
    }
    /** Callers must cancel and join the matching worker when this returns an active claim. */
    suspend fun delete(id: String): ScheduledRunClaim? = transaction { rows ->
        requireNotNull(rows.task(id)) { "Scheduled task not found" }
        val active = rows.claim(id)?.copy(cancelRequested = true)
        if (active != null) rows.write(active)
        rows.deleteValue(TASK + id)
        active
    }
    suspend fun active(id: String): ScheduledRunClaim? = transaction { it.claim(id) }
    suspend fun hasActiveRuns(): Boolean = transaction { it.valuesByPrefix(ACTIVE).isNotEmpty() }
    /** Cancel the active scheduled run which owns this conversation, if any. */
    suspend fun cancelByConversation(conversationId: String): Boolean = transaction { rows ->
        val matches = rows.valuesByPrefix(TASK).map { row ->
            decode(row.value).also { check(row.key == TASK + it.id) { "Invalid scheduled task key" } }
        }.filter { it.lastConversationId == conversationId }.mapNotNull { task ->
            rows.claim(task.id)?.let { task to it }
        }
        check(matches.size <= 1) { "Scheduled conversation ownership is ambiguous" }
        val claim = matches.singleOrNull()?.second ?: return@transaction false
        rows.write(claim.copy(cancelRequested = true))
        true
    }
    fun observeActive(id: String): Flow<ScheduledRunClaim?> = db.rows().observeValue(ACTIVE + id).map { row ->
        row?.value?.let { codec.decodeFromString<ScheduledRunClaim>(it) }
    }
    /** Caller must cancel and join each returned worker. */
    suspend fun clear(): List<ScheduledRunClaim> = transaction { rows ->
        val claims = mutableListOf<ScheduledRunClaim>()
        for (row in rows.valuesByPrefix(TASK)) {
            val task = decode(row.value)
            check(row.key == TASK + task.id) { "Invalid scheduled task key" }
            rows.claim(task.id)?.copy(cancelRequested = true)?.let { rows.write(it); claims.add(it) }
            rows.deleteValue(row.key)
        }
        claims
    }
    /** Claim advances the next time and persists an interrupted result before starting any turn. */
    suspend fun claimDue(now: Long): List<Pair<KotlinScheduledTask, ScheduledRunClaim>> = transaction { rows ->
        require(now > 0)
        val result = mutableListOf<Pair<KotlinScheduledTask, ScheduledRunClaim>>()
        for (row in rows.valuesByPrefix(TASK)) {
            val task = decode(row.value)
            check(row.key == TASK + task.id) { "Invalid scheduled task key" }
            if (!task.enabled || task.nextRunAt > now || rows.claim(task.id) != null) continue
            require(task.intervalMinutes in 1..525600 && task.nextRunAt > 0) { "Invalid scheduled task interval" }
            val delay = task.intervalMinutes * 60_000L
            val nextTime = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
            val claimed = task.copy(lastRunAt = now, lastError = "Execution interrupted before completion", lastConversationId = null, nextRunAt = nextTime)
            val claim = ScheduledRunClaim(task.id, randomUuid(), now)
            rows.write(claimed); rows.write(claim); result.add(claimed to claim)
        }
        result
    }
    /** Records a conversation early so cancellation or a crash never hides a created conversation. */
    suspend fun recordConversation(claim: ScheduledRunClaim, conversationId: String): Boolean = transaction { rows ->
        val current = rows.claim(claim.taskId) ?: return@transaction false
        if (current != claim || current.cancelRequested) return@transaction false
        val task = rows.task(claim.taskId) ?: return@transaction false
        if (task.lastRunAt != claim.startedAt || (task.lastConversationId != null && task.lastConversationId != conversationId)) return@transaction false
        rows.write(task.copy(lastConversationId = conversationId)); true
    }
    /** Publish the conversation and attach it to the still-live claim in the same transaction. */
    suspend fun publishConversation(scope: ScheduledExecutionScope, conversationId: String, create: suspend (LocalRowsDao) -> Unit): Boolean = transaction { rows ->
        require(conversationId.isNotBlank()) { "Scheduled conversation ID is empty" }
        val claim = scope.claim
        val current = rows.claim(claim.taskId) ?: return@transaction false
        if (current != claim || current.cancelRequested) return@transaction false
        val task = rows.task(claim.taskId) ?: return@transaction false
        if (!task.enabled || task.lastRunAt != claim.startedAt || task.lastConversationId != null ||
            task.agentId != scope.agentId || task.projectId != scope.projectId || task.prompt != scope.prompt) return@transaction false
        check(rows.getValue(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
        check(rows.conversation(conversationId) == null) { "Scheduled conversation already exists" }
        create(rows)
        rows.write(task.copy(lastConversationId = conversationId))
        true
    }
    suspend fun finish(claim: ScheduledRunClaim, error: String?, conversationId: String?): Boolean =
        finishWithStatus(claim, error, conversationId) != null

    suspend fun finishWithStatus(claim: ScheduledRunClaim, error: String?, conversationId: String?): String? = transaction { rows ->
        val current = rows.claim(claim.taskId) ?: run {
            check(claim.taskId !in rows.activeScheduledTaskIds()) { "Scheduled turn has no claim and requires recovery" }
            return@transaction null
        }
        if (current.token != claim.token || current.startedAt != claim.startedAt) return@transaction null
        check(claim.taskId !in rows.activeScheduledTaskIds()) { "Scheduled turn remains active and requires recovery" }
        val status = if (current.cancelRequested) "cancelled" else if (error != null) "failed" else "completed"
        rows.task(claim.taskId)?.takeIf { it.lastRunAt == claim.startedAt }?.let { task ->
            rows.write(task.copy(lastError = if (current.cancelRequested) "Execution cancelled" else error,
                lastConversationId = conversationId ?: task.lastConversationId))
        }
        rows.deleteValue(ACTIVE + claim.taskId)
        status
    }
    /** Invoke only after proving all prior-process workers have stopped. Never re-executes a claim. */
    suspend fun recoverInterrupted() = transaction { rows ->
        val turns = rows.activeScheduledTaskIds()
        check(turns.isEmpty()) { "Scheduled turns remain active and require recovery" }
        for (entry in rows.valuesByPrefix(ACTIVE)) {
            val claim = codec.decodeFromString<ScheduledRunClaim>(entry.value)
            check(entry.key == ACTIVE + claim.taskId) { "Invalid scheduled run claim" }
            rows.deleteValue(entry.key)
        }
    }

    /** Run admission rechecks ownership, the model and the current policy; recursion is always forbidden. */
    suspend fun executionScope(claim: ScheduledRunClaim, availableNames: Set<String>): ScheduledExecutionScope = transaction { rows ->
        val current = requireNotNull(rows.claim(claim.taskId)) { "Scheduled run is unavailable" }
        check(current == claim && !current.cancelRequested) { "Scheduled run was cancelled or replaced" }
        val task = requireNotNull(rows.task(claim.taskId)) { "Scheduled task was deleted" }
        check(task.enabled && task.lastRunAt == claim.startedAt) { "Scheduled task changed" }
        rows.validate(task)
        val agent = requireNotNull(rows.agent(task.agentId))
        check(agent.defaultModelId != null) { "Scheduled Agent requires a model" }
        val default = rows.defaultAgents().singleOrNull() ?: error("Default Agent not found")
        val modelId = if (!agent.isDefault && agent.followDefaultModel) default.defaultModelId else agent.defaultModelId
        val model = modelId?.let { rows.model(it) } ?: error("Scheduled Agent requires a model")
        check(model.isEnabled && rows.provider(model.providerId) != null) { "Scheduled model is unavailable" }
        val follow = !agent.isDefault && agent.toolsFollowDefault
        val enabled = if (follow) default.toolsEnabled else agent.toolsEnabled
        val rawConfig = if (follow) default.toolsConfig else agent.toolsConfig
        val config = if (rawConfig.isBlank()) emptyMap() else codec.decodeFromString<Map<String, Boolean>>(rawConfig)
        val names = if (!enabled) emptySet() else availableNames.filterTo(mutableSetOf()) { name ->
            name != SCHEDULE_TOOL && name != AGENTS_TOOL && config[name] != false && (task.allowedTools == null || name in task.allowedTools)
        }
        ScheduledExecutionScope(task.id, task.agentId, task.projectId, task.prompt, names, claim)
    }

    /** Atomically rechecks the persisted turn, tool setting and live Agent policy before creating a job. */
    suspend fun createFromTool(turnId: String, call: cc.ptoe.messenger.data.remote.sse.ToolCallData, now: Long,
        authorization: ScheduleCreationAuthorization): KotlinScheduledTask {
        authorization.requireAllowed(turnId, call.callId)
        return transaction { rows ->
        require(call.name == SCHEDULE_TOOL && now > 0)
        check(rows.getValue(TOOL_SETTING)?.value == "true") { "Scheduled task tool is disabled" }
        val turn = rows.getValue("kotlin_turn_v1:$turnId")?.value?.let { codec.parseToJsonElement(it) as? JsonObject }
            ?: error("Turn not found")
        val conversationId = (turn["conversationId"] as? JsonPrimitive)?.contentOrNull ?: error("Invalid turn")
        check((turn["id"] as? JsonPrimitive)?.contentOrNull == turnId &&
            (turn["phase"] as? JsonPrimitive)?.contentOrNull == "EXECUTING_TOOL" &&
            rows.getValue("kotlin_turn_active_v1:$conversationId")?.value == turnId) { "Turn is no longer executing" }
        val index = (turn["index"] as? JsonPrimitive)?.intOrNull ?: error("Invalid tool index")
        val pending = ((turn["calls"] as? JsonArray)?.getOrNull(index) as? JsonObject) ?: error("Missing pending tool call")
        check((pending["id"] as? JsonPrimitive)?.contentOrNull == call.callId &&
            (pending["name"] as? JsonPrimitive)?.contentOrNull == call.name &&
            (pending["arguments"] as? JsonPrimitive)?.contentOrNull == call.arguments) { "Tool call changed" }
        val conversation = requireNotNull(rows.conversation(conversationId)) { "Conversation not found" }
        check(conversation.writable && conversation.overrideToolsEnabled == null && conversation.overrideToolsConfig == null) {
            "Scheduling requires a writable conversation without tool overrides"
        }
        val agent = requireNotNull(rows.agent(conversation.agentId)) { "Agent not found" }
        val default = rows.defaultAgents().singleOrNull() ?: error("Default Agent not found")
        val follow = !agent.isDefault && agent.toolsFollowDefault
        check(if (follow) default.toolsEnabled else agent.toolsEnabled) { "Agent tools are disabled" }
        val rawConfig = if (follow) default.toolsConfig else agent.toolsConfig
        val config = if (rawConfig.isBlank()) emptyMap() else codec.decodeFromString<Map<String, Boolean>>(rawConfig)
        check(config[SCHEDULE_TOOL] != false) { "Scheduling is disabled by Agent policy" }
        val request = turn["request"] as? JsonObject ?: error("Missing turn request")
        val declared = (request["tools"] as? JsonArray)?.map {
            val function = (it as? JsonObject)?.get("function") as? JsonObject ?: error("Invalid declared tool")
            (function["name"] as? JsonPrimitive)?.contentOrNull ?: error("Invalid declared tool")
        } ?: error("No declared tools")
        check(SCHEDULE_TOOL in declared && declared.distinct().size == declared.size) { "Scheduling was not declared for this turn" }
        val arguments = codec.decodeFromString<ScheduleToolArguments>(call.arguments)
        val first = when {
            arguments.nextRunAt != null && arguments.firstRunDelayMinutes == null -> arguments.nextRunAt
            arguments.nextRunAt == null && arguments.firstRunDelayMinutes != null && arguments.firstRunDelayMinutes in 1..525600 -> {
                val delay = arguments.firstRunDelayMinutes * 60_000
                if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
            }
            else -> error("Specify exactly one first execution time")
        }
        require(first > now) { "First execution must be in the future" }
        val projectId = if (arguments.projectless) null else requireNotNull(conversation.projectId) { "Select projectless for a conversation without a project" }
        val invocation = ("${turnId.length}:$turnId${call.callId}").encodeToByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(invocation).joinToString("") { "%02x".format(it.toInt() and 255) }
        val marker = "kotlin_schedule_invocation:$digest"
        rows.getValue(marker)?.value?.let { id -> return@transaction requireNotNull(rows.task(id)) { "This tool invocation was already completed" } }
        val task = KotlinScheduledTask(randomUuid(), arguments.name, arguments.prompt, agent.id, projectId, arguments.intervalMinutes, first,
            allowedTools = declared.filterNot { it == SCHEDULE_TOOL || it == AGENTS_TOOL })
        rows.validate(task)
        rows.write(task)
        rows.upsertValues(listOf(KeyValueRow(marker, task.id)))
        task
        }
    }
}

internal data class ScheduledExecutionScope(val taskId: String, val agentId: String, val projectId: String?, val prompt: String, val allowedTools: Set<String>, val claim: ScheduledRunClaim)

@Serializable
private data class ScheduleToolArguments(
    val name: String, val prompt: String, val intervalMinutes: Long,
    val nextRunAt: Long? = null, val firstRunDelayMinutes: Long? = null, val projectless: Boolean,
)

/** Caller must verify the turn's immutable provenance, including that it is not a scheduled run. */
internal fun interface ScheduleCreationAuthorization { suspend fun requireAllowed(turnId: String, callId: String) }

/** Deliberately unconnected until scheduler, cancellation and provenance are installed together. */
internal class ScheduleToolAdapter(private val store: RoomScheduledTasks, private val authorization: ScheduleCreationAuthorization, private val now: () -> Long) {
    suspend fun execute(turnId: String, call: cc.ptoe.messenger.data.remote.sse.ToolCallData): cc.ptoe.messenger.domain.tool.ToolExecutionResult = try {
        val task = store.createFromTool(turnId, call, now(), authorization)
        cc.ptoe.messenger.domain.tool.ToolExecutionResult("Scheduled task created: ${task.id}", structuredContent = buildJsonObject {
            put("scheduledTask", codec.encodeToJsonElement(KotlinScheduledTask.serializer(), task))
        })
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        cc.ptoe.messenger.domain.tool.ToolExecutionResult(failure.message ?: "Scheduled task creation failed", isError = true)
    }
}

/** Discovery descriptor only; execution still requires the adapter and its turn authorization. */
internal fun scheduleToolDeclaration(): cc.ptoe.messenger.data.remote.dto.ToolSpecDto {
    val schema = codec.parseToJsonElement("""{"type":"object","properties":{"name":{"type":"string"},"prompt":{"type":"string"},"intervalMinutes":{"type":"integer","minimum":1,"maximum":525600},"nextRunAt":{"type":"integer"},"firstRunDelayMinutes":{"type":"integer","minimum":1,"maximum":525600},"projectless":{"type":"boolean"}},"required":["name","prompt","intervalMinutes","projectless"],"additionalProperties":false}""").jsonObject
    return cc.ptoe.messenger.data.remote.dto.ToolSpecDto("function", cc.ptoe.messenger.data.remote.dto.ToolSpecFunctionDto(
        SCHEDULE_TOOL, "Create a recurring scheduled task for this Agent. Tasks run only while the app is active and use the current tool permissions.", schema))
}
