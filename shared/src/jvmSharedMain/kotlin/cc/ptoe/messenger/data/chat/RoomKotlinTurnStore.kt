/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.chat

import androidx.room.Transactor
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.cloud.SyncCollection
import cc.ptoe.messenger.data.cloud.SyncMutation
import cc.ptoe.messenger.data.cloud.SyncSession
import cc.ptoe.messenger.data.cloud.RoomCloudProviderGate
import cc.ptoe.messenger.data.cloud.canonicalCloudBase
import cc.ptoe.messenger.data.cloud.requireRemoteControl
import cc.ptoe.messenger.data.local.ContentPartCodec
import cc.ptoe.messenger.data.local.room.*
import cc.ptoe.messenger.data.remote.dto.ChatCompletionRequestDto
import cc.ptoe.messenger.data.remote.dto.ChatMessageDto
import cc.ptoe.messenger.data.remote.dto.ToolCallDto
import cc.ptoe.messenger.data.remote.dto.ToolCallFunctionDto
import cc.ptoe.messenger.data.remote.dto.ToolSpecDto
import cc.ptoe.messenger.data.remote.dto.UsageDto
import cc.ptoe.messenger.data.remote.sse.ToolCallData
import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.randomUuid
import cc.ptoe.messenger.domain.chat.*
import cc.ptoe.messenger.domain.model.ApiFormat
import cc.ptoe.messenger.domain.model.ContentPart
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.tool.ToolExecutionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** Must prepare the complete provider request without changing state or doing network I/O.
 * Unsupported attachments, tools or provider features must be rejected explicitly. */
fun interface KotlinTurnRequestPreparer {
    fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow, model: ModelRow, provider: ProviderRow, history: List<MessageRow>): ChatCompletionRequestDto
    fun prepare(conversation: ConversationRow, agent: AgentRow, defaultAgent: AgentRow, model: ModelRow, provider: ProviderRow, history: List<MessageRow>, disableTools: Boolean): ChatCompletionRequestDto =
        prepare(conversation, agent, defaultAgent, model, provider, history).also {
            require(!disableTools || it.tools.isNullOrEmpty()) { "This request preparer cannot disable tools" }
        }
    fun toolGrant(): KotlinRuntimeToolGrant? = null
    fun coreToolGrant(): KotlinCoreToolGrant? = null
    fun mcpToolGrant(): KotlinMcpToolGrant? = null
    fun effectiveSystemPrompt(agent: AgentRow, defaultAgent: AgentRow, request: ChatCompletionRequestDto): String =
        if (!agent.isDefault && agent.followDefaultSystemPrompt) defaultAgent.systemPrompt else agent.systemPrompt
    fun supportsContextCompaction(): Boolean = false
    fun rebuildContext(conversation: ConversationRow, history: List<MessageRow>, prompt: String): List<ChatMessageDto> = error("Context compaction unavailable")
    suspend fun validateAdmission(conversation: ConversationRow, request: ChatCompletionRequestDto) {}
}

/** Checks the effective live Agent/tool policy immediately before an approved call is claimed. */
fun interface KotlinTurnToolPolicy {
    suspend fun permits(turnId: String, conversation: ConversationRow, call: ToolCallData): Boolean
}

@Serializable
private data class StoredTool(val id: String, val name: String, val arguments: String) {
    fun domain() = ToolCallData(id, name, arguments)
}
@Serializable
private data class StoredTitle(
    val providerId: String, val model: String, val prompt: String, val temperature: Float?,
    val topP: Float?, val maxTokens: Int?, val cloudIdentity: KotlinCloudTurnIdentity? = null,
)
@Serializable
private data class StoredDelegation(val childId: String, val agentId: String, val projectId: String?, val writable: Boolean, val admitted: Boolean = false)
@Serializable
private data class StoredTurn(
    val id: String, val conversationId: String, val providerId: String, val assistantId: String,
    val phase: String, val request: ChatCompletionRequestDto, val calls: List<StoredTool> = emptyList(),
    val index: Int = 0, val owner: String? = null, val detail: String? = null,
    val toolGrant: KotlinRuntimeToolGrant? = null, val invocationStarted: Boolean = false,
    val cloudIdentity: KotlinCloudTurnIdentity? = null,
    val userMessageId: String? = null,
    val title: StoredTitle? = null,
    val titleFailure: String? = null,
    val titleFailureHasDurableNotice: Boolean = false,
    val summaryPlan: StoredSummaryPlan? = null,
    val summaryFailure: String? = null,
    val remoteScope: KotlinRemoteControlScope? = null,
    val coreGrant: KotlinCoreToolGrant? = null,
    val mcpGrant: KotlinMcpToolGrant? = null,
    val mcpOwner: String? = null,
    val scheduledRun: Boolean = false,
    val scheduledClaim: KotlinScheduledTurnClaim? = null,
    val imageFailed: Boolean = false,
    val delegatedRun: Boolean = false,
    val delegation: StoredDelegation? = null,
    val supportsApproval: Boolean = true,
    val approvalUserRequest: String? = null,
    val approvalFailure: String? = null,
    val approvalSettingsRevision: String? = null,
)
data class KotlinTurnFailure(
    val noticeId: String, val turnId: String, val detail: String,
    val conversationId: String? = null, val conversationTitle: String? = null,
)

@Serializable
private data class StoredTurnFailure(val turnId: String, val detail: String, val conversationId: String? = null)

@Serializable
private data class AcknowledgedMcpUncertainty(val conversationId: String, val turnId: String, val callId: String,
    val serverIdHash: String, val toolName: String, val acknowledgedAt: Long)

/** Platform evidence that every previous native chat worker and its side effects have stopped. */
internal fun interface PreviousChatWorkersStopped {
    suspend fun verify()
}

private const val TURN = "kotlin_turn_v1:"
internal const val ACTIVE_TURN_KEY_PREFIX = "kotlin_turn_active_v1:"
private const val ACTIVE = ACTIVE_TURN_KEY_PREFIX
private const val LATEST = "kotlin_turn_latest_v1:"
private const val LEGACY_NOTICE = "kotlin_turn_error_v1:"
private const val NOTICE = "kotlin_turn_error_v2:"
internal const val ACKNOWLEDGED_MCP_UNCERTAINTY = "kotlin_mcp_uncertainty_ack_v1:"
private const val APPROVAL_SETTINGS_REVISION = "kotlin_tool_approval_settings_revision_v1"
private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

/** Separate experimental Kotlin runtime. Existing Core KV entries are never overwritten. */
class RoomKotlinTurnStore(
    private val db: LocalDatabase,
    private val prepare: KotlinTurnRequestPreparer,
    private val policy: KotlinTurnToolPolicy,
    private val cloudGate: RoomCloudProviderGate? = null,
    private val clock: () -> Long = ::currentTimeMillis,
) : KotlinTurnStore {
    private val owner = randomUuid()
    private val mcpInvocations = ConcurrentHashMap<String, McpInvocationReceipt>()

    /** Non-serializable, one-use capability for one invocation in this exact live store. */
    internal class McpInvocationReceipt internal constructor(val turnId: String, val call: ToolCallData) {
        private var admitted = false
        private var cancelled = false
        private var settled = false
        fun cancelBeforeAdmission() = synchronized(this) { if (!admitted) cancelled = true }
        fun authorize() = synchronized(this) {
            check(!admitted && !cancelled && !settled) { "MCP invocation was cancelled or already admitted" }
            admitted = true
        }
        fun settleBeforeAdmission() = synchronized(this) {
            check(!admitted && !settled) { "MCP invocation may have been dispatched" }; settled = true
        }
        fun settleNotDispatched() = synchronized(this) {
            check(admitted && !settled) { "MCP invocation was not admitted or is already settled" }; settled = true
        }
    }
    init { require(cloudGate == null || cloudGate === RoomCloudProviderGate.forDao(db.cloudSync())) { "Cloud gate belongs to another Room database" } }
    private suspend fun <T> transaction(block: suspend (LocalRowsDao, suspend (String) -> Unit) -> T): T =
        db.useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val sync = db.cloudSync()
                val result = block(db.rows()) { conversationId ->
                    sync.enqueueLocal(SyncCollection.CONVERSATION, conversationId, SyncMutation.UPSERT)
                }
                sync.cleanupDeferredBuiltinProvider()
                result
            }
        }
    private suspend fun LocalRowsDao.read(id: String) = getValue(TURN + id)?.value?.let { json.decodeFromString<StoredTurn>(it) }
        ?: error("Turn not found")
    private suspend fun LocalRowsDao.write(turn: StoredTurn) = upsertValues(listOf(KeyValueRow(TURN + turn.id, json.encodeToString(turn))))
    private suspend fun LocalRowsDao.addTurnFailure(turn: StoredTurn, detail: String) {
        val key = NOTICE + randomUuid()
        check(getValue(key) == null) { "Turn notice identity collision" }
        upsertValues(listOf(KeyValueRow(key, json.encodeToString(StoredTurnFailure(turn.id, detail, turn.conversationId)))))
    }
    private fun StoredTurn.phase() = KotlinTurnPhase.valueOf(phase)
    private suspend fun LocalRowsDao.snapshot(turn: StoredTurn): KotlinTurnSnapshot {
        val row = provider(turn.providerId)
        if (row == null && turn.phase() in terminal) {
            return KotlinTurnSnapshot(turn.id, turn.conversationId, turn.phase(),
                Provider(turn.providerId, "Deleted provider", "", "", createdAt = 0, updatedAt = 0),
                turn.request, turn.calls.getOrNull(turn.index)?.domain(), turn.cloudIdentity)
        }
        checkNotNull(row) { "Provider no longer exists" }
        return KotlinTurnSnapshot(turn.id, turn.conversationId, turn.phase(),
            Provider(row.id, row.name, row.baseUrl, row.apiKey, ApiFormat.fromWireName(row.apiFormat), row.nativeWebSearch, row.createdAt, row.updatedAt),
            turn.request, turn.calls.getOrNull(turn.index)?.domain(), turn.cloudIdentity)
    }
    private fun StoredTurn.requireOwner() { check(owner == this@RoomKotlinTurnStore.owner) { "Turn is not owned by this runner" } }
    private fun StoredTurn.requirePhase(expected: KotlinTurnPhase) { check(phase() == expected) { "Turn state changed" } }
    private suspend fun LocalRowsDao.requireTurnCloudSession(turn: StoredTurn) {
        if (turn.providerId != BUILTIN_PROVIDER_ID) return
        val identity = checkNotNull(turn.cloudIdentity) { "Cloud session is unavailable" }
        val session = db.cloudSync().currentSession()
        check(session?.accountId == identity.accountId && session.generation == identity.generation &&
            getValue(DEFERRED_BUILTIN_PROVIDER_CLEANUP) == null) { "Cloud session changed" }
        // A 401 invalidates this identity before durable credentials can be cleared. The
        // gate check and invalidation share a short lock, never held during tool execution.
        checkNotNull(cloudGate) { "Cloud provider dispatch gate is unavailable" }
            .requireValid(SyncSession(identity.accountId, identity.generation))
    }
    private suspend fun LocalRowsDao.approvalPhase(turn: StoredTurn, call: ToolCallData): KotlinTurnPhase = when {
        isTrustedReadOnlyTool(call) -> KotlinTurnPhase.READY_TOOL
        getValue("tool_approval_mode")?.value == "automatic" -> KotlinTurnPhase.AUTO_APPROVAL_PENDING
        turn.supportsApproval -> KotlinTurnPhase.WAITING_APPROVAL
        else -> KotlinTurnPhase.APPROVAL_UNAVAILABLE
    }

    suspend fun toolApprovalMode(): String = if (db.rows().getValue("tool_approval_mode")?.value == "automatic") "automatic" else "manual"
    suspend fun setToolApprovalMode(mode: String) { transaction { rows, _ ->
        require(mode == "manual" || mode == "automatic") { "Invalid tool approval mode" }
        check(rows.getValue(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
        rows.upsertValues(listOf(KeyValueRow("tool_approval_mode", mode), KeyValueRow(APPROVAL_SETTINGS_REVISION, randomUuid())))
    } }
    private suspend fun LocalRowsDao.nextMessageTimestamp(conversationId: String): Long {
        val latest = latestMessageTimestamp(conversationId) ?: return clock()
        check(latest < Long.MAX_VALUE) { "Message timestamp is exhausted" }
        return maxOf(clock(), latest + 1)
    }
    private suspend fun LocalRowsDao.refreshConversationPreview(conversationId: String) {
        val last = messagesFor(listOf(conversationId)).lastOrNull() ?: return
        val preview = if (last.role == "user" && last.content.isEmpty() && last.partsJson != null) "📷" else last.content
        // Update only preview fields: a concurrent manual rename must remain intact.
        updateLastMessage(conversationId, preview, last.timestamp)
    }

    override suspend fun admit(command: KotlinTurnCommand): KotlinTurnAdmission = transaction { rows, mark ->
        command.remoteScope?.let { rows.requireRemoteControl(it, command.conversationId, requireOwnedProject = true) }
        if (rows.getValue(CLOUD_REPLACEMENT_LOCK_KEY) != null) return@transaction KotlinTurnAdmission.Rejected("Cloud replacement requires completion or recovery")
        if (command.conversationId.startsWith("acp:")) return@transaction KotlinTurnAdmission.Rejected("ACP turns require the ACP service")
        if (rows.getValue(ACTIVE + command.conversationId) != null) return@transaction KotlinTurnAdmission.Rejected("A turn is already active")
        val conversation = rows.conversation(command.conversationId) ?: return@transaction KotlinTurnAdmission.Rejected("Conversation not found")
        val scheduledClaim = (command as? KotlinTurnCommand.Send)?.scheduledClaim
        if (command.scheduledRun) {
            if (command !is KotlinTurnCommand.Send || scheduledClaim == null || command.delegatedRun || command.remoteScope != null)
                return@transaction KotlinTurnAdmission.Rejected("Scheduled turn claim is unavailable")
            cc.ptoe.messenger.data.local.schedule.requireScheduledTurnAdmission(rows, scheduledClaim, conversation, command.content)
        } else if (scheduledClaim != null) return@transaction KotlinTurnAdmission.Rejected("Unexpected scheduled turn claim")
        val delegationParent = command.delegationParent?.let { parent ->
            check(command is KotlinTurnCommand.Send && command.delegatedRun && !command.scheduledRun && command.remoteScope == null) { "Invalid delegated turn" }
            val turn = rows.requireActiveDelegation(parent, conversation.id, admitted = false)
            val binding = checkNotNull(turn.delegation)
            check(binding.agentId == conversation.agentId && binding.projectId == conversation.projectId && binding.writable == conversation.writable) { "Delegated conversation changed" }
            turn
        }
        val agent = rows.agent(conversation.agentId) ?: return@transaction KotlinTurnAdmission.Rejected("Agent not found")
        // This check precedes every transcript mutation, including retry and regeneration.
        if (agent.defaultModelId == null) return@transaction KotlinTurnAdmission.ModelRequired
        val defaultAgent = rows.defaultAgents().singleOrNull() ?: return@transaction KotlinTurnAdmission.Rejected("Default agent is unavailable")
        val effectiveModel = if (!agent.isDefault && agent.followDefaultModel) defaultAgent.defaultModelId else agent.defaultModelId
        val selectedModel = conversation.overrideModelId ?: effectiveModel ?: return@transaction KotlinTurnAdmission.ModelRequired
        val model = rows.model(selectedModel)
            ?: return@transaction KotlinTurnAdmission.Rejected("Model not found")
        if (!model.isEnabled) return@transaction KotlinTurnAdmission.Rejected("Model is disabled")
        val provider = rows.provider(model.providerId) ?: return@transaction KotlinTurnAdmission.Rejected("Provider not found")
        val cloudIdentity = if (provider.id == BUILTIN_PROVIDER_ID) {
            if (cloudGate == null || rows.getValue(DEFERRED_BUILTIN_PROVIDER_CLEANUP) != null || provider.apiKey.isBlank())
                return@transaction KotlinTurnAdmission.Rejected("Cloud provider is unavailable")
            val session = db.cloudSync().currentSession() ?: return@transaction KotlinTurnAdmission.Rejected("Cloud session is unavailable")
            val host = rows.getValue("cloud_session_host")?.value ?: return@transaction KotlinTurnAdmission.Rejected("Cloud session host is unavailable")
            if (runCatching { canonicalCloudBase(host) + "/v1" }.getOrNull() != provider.baseUrl)
                return@transaction KotlinTurnAdmission.Rejected("Cloud provider does not match the active session")
            KotlinCloudTurnIdentity(session.accountId, session.generation)
        } else null
        // Capture title settings at admission; credentials themselves remain in the provider row.
        val titleAgent = rows.titleAgent()
        val titleModel = titleAgent?.defaultModelId?.let { rows.model(it) }
        val titleProvider = titleModel?.let { rows.provider(it.providerId) }
        val titleIdentity = if (titleProvider?.id == BUILTIN_PROVIDER_ID) {
            val session = db.cloudSync().currentSession()
            val host = rows.getValue("cloud_session_host")?.value
            if (cloudGate != null && session != null && host != null &&
                rows.getValue(DEFERRED_BUILTIN_PROVIDER_CLEANUP) == null &&
                runCatching { canonicalCloudBase(host) + "/v1" }.getOrNull() == titleProvider.baseUrl)
                KotlinCloudTurnIdentity(session.accountId, session.generation) else null
        } else null
        val titleConfig = if (titleAgent != null && titleModel != null && titleProvider != null &&
            (titleAgent.maxTokens == null || titleAgent.maxTokens in 0..Int.MAX_VALUE.toLong())) {
            StoredTitle(titleProvider.id, titleModel.modelId, titleAgent.systemPrompt, titleAgent.temperature?.toFloat(),
                titleAgent.topP?.toFloat(), titleAgent.maxTokens?.toInt(), titleIdentity)
        } else null
        val history = rows.messagesFor(listOf(conversation.id)).toMutableList()
        fun approvalText(content: String, partsJson: String?): String {
            val text = partsJson?.let { runCatching { ContentPartCodec.decode(it) }.getOrNull() }.orEmpty()
                .filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
            return text.ifEmpty { content }
        }
        val approvalUserRequest = when (command) {
            is KotlinTurnCommand.Send -> approvalText(command.content, command.partsJson)
            is KotlinTurnCommand.Retry, is KotlinTurnCommand.Regenerate -> {
                val messageId = when (command) { is KotlinTurnCommand.Retry -> command.messageId; is KotlinTurnCommand.Regenerate -> command.messageId; else -> error("unreachable") }
                val position = history.indexOfFirst { it.id == messageId }.let { if (it < 0) history.size else it }
                history.take(position).lastOrNull { it.role == "user" }?.let { approvalText(it.content, it.partsJson) }.orEmpty()
            }
        }
        val removed = mutableListOf<String>()
        val now = rows.nextMessageTimestamp(conversation.id)
        when (command) {
            is KotlinTurnCommand.Send -> {
                if (command.content.isBlank() && command.partsJson.isNullOrBlank()) return@transaction KotlinTurnAdmission.Rejected("Message is empty")
                history.add(MessageRow(randomUuid(), conversation.id, "user", command.content, command.partsJson, now, "sent", null))
            }
            is KotlinTurnCommand.Retry -> {
                val target = history.firstOrNull { it.id == command.messageId }
                    ?: return@transaction KotlinTurnAdmission.Rejected("Message not found")
                if (!target.role.equals("assistant", ignoreCase = true) || !target.status.equals("error", ignoreCase = true)) return@transaction KotlinTurnAdmission.Rejected("Message cannot be retried")
                removed.add(target.id); history.remove(target)
            }
            is KotlinTurnCommand.Regenerate -> {
                val group = command.messageGroup
                if (group != null && (group.isEmpty() || command.messageId !in group || group.distinct().size != group.size)) return@transaction KotlinTurnAdmission.Rejected("Invalid message group")
                val start = history.indexOfFirst { it.id in (group ?: listOf(command.messageId)) }
                if (start < 0 || history[start].role != "assistant") return@transaction KotlinTurnAdmission.Rejected("Message cannot be regenerated")
                val turnRows = history.drop(start).takeWhile { it.role != "user" }.filter { it.role == "assistant" || it.role == "tool" }
                if (group != null && turnRows.map { it.id }.toSet() != group.toSet()) return@transaction KotlinTurnAdmission.Rejected("Message group changed")
                for (message in history.drop(start)) {
                    if (message.id != command.messageId && message.role == "user") break
                    if (message.role == "assistant" || message.role == "tool") removed.add(message.id)
                }
                history.removeAll { it.id in removed }
            }
        }
        val request = try { prepare.prepare(conversation, agent, defaultAgent, model, provider, history.toList(), command.disableTools) }
            catch (error: IllegalArgumentException) { return@transaction KotlinTurnAdmission.Rejected(error.message ?: "Unsupported request") }
        if (command.disableTools && !request.tools.isNullOrEmpty()) return@transaction KotlinTurnAdmission.Rejected("Tools must be disabled for this client")
        require(request.model == model.modelId) { "Prepared request selected a different model" }
        val names = request.tools.orEmpty().map { it.function.name }
        if (names.distinct().size != names.size) return@transaction KotlinTurnAdmission.Rejected("Duplicate tool declaration")
        val coreNames = names.filter { it.startsWith("messenger_") }
        val mcpGrant = prepare.mcpToolGrant()
        val mcpNames = mcpGrant?.tools?.map { it.name }.orEmpty()
        val runtimeNames = names - coreNames.toSet() - mcpNames.toSet()
        if (command.scheduledRun && coreNames.any { it == "messenger_schedule_task" || it == "messenger_agents" }) {
            return@transaction KotlinTurnAdmission.Rejected("Scheduled turns cannot schedule or delegate")
        }
        if (command.delegatedRun && coreNames.any { it == "messenger_agents" || it == "messenger_schedule_task" }) return@transaction KotlinTurnAdmission.Rejected("Delegated turns cannot schedule or delegate again")
        val grant = if (runtimeNames.isEmpty()) null else prepare.toolGrant()
            ?: return@transaction KotlinTurnAdmission.Rejected("Runtime tool scope is unavailable")
        val coreGrant = if (coreNames.isEmpty()) null else prepare.coreToolGrant()
            ?: return@transaction KotlinTurnAdmission.Rejected("Core tool scope is unavailable")
        if (mcpGrant != null) {
            val bindings = mcpGrant.tools
            if (command.scheduledRun || command.delegatedRun || mcpGrant.conversationId != conversation.id ||
                bindings.map { it.name }.distinct().size != bindings.size || bindings.any {
                    it.name.isBlank() || it.name.startsWith("messenger_") || it.serverId.isBlank() || it.remoteName.isBlank()
                } || names.any { name -> name in mcpNames && request.tools.orEmpty().single { it.function.name == name }.function.let { spec ->
                    val binding = bindings.single { it.name == name }
                    spec.description != binding.description || spec.parameters != binding.parameters
                } }) return@transaction KotlinTurnAdmission.Rejected("MCP tool scope does not match this conversation")
        }
        if (grant != null) {
            if (grant.conversationId != conversation.id || grant.projectId != conversation.projectId || grant.allowedNames.any { it.startsWith("messenger_") || it in mcpNames } ||
                runtimeNames.any { it !in grant.allowedNames }) {
                return@transaction KotlinTurnAdmission.Rejected("Tool scope does not match this conversation")
            }
            if (grant.projectId != null) {
                val project = rows.project(grant.projectId) ?: return@transaction KotlinTurnAdmission.Rejected("Project workspace is unavailable")
                if (project.workspace != grant.workspaceRoot || project.deviceId != grant.deviceId) return@transaction KotlinTurnAdmission.Rejected("Project workspace changed")
            }
        }
        if (coreGrant != null) {
            if (coreGrant.conversationId != conversation.id || coreGrant.allowedNames.distinct().size != coreGrant.allowedNames.size ||
                coreGrant.allowedNames.any { it !in KOTLIN_CORE_TOOLS } || coreNames.any { it !in coreGrant.allowedNames }) {
                return@transaction KotlinTurnAdmission.Rejected("Core tool scope does not match this conversation")
            }
            try { coreNames.forEach { rows.requireCoreToolAllowed(conversation.id, it) } }
            catch (error: IllegalStateException) { return@transaction KotlinTurnAdmission.Rejected(error.message ?: "Core tool is unavailable") }
            catch (error: IllegalArgumentException) { return@transaction KotlinTurnAdmission.Rejected(error.message ?: "Core tool is unavailable") }
        }
        try { prepare.validateAdmission(conversation, request) }
        catch (error: IllegalStateException) { return@transaction KotlinTurnAdmission.Rejected(error.message ?: "Tool catalog changed") }
        catch (error: IllegalArgumentException) { return@transaction KotlinTurnAdmission.Rejected(error.message ?: "Tool catalog changed") }
        val systemPrompt = prepare.effectiveSystemPrompt(agent, defaultAgent, request)
        val summaryPlan = if (prepare.supportsContextCompaction()) KotlinChatContext.plan(conversation, history, systemPrompt, command.summarizePrompt, model.contextWindow) else null
        if (delegationParent != null) rows.write(delegationParent.copy(delegation = checkNotNull(delegationParent.delegation).copy(admitted = true)))
        removed.forEach { rows.deleteMessage(it) }
        if (command is KotlinTurnCommand.Send) {
            val userMessage = history.last()
            rows.upsertMessages(listOf(userMessage))
            rows.updateLastMessage(conversation.id, userMessage.content, userMessage.timestamp)
        }
        val id = randomUuid()
        val assistant = MessageRow(randomUuid(), conversation.id, "assistant", "", null, rows.nextMessageTimestamp(conversation.id), "sending", null)
        rows.upsertMessages(listOf(assistant))
        rows.write(StoredTurn(id, conversation.id, provider.id, assistant.id,
            if (summaryPlan == null) KotlinTurnPhase.READY_PROVIDER.name else KotlinTurnPhase.SUMMARY_PENDING.name,
            request, toolGrant = grant, cloudIdentity = cloudIdentity,
            userMessageId = if (command is KotlinTurnCommand.Send) history.last().id else null, title = titleConfig, summaryPlan = summaryPlan,
            remoteScope = command.remoteScope, coreGrant = coreGrant, mcpGrant = mcpGrant,
            mcpOwner = if (mcpGrant != null) owner else null, scheduledRun = command.scheduledRun, scheduledClaim = scheduledClaim, delegatedRun = command.delegatedRun,
            supportsApproval = command.supportsToolApproval, approvalUserRequest = approvalUserRequest))
        rows.upsertValues(listOf(KeyValueRow(ACTIVE + conversation.id, id)))
        rows.upsertValues(listOf(KeyValueRow(LATEST + conversation.id, id)))
        mark(conversation.id)
        KotlinTurnAdmission.Accepted(id)
    }

    override suspend fun claim(turnId: String, recovery: Boolean): Boolean = transaction { rows, _ ->
        val turn = rows.read(turnId)
        if (turn.phase() in terminal || (turn.owner != null && turn.owner != owner && !recovery)) return@transaction false
        rows.write(turn.copy(owner = owner)); true
    }
    override suspend fun release(turnId: String) { transaction { rows, mark ->
        val turn = rows.read(turnId)
        if (turn.owner == owner) {
            if (turn.phase() == KotlinTurnPhase.CANCELLING_TOOL) {
                val assistant = checkNotNull(rows.message(turn.assistantId))
                rows.upsertMessages(listOf(assistant.copy(status = "sent")))
                rows.write(turn.copy(phase = KotlinTurnPhase.CANCELLED.name, owner = null))
                rows.deleteValue(ACTIVE + turn.conversationId)
                rows.refreshConversationPreview(turn.conversationId)
                mark(turn.conversationId)
            } else rows.write(turn.copy(owner = null))
        }
    } }
    override suspend fun snapshot(turnId: String): KotlinTurnSnapshot = transaction { rows, _ -> rows.snapshot(rows.read(turnId)) }
    override fun providerStream(turn: KotlinTurnSnapshot, provider: KotlinTurnProvider): Flow<cc.ptoe.messenger.data.remote.sse.ChatStreamEvent> =
        if (turn.provider.id != BUILTIN_PROVIDER_ID) provider.stream(turn.provider, turn.request)
        else checkNotNull(cloudGate) { "Cloud provider dispatch gate is unavailable" }.stream(turn)
    override fun observe(turnId: String): Flow<KotlinTurnSnapshot> = db.rows().observeValue(TURN + turnId).map {
        val row = it ?: error("Turn not found")
        val stored = json.decodeFromString<StoredTurn>(row.value)
        db.rows().snapshot(stored)
    }

    /** The last accepted turn survives completion so a detached observer can receive its terminal event. */
    suspend fun latestTurn(conversationId: String): String? =
        db.rows().getValue(LATEST + conversationId)?.value ?: db.rows().getValue(ACTIVE + conversationId)?.value

    suspend fun activeTurn(conversationId: String): String? {
        val id = db.rows().getValue(ACTIVE + conversationId)?.value ?: return null
        return id.takeUnless { db.rows().read(it).phase() in terminal }
    }

    /** Includes interrupted calls, whose durable reservation must not be displaced. */
    internal suspend fun reservedTurn(conversationId: String): String? = db.rows().getValue(ACTIVE + conversationId)?.value

    /** Observes both the state row and the message table, which changes on every streamed chunk. */
    fun observePresentation(turnId: String, conversationId: String): Flow<KotlinTurnPresentation> =
        combine(db.rows().observeValue(TURN + turnId), db.rows().observeMessages(conversationId)) { _, _ ->
            db.useReaderConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.DEFERRED) {
                val turn = db.rows().read(turnId)
                require(turn.conversationId == conversationId) { "Turn belongs to another conversation" }
                val assistant = checkNotNull(db.rows().message(turn.assistantId)) { "Turn message is missing" }
                KotlinTurnPresentation(turn.id, turn.conversationId, turn.phase(), turn.assistantId,
                    assistant.content, turn.detail, turn.calls.getOrNull(turn.index)?.domain(), turn.userMessageId, turn.titleFailure, turn.summaryFailure, turn.approvalFailure,
                    turn.titleFailureHasDurableNotice)
            } }
        }
    private suspend fun LocalRowsDao.requireScheduledClaim(turn: StoredTurn) {
        if (!turn.scheduledRun) return
        val claim = turn.scheduledClaim ?: throw cc.ptoe.messenger.data.local.schedule.ScheduledTurnUnavailable("Scheduled turn claim is unavailable")
        val conversation = checkNotNull(conversation(turn.conversationId)) { "Scheduled conversation is unavailable" }
        val prompt = checkNotNull(turn.userMessageId?.let { message(it) }) { "Scheduled prompt is unavailable" }
        check(prompt.conversationId == conversation.id && prompt.role == "user") { "Scheduled prompt changed" }
        cc.ptoe.messenger.data.local.schedule.requireScheduledTurnAdmission(this, claim, conversation, prompt.content, matchDefinition = false)
    }

    override suspend fun beginSummary(turnId: String): KotlinSummaryWork? = transaction { rows, _ ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.SUMMARY_PENDING)
        rows.requireScheduledClaim(turn)
        turn.remoteScope?.let { rows.requireRemoteControl(it, turn.conversationId, requireOwnedProject = true) }
        val plan = checkNotNull(turn.summaryPlan) { "Summary plan is missing" }
        val original = rows.snapshot(turn)
        val snapshot = original.copy(provider = original.provider.copy(nativeWebSearch = false))
        val request = ChatCompletionRequestDto(model = turn.request.model, messages = listOf(
            ChatMessageDto(role = "system", content = JsonPrimitive(plan.summaryPrompt)),
            ChatMessageDto(role = "user", content = JsonPrimitive(plan.transcript))),
            temperature = 0.3f, topP = 1f, tools = null, stream = false)
        rows.write(turn.copy(phase = KotlinTurnPhase.SUMMARY_REQUESTING.name))
        KotlinSummaryWork(snapshot.copy(phase = KotlinTurnPhase.SUMMARY_REQUESTING), request)
    }

    override fun summaryStream(work: KotlinSummaryWork, provider: KotlinTurnProvider): Flow<cc.ptoe.messenger.data.remote.sse.ChatStreamEvent> =
        if (work.turn.provider.id != BUILTIN_PROVIDER_ID) provider.complete(work.turn.provider, work.request)
        else checkNotNull(cloudGate) { "Cloud provider dispatch gate is unavailable" }.complete(work.turn.copy(request = work.request))

    private suspend fun LocalRowsDao.finishSummary(turn: StoredTurn, result: KotlinSummaryResult, mark: suspend (String) -> Unit) {
        val plan = checkNotNull(turn.summaryPlan) { "Summary plan is missing" }
        val latest = checkNotNull(conversation(turn.conversationId)) { "Conversation not found" }
        var failure = result.failure
        var request = turn.request
        if (failure == null && !result.summary.isNullOrBlank()) {
            if (latest.contextSummary != plan.previousSummary || latest.contextSummaryUntil != plan.previousCutoff) {
                failure = "Conversation context changed during summarization"
            } else {
                val updated = latest.copy(contextSummary = result.summary, contextSummaryUntil = plan.cutoff,
                    contextTokens = KotlinChatContext.estimateText(plan.systemPrompt) + KotlinChatContext.estimateText(result.summary) + plan.keptTokens,
                    contextTokensAt = clock())
                try {
                    request = request.copy(messages = prepare.rebuildContext(updated, messagesFor(listOf(turn.conversationId)), plan.systemPrompt))
                    upsertConversations(listOf(updated))
                } catch (error: IllegalArgumentException) {
                    failure = error.message ?: "Could not prepare summarized context"
                }
            }
        }
        if (failure != null) {
            val detail = "Context summarization failed: $failure"
            val assistant = checkNotNull(message(turn.assistantId))
            upsertMessages(listOf(MessageRow(randomUuid(), turn.conversationId, "assistant", detail, null,
                nextMessageTimestamp(turn.conversationId), "error", detail)))
            upsertMessages(listOf(assistant.copy(timestamp = nextMessageTimestamp(turn.conversationId))))
            addTurnFailure(turn, detail)
            failure = detail
        }
        write(turn.copy(phase = KotlinTurnPhase.READY_PROVIDER.name, request = request, summaryFailure = failure))
        mark(turn.conversationId)
    }

    override suspend fun completeSummary(turnId: String, result: KotlinSummaryResult) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.SUMMARY_REQUESTING)
        rows.finishSummary(turn, result, mark)
    } }

    override suspend fun interruptedSummary(turnId: String) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.SUMMARY_REQUESTING)
        rows.finishSummary(turn, KotlinSummaryResult(failure = "Summary request was interrupted"), mark)
    } }

    override suspend fun beginProvider(turnId: String): KotlinTurnSnapshot = transaction { rows, _ ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.READY_PROVIDER)
        rows.requireScheduledClaim(turn)
        turn.remoteScope?.let { rows.requireRemoteControl(it, turn.conversationId, requireOwnedProject = true) }
        if (turn.providerId == BUILTIN_PROVIDER_ID) {
            check(cloudGate != null && turn.cloudIdentity != null && rows.getValue(DEFERRED_BUILTIN_PROVIDER_CLEANUP) == null) { "Cloud session is unavailable" }
        }
        val next = turn.copy(phase = KotlinTurnPhase.REQUESTING_PROVIDER.name); rows.write(next); rows.snapshot(next)
    }
    override suspend fun appendContent(turnId: String, content: String) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.REQUESTING_PROVIDER)
        val assistant = checkNotNull(rows.message(turn.assistantId)); rows.upsertMessages(listOf(assistant.copy(content = assistant.content + content)))
        mark(turn.conversationId)
    } }
    override suspend fun setReasoningFormat(turnId: String, format: String) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.REQUESTING_PROVIDER)
        val conversation = checkNotNull(rows.conversation(turn.conversationId)); rows.upsertConversations(listOf(conversation.copy(reasoningFormat = format))); mark(turn.conversationId)
    } }

    override suspend fun completeProvider(turnId: String, calls: List<ToolCallData>, usage: UsageDto?) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.REQUESTING_PROVIDER)
        rows.requireTurnCloudSession(turn)
        val advertised = turn.request.tools.orEmpty().map { it.function.name }.toSet()
        require(calls.all { it.callId.isNotBlank() && it.name.isNotBlank() && it.name in advertised } && calls.map { it.callId }.distinct().size == calls.size) { "Provider requested an undeclared or invalid tool" }
        val assistant = checkNotNull(rows.message(turn.assistantId))
        if (usage != null) {
            val conversation = checkNotNull(rows.conversation(turn.conversationId))
            val total = if (usage.totalTokens > 0) usage.totalTokens else usage.promptTokens + usage.completionTokens
            rows.upsertConversations(listOf(conversation.copy(contextTokens = total.toLong(), contextTokensAt = currentTimeMillis())))
        }
        if (calls.isEmpty()) {
            rows.upsertMessages(listOf(assistant.copy(status = "sent")))
            val conversation = checkNotNull(rows.conversation(turn.conversationId))
            val needsTitle = turn.title != null && assistant.content.isNotBlank() && KotlinConversationTitle.isUntitled(conversation.title) &&
                rows.messagesFor(listOf(turn.conversationId)).any { it.role == "user" && it.status == "sent" && it.content.isNotBlank() }
            rows.write(turn.copy(phase = if (needsTitle) KotlinTurnPhase.TITLE_PENDING.name else KotlinTurnPhase.FINISHED.name))
            if (!needsTitle) {
                rows.deleteValue(ACTIVE + turn.conversationId)
                rows.refreshConversationPreview(turn.conversationId)
            }
        } else {
            val parts = buildList {
                if (assistant.content.isNotEmpty()) add(ContentPart.Text(assistant.content))
                calls.forEach { add(ContentPart.ToolCall(it.callId, it.name, it.arguments)) }
            }
            rows.upsertMessages(listOf(assistant.copy(partsJson = ContentPartCodec.encode(parts), status = "sent")))
            val message = ChatMessageDto(role = "assistant", content = JsonPrimitive(assistant.content), toolCalls = calls.map {
                ToolCallDto(id = it.callId, type = "function", function = ToolCallFunctionDto(it.name, it.arguments))
            })
            rows.write(turn.copy(phase = rows.approvalPhase(turn, calls.first()).name,
                request = turn.request.copy(messages = turn.request.messages + message),
                calls = calls.map { StoredTool(it.callId, it.name, it.arguments) }, index = 0))
        }
        mark(turn.conversationId)
    } }

    private suspend fun LocalRowsDao.finishTitle(turn: StoredTurn, result: KotlinTitleResult?, mark: suspend (String) -> Unit,
        durableNotice: Boolean = false) {
        val conversation = conversation(turn.conversationId)
        if (conversation != null && result != null && result.title.isNotBlank() && KotlinConversationTitle.isUntitled(conversation.title)) {
            upsertConversations(listOf(conversation.copy(title = result.title)))
        }
        write(turn.copy(phase = KotlinTurnPhase.FINISHED.name, titleFailure = result?.failure, titleFailureHasDurableNotice = durableNotice))
        deleteValue(ACTIVE + turn.conversationId)
        refreshConversationPreview(turn.conversationId)
        mark(turn.conversationId)
    }

    override suspend fun beginTitle(turnId: String): KotlinTitleWork? = transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.TITLE_PENDING)
        try { rows.requireScheduledClaim(turn) }
        catch (_: cc.ptoe.messenger.data.local.schedule.ScheduledTurnUnavailable) {
            // A completed assistant answer stays sent when cancellation makes title work obsolete.
            rows.finishTitle(turn, null, mark)
            return@transaction null
        }
        turn.remoteScope?.let { rows.requireRemoteControl(it, turn.conversationId, requireOwnedProject = true) }
        val conversation = rows.conversation(turn.conversationId)
        val messages = rows.messagesFor(listOf(turn.conversationId)).filter { it.status == "sent" }
        val firstUser = messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }?.content
        val firstAssistant = messages.firstOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content
        val title = turn.title
        if (conversation == null || !KotlinConversationTitle.isUntitled(conversation.title) || firstUser == null || firstAssistant == null || title == null) {
            rows.finishTitle(turn, null, mark)
            return@transaction null
        }
        val provider = rows.provider(title.providerId)
        if (provider == null) {
            rows.finishTitle(turn, KotlinTitleResult(KotlinConversationTitle.sanitize(firstUser), "Title provider is unavailable"), mark)
            return@transaction null
        }
        val selected = Provider(provider.id, provider.name, provider.baseUrl, provider.apiKey,
            ApiFormat.fromWireName(provider.apiFormat), false, provider.createdAt, provider.updatedAt)
        rows.write(turn.copy(phase = KotlinTurnPhase.TITLE_REQUESTING.name))
        KotlinTitleWork(turn.id, turn.conversationId, KotlinTitleInput(title.model, title.prompt, firstUser, firstAssistant,
            title.temperature, title.topP, title.maxTokens), selected, title.cloudIdentity)
    }

    override fun titleStream(work: KotlinTitleWork, request: ChatCompletionRequestDto, provider: KotlinTurnProvider): Flow<cc.ptoe.messenger.data.remote.sse.ChatStreamEvent> =
        if (work.provider.id != BUILTIN_PROVIDER_ID) provider.complete(work.provider, request)
        else checkNotNull(cloudGate) { "Cloud provider dispatch gate is unavailable" }.complete(
            KotlinTurnSnapshot(work.turnId, work.conversationId, KotlinTurnPhase.TITLE_REQUESTING, work.provider, request, cloudIdentity = work.cloudIdentity))

    override suspend fun completeTitle(turnId: String, result: KotlinTitleResult) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.TITLE_REQUESTING)
        rows.finishTitle(turn, result, mark)
    } }

    override suspend fun interruptedTitle(turnId: String) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.TITLE_REQUESTING)
        val firstUser = rows.messagesFor(listOf(turn.conversationId)).firstOrNull { it.role == "user" && it.status == "sent" }?.content.orEmpty()
        rows.finishTitle(turn, KotlinTitleResult(KotlinConversationTitle.sanitize(firstUser), "Title request was interrupted"), mark)
    } }

    private suspend fun LocalRowsDao.applyApproval(turn: StoredTurn, callId: String, allow: Boolean, mark: suspend (String) -> Unit) {
        turn.requirePhase(KotlinTurnPhase.WAITING_APPROVAL)
        val call = turn.calls.getOrNull(turn.index)?.domain() ?: error("No pending tool")
        require(call.callId == callId) { "Approval does not match pending tool" }
        if (allow) write(turn.copy(phase = KotlinTurnPhase.READY_TOOL.name))
        else completeToolResult(turn, call, ToolExecutionResult("User denied tool execution", isError = true))
        mark(turn.conversationId)
    }

    override suspend fun decideApproval(turnId: String, callId: String, allow: Boolean) { transaction { rows, mark ->
        rows.applyApproval(rows.read(turnId), callId, allow, mark)
    } }

    override suspend fun expireApproval(turnId: String, callId: String) { transaction { rows, mark ->
        val turn = rows.read(turnId)
        val call = turn.calls.getOrNull(turn.index)?.domain()
        if (turn.phase() == KotlinTurnPhase.WAITING_APPROVAL && call?.callId == callId) {
            rows.completeToolResult(turn, call, ToolExecutionResult("Tool approval denied or timed out", isError = true))
            mark(turn.conversationId)
        }
    } }

    override suspend fun rejectUnavailableApproval(turnId: String) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.APPROVAL_UNAVAILABLE)
        val call = turn.calls.getOrNull(turn.index)?.domain() ?: error("No pending tool")
        rows.completeToolResult(turn, call, ToolExecutionResult("Tool requires manual approval, but this client cannot request it", isError = true))
        mark(turn.conversationId)
    } }

    override suspend fun beginAutomaticApproval(turnId: String): KotlinApprovalWork? = transaction { rows, _ ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.AUTO_APPROVAL_PENDING)
        rows.requireScheduledClaim(turn)
        turn.remoteScope?.let { rows.requireRemoteControl(it, turn.conversationId, requireOwnedProject = true) }
        val call = turn.calls.getOrNull(turn.index)?.domain() ?: error("No pending tool")
        val phase = rows.approvalPhase(turn, call)
        if (phase != KotlinTurnPhase.AUTO_APPROVAL_PENDING) {
            rows.write(turn.copy(phase = phase.name))
            return@transaction null
        }
        val original = rows.snapshot(turn)
        rows.write(turn.copy(phase = KotlinTurnPhase.AUTO_APPROVAL_REQUESTING.name,
            approvalSettingsRevision = rows.getValue(APPROVAL_SETTINGS_REVISION)?.value))
        KotlinApprovalWork(original.copy(phase = KotlinTurnPhase.AUTO_APPROVAL_REQUESTING,
            provider = original.provider.copy(nativeWebSearch = false)), call, turn.approvalUserRequest.orEmpty())
    }

    override fun automaticApprovalStream(work: KotlinApprovalWork, request: ChatCompletionRequestDto, provider: KotlinTurnProvider): Flow<cc.ptoe.messenger.data.remote.sse.ChatStreamEvent> =
        if (work.turn.provider.id != BUILTIN_PROVIDER_ID) provider.complete(work.turn.provider, request)
        else checkNotNull(cloudGate) { "Cloud provider dispatch gate is unavailable" }.complete(work.turn.copy(request = request))

    private suspend fun LocalRowsDao.finishAutomaticApproval(turn: StoredTurn, call: ToolCallData, result: KotlinApprovalResult, mark: suspend (String) -> Unit) {
        var next = turn
        if (result.failure != null) {
            val detail = "Automatic approval failed: ${result.failure}"
            upsertMessages(listOf(MessageRow(randomUuid(), turn.conversationId, "assistant", detail, null,
                nextMessageTimestamp(turn.conversationId), "error", detail)))
            addTurnFailure(turn, detail)
            next = turn.copy(approvalFailure = detail)
        }
        completeToolResult(next, call, ToolExecutionResult(result.failure?.let { "Automatic approval failed: $it" }
            ?: "Automatic approval agent denied this tool call", isError = true))
        mark(turn.conversationId)
    }

    override suspend fun completeAutomaticApproval(turnId: String, callId: String, result: KotlinApprovalResult) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.AUTO_APPROVAL_REQUESTING)
        rows.requireTurnCloudSession(turn)
        turn.remoteScope?.let { rows.requireRemoteControl(it, turn.conversationId, requireOwnedProject = true) }
        val call = turn.calls.getOrNull(turn.index)?.domain() ?: error("No pending tool")
        require(call.callId == callId) { "Approval does not match pending tool" }
        val phase = rows.approvalPhase(turn, call)
        if (phase != KotlinTurnPhase.AUTO_APPROVAL_PENDING) rows.write(turn.copy(phase = phase.name))
        else if (turn.approvalSettingsRevision != rows.getValue(APPROVAL_SETTINGS_REVISION)?.value)
            rows.finishAutomaticApproval(turn, call, KotlinApprovalResult(false, "Approval settings changed during review"), mark)
        else if (result.approved && result.failure == null) rows.write(turn.copy(phase = KotlinTurnPhase.READY_TOOL.name))
        else rows.finishAutomaticApproval(turn, call, result, mark)
    } }

    override suspend fun interruptedAutomaticApproval(turnId: String) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.AUTO_APPROVAL_REQUESTING)
        val call = turn.calls.getOrNull(turn.index)?.domain() ?: error("No pending tool")
        rows.finishAutomaticApproval(turn, call, KotlinApprovalResult(false, "Approval request was interrupted"), mark)
    } }

    override suspend fun decideRemoteApproval(scope: KotlinRemoteControlScope, conversationId: String, turnId: String, callId: String, allow: Boolean) { transaction { rows, mark ->
        rows.requireRemoteControl(scope, conversationId, requireOwnedProject = false)
        check(rows.getValue(ACTIVE + conversationId)?.value == turnId) { "Turn is no longer active" }
        val turn = rows.read(turnId)
        check(turn.conversationId == conversationId && (turn.remoteScope == null || turn.remoteScope == scope)) { "Turn does not match the remote session" }
        rows.applyApproval(turn, callId, allow, mark)
    } }
    /** Relay decisions must retain the exact cookie, live session and local turn owner. */
    internal suspend fun decideRemoteApprovalAuthenticated(scope: KotlinRemoteControlScope, cookie: String,
        conversationId: String, turnId: String, callId: String, allow: Boolean) { transaction { rows, mark ->
        rows.requireRemoteControl(scope, conversationId, requireOwnedProject = false)
        check(rows.getValue("cloud_session")?.value == cookie) { "Cloud session changed" }
        checkNotNull(cloudGate) { "Cloud provider dispatch gate is unavailable" }.requireValid(SyncSession(scope.accountId, scope.generation))
        check(rows.getValue(ACTIVE + conversationId)?.value == turnId) { "Turn is no longer active" }
        val turn = rows.read(turnId)
        turn.requireOwner()
        check(turn.conversationId == conversationId && (turn.remoteScope == null || turn.remoteScope == scope)) { "Turn does not match the remote session" }
        rows.applyApproval(turn, callId, allow, mark)
    } }
    override suspend fun beginTool(turnId: String): ToolCallData = transaction { rows, _ ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.READY_TOOL)
        rows.requireScheduledClaim(turn)
        rows.requireTurnCloudSession(turn)
        turn.remoteScope?.let { rows.requireRemoteControl(it, turn.conversationId, requireOwnedProject = true) }
        val call = turn.calls.getOrNull(turn.index)?.domain() ?: error("No pending tool")
        if (call.name.startsWith("messenger_")) {
            check(turn.coreGrant?.let { it.conversationId == turn.conversationId && call.name in it.allowedNames } == true) { "Core tool was not granted to this turn" }
            rows.requireCoreToolAllowed(turn.conversationId, call.name)
        }
        val conversation = checkNotNull(rows.conversation(turn.conversationId))
        check(policy.permits(turnId, conversation, call)) { "Tool permission is no longer available" }
        rows.write(turn.copy(phase = KotlinTurnPhase.EXECUTING_TOOL.name, invocationStarted = false))
        call
    }
    override suspend fun completeTool(turnId: String, call: ToolCallData, result: ToolExecutionResult) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner()
        if (turn.phase() == KotlinTurnPhase.CANCELLING_TOOL) {
            val assistant = checkNotNull(rows.message(turn.assistantId))
            rows.upsertMessages(listOf(assistant.copy(status = "sent")))
            rows.write(turn.copy(phase = KotlinTurnPhase.CANCELLED.name))
            rows.deleteValue(ACTIVE + turn.conversationId)
            rows.refreshConversationPreview(turn.conversationId)
            mark(turn.conversationId)
            return@transaction
        }
        turn.requirePhase(KotlinTurnPhase.EXECUTING_TOOL)
        check(turn.invocationStarted) { "Tool invocation was not claimed" }
        check(turn.calls.getOrNull(turn.index)?.domain() == call) { "Tool result does not match the pending call" }
        rows.completeToolResult(turn, call, result)
        mark(turn.conversationId)
    } }

    override suspend fun completeMcpTool(turnId: String, call: ToolCallData, result: ToolExecutionResult): Boolean = transaction { rows, mark ->
        val turn = rows.read(turnId)
        if (turn.mcpGrant?.tools?.none { it.name == call.name } != false) return@transaction false
        turn.requireOwner()
        check(turn.mcpOwner == owner && turn.invocationStarted &&
            turn.phase() in setOf(KotlinTurnPhase.EXECUTING_TOOL, KotlinTurnPhase.CANCELLING_TOOL) &&
            turn.calls.getOrNull(turn.index)?.domain() == call && rows.getValue(ACTIVE + turn.conversationId)?.value == turnId) {
            "MCP result does not match the claimed invocation"
        }
        if (turn.phase() == KotlinTurnPhase.CANCELLING_TOOL) {
            val assistant = checkNotNull(rows.message(turn.assistantId))
            val part = ContentPart.ToolResult(call.callId, call.name, result.output, result.isError, result.structuredContent)
            rows.upsertMessages(listOf(assistant.copy(status = "sent"), MessageRow(randomUuid(), turn.conversationId, "tool", result.output,
                ContentPartCodec.encode(listOf(part)), rows.nextMessageTimestamp(turn.conversationId), "sent", null)))
            rows.write(turn.copy(phase = KotlinTurnPhase.CANCELLED.name))
            rows.deleteValue(ACTIVE + turn.conversationId)
            rows.refreshConversationPreview(turn.conversationId)
        } else rows.completeToolResult(turn, call, result)
        mark(turn.conversationId)
        true
    }
    override suspend fun rejectUndispatchedRuntimeTool(turnId: String, call: ToolCallData, detail: String) { transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner()
        check(turn.phase() in setOf(KotlinTurnPhase.EXECUTING_TOOL, KotlinTurnPhase.CANCELLING_TOOL) &&
            rows.getValue(ACTIVE + turn.conversationId)?.value == turnId && !turn.invocationStarted &&
            turn.toolGrant?.let { it.conversationId == turn.conversationId && call.name in it.allowedNames } == true &&
            turn.calls.getOrNull(turn.index)?.domain() == call) { "Runtime invocation may already have started" }
        val cancelled = turn.phase() == KotlinTurnPhase.CANCELLING_TOOL
        val assistant = checkNotNull(rows.message(turn.assistantId))
        rows.upsertMessages(listOf(assistant.copy(status = if (cancelled) "sent" else "error", errorMessage = if (cancelled) null else detail)))
        rows.write(turn.copy(phase = if (cancelled) KotlinTurnPhase.CANCELLED.name else KotlinTurnPhase.FAILED.name, detail = if (cancelled) null else detail))
        if (!cancelled) rows.addTurnFailure(turn, detail)
        rows.deleteValue(ACTIVE + turn.conversationId)
        rows.refreshConversationPreview(turn.conversationId)
        mark(turn.conversationId)
    } }
    override suspend fun rejectUndispatchedCoreTool(turnId: String, call: ToolCallData, detail: String): Boolean = transaction { rows, mark ->
        val turn = rows.read(turnId)
        if (turn.owner != owner || turn.phase() !in setOf(KotlinTurnPhase.EXECUTING_TOOL, KotlinTurnPhase.CANCELLING_TOOL) ||
            rows.getValue(ACTIVE + turn.conversationId)?.value != turnId || turn.invocationStarted ||
            turn.coreGrant?.let { it.conversationId == turn.conversationId && call.name in it.allowedNames } != true ||
            turn.calls.getOrNull(turn.index)?.domain() != call || !call.name.startsWith("messenger_")) return@transaction false
        val cancelled = turn.phase() == KotlinTurnPhase.CANCELLING_TOOL
        val assistant = checkNotNull(rows.message(turn.assistantId))
        check(assistant.conversationId == turn.conversationId && assistant.role.equals("assistant", ignoreCase = true)) { "Invalid turn message" }
        rows.upsertMessages(listOf(assistant.copy(status = if (cancelled) "sent" else "error", errorMessage = if (cancelled) null else detail)))
        rows.write(turn.copy(phase = if (cancelled) KotlinTurnPhase.CANCELLED.name else KotlinTurnPhase.FAILED.name, detail = if (cancelled) null else detail))
        if (!cancelled) rows.addTurnFailure(turn, detail)
        rows.deleteValue(ACTIVE + turn.conversationId)
        rows.refreshConversationPreview(turn.conversationId)
        mark(turn.conversationId)
        true
    }

    internal suspend fun rejectUndispatchedMcpTool(receipt: McpInvocationReceipt, afterAdmission: Boolean, detail: String) {
        check(mcpInvocations[receipt.turnId] === receipt) { "MCP invocation receipt is not current" }
        if (afterAdmission) receipt.settleNotDispatched() else receipt.settleBeforeAdmission()
        val turnId = receipt.turnId
        val call = receipt.call
        transaction { rows, mark ->
        val turn = rows.read(turnId); turn.requireOwner()
        check(turn.mcpOwner == owner && turn.phase() in setOf(KotlinTurnPhase.EXECUTING_TOOL, KotlinTurnPhase.CANCELLING_TOOL) &&
            rows.getValue(ACTIVE + turn.conversationId)?.value == turnId &&
            turn.mcpGrant?.let { it.conversationId == turn.conversationId && it.tools.count { binding -> binding.name == call.name } == 1 } == true &&
            turn.calls.getOrNull(turn.index)?.domain() == call) { "MCP invocation no longer matches the turn" }
        val cancelled = turn.phase() == KotlinTurnPhase.CANCELLING_TOOL
        val assistant = checkNotNull(rows.message(turn.assistantId))
        check(assistant.conversationId == turn.conversationId && assistant.role.equals("assistant", ignoreCase = true)) { "Invalid turn message" }
        rows.upsertMessages(listOf(assistant.copy(status = if (cancelled) "sent" else "error", errorMessage = if (cancelled) null else detail)))
        rows.write(turn.copy(phase = if (cancelled) KotlinTurnPhase.CANCELLED.name else KotlinTurnPhase.FAILED.name, detail = if (cancelled) null else detail))
        if (!cancelled) rows.addTurnFailure(turn, detail)
        rows.deleteValue(ACTIVE + turn.conversationId)
        rows.refreshConversationPreview(turn.conversationId)
        mark(turn.conversationId)
        }
    }

    override suspend fun rejectUnclaimedMcpTool(turnId: String, call: ToolCallData, detail: String): Boolean = transaction { rows, mark ->
        val turn = rows.read(turnId)
        if (turn.owner != owner || turn.mcpOwner != owner || turn.phase() !in setOf(KotlinTurnPhase.EXECUTING_TOOL, KotlinTurnPhase.CANCELLING_TOOL) ||
            turn.invocationStarted || rows.getValue(ACTIVE + turn.conversationId)?.value != turnId ||
            turn.calls.getOrNull(turn.index)?.domain() != call ||
            turn.mcpGrant?.let { it.conversationId == turn.conversationId && it.tools.count { binding -> binding.name == call.name } == 1 } != true) return@transaction false
        val cancelled = turn.phase() == KotlinTurnPhase.CANCELLING_TOOL
        val assistant = checkNotNull(rows.message(turn.assistantId))
        check(assistant.conversationId == turn.conversationId && assistant.role.equals("assistant", ignoreCase = true)) { "Invalid turn message" }
        rows.upsertMessages(listOf(assistant.copy(status = if (cancelled) "sent" else "error", errorMessage = if (cancelled) null else detail)))
        rows.write(turn.copy(phase = if (cancelled) KotlinTurnPhase.CANCELLED.name else KotlinTurnPhase.FAILED.name, detail = if (cancelled) null else detail))
        if (!cancelled) rows.addTurnFailure(turn, detail)
        rows.deleteValue(ACTIVE + turn.conversationId)
        rows.refreshConversationPreview(turn.conversationId)
        mark(turn.conversationId)
        true
    }
    private suspend fun LocalRowsDao.completeToolResult(turn: StoredTurn, call: ToolCallData, result: ToolExecutionResult) {
        val now = nextMessageTimestamp(turn.conversationId)
        val part = ContentPart.ToolResult(call.callId, call.name, result.output, result.isError, result.structuredContent)
        upsertMessages(listOf(MessageRow(randomUuid(), turn.conversationId, "tool", result.output, ContentPartCodec.encode(listOf(part)), now, "sent", null)))
        val request = turn.request.copy(messages = turn.request.messages + ChatMessageDto(role = "tool", content = JsonPrimitive(result.output), toolCallId = call.callId))
        val nextIndex = turn.index + 1
        if (nextIndex < turn.calls.size) {
            write(turn.copy(request = request, index = nextIndex, phase = approvalPhase(turn, turn.calls[nextIndex].domain()).name))
        } else {
            val assistant = MessageRow(randomUuid(), turn.conversationId, "assistant", "", null, nextMessageTimestamp(turn.conversationId), "sending", null)
            upsertMessages(listOf(assistant))
            write(turn.copy(request = request, assistantId = assistant.id, calls = emptyList(), index = 0, phase = KotlinTurnPhase.READY_PROVIDER.name))
        }
    }

    private suspend fun LocalRowsDao.failTurn(turn: StoredTurn, detail: String, mark: suspend (String) -> Unit) {
        if (turn.phase() in terminal) return
        val assistant = checkNotNull(message(turn.assistantId))
        check(assistant.conversationId == turn.conversationId && assistant.role.equals("assistant", ignoreCase = true)) { "Invalid turn message" }
        upsertMessages(listOf(assistant.copy(status = "error", errorMessage = detail)))
        val interruptedTool = turn.phase() == KotlinTurnPhase.EXECUTING_TOOL || turn.phase() == KotlinTurnPhase.CANCELLING_TOOL
        write(turn.copy(phase = if (interruptedTool) KotlinTurnPhase.INTERRUPTED_TOOL.name else KotlinTurnPhase.FAILED.name, detail = detail))
        // Persisted separately for a snackbar observer; the assistant row is the bubble.
        addTurnFailure(turn, detail)
        if (!interruptedTool) deleteValue(ACTIVE + turn.conversationId)
        refreshConversationPreview(turn.conversationId)
        mark(turn.conversationId)
    }
    override suspend fun fail(turnId: String, detail: String) { transaction { rows, mark ->
        rows.failTurn(rows.read(turnId), detail, mark)
    } }

    /**
     * Run only before exposing this Room owner. No provider or tool operation is resumed.
     * An interrupted tool keeps its reservation until separate stop evidence permits recovery.
     */
    internal suspend fun reconcilePreviousChats(proof: PreviousChatWorkersStopped?) {
        proof?.verify()
        transaction { rows, mark ->
            val active = rows.activeTurnKeys()
            check(active.size <= 10_000) { "Too many interrupted chat turns" }
            check(rows.getValue(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
            for (entry in active) {
                val turn = rows.read(entry.value)
                check(entry.key == ACTIVE + turn.conversationId && entry.value == turn.id) { "Invalid active turn identity" }
                if (proof == null) check(turn.mcpExclusionKey()?.let { LocalMcpWorkerExclusion.provesStopped(db, it) } == true) {
                    "Previous chat workers require stop proof"
                }
                check(!turn.scheduledRun && !turn.delegatedRun) {
                    "Scheduled or delegated chat requires its owning service to reconcile before startup"
                }
                when (turn.phase()) {
                    KotlinTurnPhase.INTERRUPTED_TOOL -> Unit // Keep the existing evidence and reservation.
                    KotlinTurnPhase.FINISHED, KotlinTurnPhase.FAILED, KotlinTurnPhase.CANCELLED ->
                        error("Terminal turn still has an active reservation")
                    else -> {
                        if (turn.phase() == KotlinTurnPhase.TITLE_PENDING || turn.phase() == KotlinTurnPhase.TITLE_REQUESTING) {
                            val detail = "Title request was interrupted when the app stopped."
                            rows.finishTitle(turn, KotlinTitleResult("", detail), mark, durableNotice = true)
                            rows.addTurnFailure(turn, detail)
                        } else {
                            val detail = if (turn.phase() == KotlinTurnPhase.EXECUTING_TOOL || turn.phase() == KotlinTurnPhase.CANCELLING_TOOL)
                                "Chat was interrupted while a tool was running; its outcome is unknown and requires recovery."
                            else "Chat was interrupted when the app stopped; the previous request may have completed. Retry manually."
                            val unclaimedMcp = turn.mcpOwner != null && turn.mcpGrant != null && !turn.invocationStarted &&
                                turn.calls.getOrNull(turn.index)?.let { call -> turn.mcpGrant.tools.count { it.name == call.name } == 1 } == true
                            rows.failTurn(if (unclaimedMcp) turn.copy(phase = KotlinTurnPhase.READY_TOOL.name) else turn, detail, mark)
                        }
                    }
                }
            }
        }
    }
    private suspend fun LocalRowsDao.applyCancellation(turn: StoredTurn, mark: suspend (String) -> Unit) {
        if (turn.phase() in terminal) return
        if (turn.phase() == KotlinTurnPhase.EXECUTING_TOOL || turn.phase() == KotlinTurnPhase.CANCELLING_TOOL) {
            write(turn.copy(phase = KotlinTurnPhase.CANCELLING_TOOL.name))
        } else {
            val assistant = checkNotNull(message(turn.assistantId))
            upsertMessages(listOf(assistant.copy(status = "sent")))
            write(turn.copy(phase = KotlinTurnPhase.CANCELLED.name))
            deleteValue(ACTIVE + turn.conversationId)
            refreshConversationPreview(turn.conversationId)
            mark(turn.conversationId)
        }
    }

    override suspend fun cancel(turnId: String) {
        mcpInvocations[turnId]?.cancelBeforeAdmission()
        transaction { rows, mark ->
        mcpInvocations[turnId]?.cancelBeforeAdmission()
        rows.applyCancellation(rows.read(turnId), mark)
    } }

    override suspend fun cancelRemote(scope: KotlinRemoteControlScope, conversationId: String, turnId: String) { transaction { rows, mark ->
        rows.requireRemoteControl(scope, conversationId, requireOwnedProject = false)
        check(rows.getValue(ACTIVE + conversationId)?.value == turnId) { "Turn is no longer active" }
        val turn = rows.read(turnId)
        check(turn.conversationId == conversationId && turn.phase() !in terminal && (turn.remoteScope == null || turn.remoteScope == scope)) { "Turn does not match the remote session" }
        mcpInvocations[turnId]?.cancelBeforeAdmission()
        rows.applyCancellation(turn, mark)
    } }

    override suspend fun cancelRemoteAuthenticated(scope: KotlinRemoteControlScope, cookie: String, conversationId: String, turnId: String) { transaction { rows, mark ->
        rows.requireRemoteControl(scope, conversationId, requireOwnedProject = false)
        check(rows.getValue("cloud_session")?.value == cookie) { "Cloud session changed" }
        checkNotNull(cloudGate) { "Cloud provider dispatch gate is unavailable" }.requireValid(SyncSession(scope.accountId, scope.generation))
        check(rows.getValue(ACTIVE + conversationId)?.value == turnId) { "Turn is no longer active" }
        val turn = rows.read(turnId)
        turn.requireOwner()
        check(turn.conversationId == conversationId && turn.phase() !in terminal && (turn.remoteScope == null || turn.remoteScope == scope)) { "Turn does not match the remote session" }
        mcpInvocations[turnId]?.cancelBeforeAdmission()
        rows.applyCancellation(turn, mark)
    } }

    /** Only a compatible Runtime's durable STOPPED proof may release an ambiguous tool turn.
     * This does not claim the operation was rolled back; the user must inspect its outcome. */
    suspend fun reconcileInterruptedTool(turnId: String, host: TrackedWorkspaceHost): Boolean {
        val candidate = db.rows().read(turnId)
        if (candidate.phase() != KotlinTurnPhase.INTERRUPTED_TOOL) return false
        val call = candidate.calls.getOrNull(candidate.index)?.domain() ?: return false
        if (candidate.toolGrant == null || !host.protocolAvailable ||
            host.status(trackedToolToken(turnId, call.callId)) != TrackedWorkspaceStatus.STOPPED) return false
        return transaction { rows, _ ->
            val current = rows.read(turnId)
            if (current.phase() != KotlinTurnPhase.INTERRUPTED_TOOL || current.toolGrant != candidate.toolGrant ||
                current.calls.getOrNull(current.index)?.domain() != call) return@transaction false
            rows.write(current.copy(phase = KotlinTurnPhase.FAILED.name, owner = null))
            rows.deleteValue(ACTIVE + current.conversationId)
            true
        }
    }

    /** Tool executors must use the scope captured at admission, including after restart. */
    suspend fun toolGrant(turnId: String): KotlinRuntimeToolGrant? = db.rows().read(turnId).toolGrant
    suspend fun coreToolGrant(turnId: String): KotlinCoreToolGrant? = db.rows().read(turnId).coreGrant
    internal suspend fun mcpToolGrant(turnId: String): KotlinMcpToolGrant? = db.rows().read(turnId).let {
        it.mcpGrant.takeIf { _ -> it.mcpOwner == owner }
    }

    private fun StoredTurn.mcpExclusionKey(): LocalMcpWorkerExclusion.Key? {
        if (phase() != KotlinTurnPhase.INTERRUPTED_TOOL || !invocationStarted) return null
        val previousOwner = mcpOwner?.takeIf { it.isNotBlank() } ?: return null
        val call = calls.getOrNull(index)?.domain() ?: return null
        if (mcpGrant?.let { it.conversationId == conversationId && it.tools.count { binding -> binding.name == call.name } == 1 } != true) return null
        return LocalMcpWorkerExclusion.Key(previousOwner, id, call.callId)
    }

    /** Only this store can register the calls its own stopped worker tree previously admitted. */
    internal suspend fun localMcpExclusionKey(conversationId: String): LocalMcpWorkerExclusion.Key? {
        val rows = db.rows()
        val id = rows.getValue(ACTIVE + conversationId)?.value ?: return null
        val turn = rows.read(id)
        if (turn.conversationId != conversationId || turn.mcpOwner != owner || turn.owner != null) return null
        return turn.mcpExclusionKey()
    }

    /** Previous-owner calls are visible but cannot be acknowledged without a local stop proof. */
    internal suspend fun uncertainMcpCall(conversationId: String): UncertainMcpCall? {
        val rows = db.rows()
        val turnId = rows.getValue(ACTIVE + conversationId)?.value ?: return null
        val turn = rows.read(turnId)
        if (turn.conversationId != conversationId) return null
        val key = turn.mcpExclusionKey() ?: return null
        val call = turn.calls.getOrNull(turn.index)?.domain() ?: return null
        return UncertainMcpCall(turnId, call.callId, call.name, call.arguments.take(16_384),
            argumentsTruncated = call.arguments.length > 16_384,
            canAcknowledge = turn.mcpOwner == owner || LocalMcpWorkerExclusion.provesStopped(db, key))
    }

    /** Acknowledgement records uncertainty, not completion, and never invokes the remote tool. */
    internal suspend fun acknowledgeUncertainMcpCall(conversationId: String, turnId: String, callId: String) {
        var priorKey: LocalMcpWorkerExclusion.Key? = null
        transaction { rows, _ ->
            val turn = rows.read(turnId)
            val evidence = turn.mcpExclusionKey() ?: error("Uncertain MCP call is unavailable")
            check(turn.conversationId == conversationId &&
                (turn.mcpOwner == owner || LocalMcpWorkerExclusion.provesStopped(db, evidence)) && turn.owner == null &&
                turn.phase() == KotlinTurnPhase.INTERRUPTED_TOOL && turn.invocationStarted &&
                rows.getValue(ACTIVE + conversationId)?.value == turnId) { "Uncertain MCP call changed" }
            val call = turn.calls.getOrNull(turn.index)?.domain() ?: error("Uncertain MCP call is unavailable")
            check(call.callId == callId) { "Uncertain MCP call changed" }
            val binding = turn.mcpGrant?.takeIf { it.conversationId == conversationId }?.tools?.singleOrNull { it.name == call.name }
                ?: error("Uncertain MCP grant is unavailable")
            val key = ACKNOWLEDGED_MCP_UNCERTAINTY + turnId
            check(rows.getValue(key) == null) { "MCP uncertainty is already acknowledged" }
            val serverIdHash = java.security.MessageDigest.getInstance("SHA-256").digest(binding.serverId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            rows.upsertValues(listOf(KeyValueRow(key, json.encodeToString(AcknowledgedMcpUncertainty(
                conversationId, turnId, callId, serverIdHash, call.name, clock())))))
            rows.write(turn.copy(phase = KotlinTurnPhase.FAILED.name))
            rows.deleteValue(ACTIVE + conversationId)
            if (turn.mcpOwner != owner) priorKey = evidence
        }
        priorKey?.let { LocalMcpWorkerExclusion.forget(db, it) }
    }
    private suspend fun LocalRowsDao.requireActiveDelegation(parent: KotlinDelegationParent, childId: String, admitted: Boolean): StoredTurn {
        val turn = read(parent.turnId)
        val call = turn.calls.getOrNull(turn.index)?.domain()
        val binding = checkNotNull(turn.delegation) { "Delegated turn is no longer authorized" }
        check(turn.phase() == KotlinTurnPhase.EXECUTING_TOOL && turn.invocationStarted && !turn.delegatedRun && !turn.scheduledRun &&
            getValue(ACTIVE + turn.conversationId)?.value == turn.id && call?.name == "messenger_agents" && call.callId == parent.callId &&
            binding.childId == childId && binding.admitted == admitted) { "Delegated turn is no longer authorized" }
        requireTurnCloudSession(turn)
        turn.remoteScope?.let { requireRemoteControl(it, turn.conversationId, requireOwnedProject = true) }
        requireCoreToolAllowed(turn.conversationId, "messenger_agents")
        val conversation = requireNotNull(conversation(turn.conversationId))
        check(conversation.projectId == binding.projectId && (!binding.writable || conversation.writable)) { "Delegated workspace changed" }
        return turn
    }

    /** Called within the parent invocation transaction immediately after its claim. */
    internal suspend fun bindDelegatedChildInTransaction(turnId: String, call: ToolCallData, childId: String,
        agentId: String, projectId: String?, writable: Boolean) {
        val rows = db.rows()
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.EXECUTING_TOOL)
        check(!turn.delegatedRun && !turn.scheduledRun && turn.invocationStarted && turn.delegation == null &&
            call.name == "messenger_agents" && turn.calls.getOrNull(turn.index)?.domain() == call) { "Delegation is unavailable" }
        val conversation = requireNotNull(rows.conversation(turn.conversationId))
        check(conversation.projectId == projectId && (!writable || conversation.writable)) { "Delegated workspace changed" }
        rows.write(turn.copy(delegation = StoredDelegation(childId, agentId, projectId, writable)))
    }

    /** ACP has a separate session store; fence its single startup against the parent turn. */
    internal suspend fun claimAcpDelegation(parent: KotlinDelegationParent, childId: String) { transaction { rows, _ ->
        val turn = rows.requireActiveDelegation(parent, childId, admitted = false)
        rows.write(turn.copy(delegation = checkNotNull(turn.delegation).copy(admitted = true)))
    } }
    internal suspend fun assertAcpDelegation(parent: KotlinDelegationParent, childId: String) { transaction { rows, _ ->
        rows.requireActiveDelegation(parent, childId, admitted = true)
    } }
    internal suspend fun coreToolInvocationStarted(turnId: String, call: ToolCallData): Boolean {
        val turn = db.rows().read(turnId)
        return turn.owner == owner && turn.phase() == KotlinTurnPhase.EXECUTING_TOOL && turn.invocationStarted &&
            turn.calls.getOrNull(turn.index)?.domain() == call
    }
    internal suspend fun requireScheduleCreationAllowed(turnId: String, callId: String) {
        val turn = db.rows().read(turnId)
        val call = turn.calls.getOrNull(turn.index)?.domain()
        check(!turn.scheduledRun && turn.phase() == KotlinTurnPhase.EXECUTING_TOOL && turn.invocationStarted &&
            call?.name == "messenger_schedule_task" && call.callId == callId) { "Scheduled tool provenance is unavailable" }
    }

    private suspend fun LocalRowsDao.claimCoreTool(turnId: String, call: ToolCallData, allowFailedImage: Boolean = false) {
        val rows = this
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.EXECUTING_TOOL)
        rows.requireTurnCloudSession(turn)
        turn.remoteScope?.let { rows.requireRemoteControl(it, turn.conversationId, requireOwnedProject = true) }
        check(turn.coreGrant?.let { it.conversationId == turn.conversationId && call.name in it.allowedNames } == true) { "Core tool was not granted to this turn" }
        rows.requireCoreToolAllowed(turn.conversationId, call.name)
        check(allowFailedImage || call.name != "messenger_generate_image" || !turn.imageFailed) { "A previous image call failed; start a new user turn before trying again" }
        check(turn.calls.getOrNull(turn.index)?.domain() == call && !turn.invocationStarted) { "Tool invocation was already claimed" }
        rows.write(turn.copy(invocationStarted = true))
    }

    /** Caller must hold this database's IMMEDIATE writer transaction and any asset gate first. */
    internal suspend fun claimCoreToolInTransaction(turnId: String, call: ToolCallData) { db.rows().claimCoreTool(turnId, call) }

    /** Persist the per-turn failure barrier before the caller can offer another paid image call. */
    internal suspend fun markCoreImageFailure(turnId: String, call: ToolCallData) { transaction { rows, _ ->
        val turn = rows.read(turnId)
        if (call.name == "messenger_generate_image" && turn.owner == owner && turn.invocationStarted &&
            turn.phase() in setOf(KotlinTurnPhase.EXECUTING_TOOL, KotlinTurnPhase.CANCELLING_TOOL) &&
            turn.calls.getOrNull(turn.index)?.domain() == call) rows.write(turn.copy(imageFailed = true))
    } }

    /** Only in-process Room-only actions belong here: do not perform network or acquire file locks. */
    internal suspend fun executeRoomCoreTool(turnId: String, call: ToolCallData, action: suspend () -> ToolExecutionResult): ToolExecutionResult = transaction { rows, _ ->
        rows.claimCoreTool(turnId, call, allowFailedImage = true)
        // Roll back the protected action on failure while retaining the invocation claim.
        try { db.useWriterConnection { connection -> connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { action() } } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { ToolExecutionResult(failure.message ?: "Core tool failed", isError = true) }
    }

    /** Atomically recheck the live workspace and policy immediately before a tracked dispatch. */
    internal suspend fun claimRuntimeToolInvocation(turnId: String, call: ToolCallData, grant: KotlinRuntimeToolGrant) {
        transaction { rows, _ ->
            val turn = rows.read(turnId)
            turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.EXECUTING_TOOL)
            rows.requireTurnCloudSession(turn)
            turn.remoteScope?.let { rows.requireRemoteControl(it, turn.conversationId, requireOwnedProject = true) }
            check(turn.toolGrant == grant && grant.conversationId == turn.conversationId && call.name in grant.allowedNames) {
                "Runtime workspace grant changed"
            }
            check(call.name in setOf("glob", "grep", "read", "edit", "create")) { "Runtime operation is unavailable" }
            val conversation = requireNotNull(rows.conversation(turn.conversationId)) { "Conversation is unavailable" }
            check(conversation.projectId == grant.projectId) { "Conversation workspace changed" }
            if (grant.projectId != null) {
                val project = requireNotNull(rows.project(grant.projectId)) { "Project workspace is unavailable" }
                check(project.workspace == grant.workspaceRoot && project.deviceId == grant.deviceId) { "Project workspace changed" }
            }
            val agent = requireNotNull(rows.agent(conversation.agentId)) { "Agent is unavailable" }
            val default = rows.defaultAgents().singleOrNull() ?: error("Default Agent is unavailable")
            check(runtimeToolAllowedByAgent(conversation, agent, default, call.name, call.name in setOf("edit", "create"))) {
                "Runtime tool is disabled by Agent policy"
            }
            check(turn.request.tools.orEmpty().count { it.function.name == call.name } == 1 &&
                turn.calls.getOrNull(turn.index)?.domain() == call && !turn.invocationStarted) { "Tool invocation was already claimed" }
            rows.write(turn.copy(invocationStarted = true))
        }
    }

    /** The owner must hold its authority fence and the exact inventory admission through this claim. */
    internal suspend fun registerMcpInvocation(turnId: String, call: ToolCallData): McpInvocationReceipt {
        val receipt = McpInvocationReceipt(turnId, call)
        var registered = false
        try { return transaction { rows, _ ->
            val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.EXECUTING_TOOL)
            check(turn.mcpOwner == owner && !turn.invocationStarted && turn.calls.getOrNull(turn.index)?.domain() == call &&
                turn.mcpGrant?.tools?.count { it.name == call.name } == 1 && rows.getValue(ACTIVE + turn.conversationId)?.value == turnId) {
                "MCP invocation cannot be registered"
            }
            check(mcpInvocations.putIfAbsent(turnId, receipt) == null) { "MCP invocation is already registered" }
            registered = true
            receipt
        } } catch (failure: Throwable) {
            if (registered) mcpInvocations.remove(turnId, receipt)
            throw failure
        }
    }

    internal fun releaseMcpInvocation(receipt: McpInvocationReceipt) { mcpInvocations.remove(receipt.turnId, receipt) }

    internal suspend fun claimMcpToolInvocation(receipt: McpInvocationReceipt, grant: KotlinMcpToolGrant,
        binding: KotlinMcpToolBinding): List<RoomCloudProviderGate.ToolPermit> {
        check(mcpInvocations[receipt.turnId] === receipt) { "MCP invocation receipt is not current" }
        val turnId = receipt.turnId
        val call = receipt.call
        val permits = mutableListOf<RoomCloudProviderGate.ToolPermit>()
        try { return transaction { rows, _ ->
            val turn = rows.read(turnId)
            turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.EXECUTING_TOOL)
            rows.requireTurnCloudSession(turn)
            turn.remoteScope?.let { rows.requireRemoteControl(it, turn.conversationId, requireOwnedProject = true) }
            check(!turn.scheduledRun && !turn.delegatedRun && turn.mcpOwner == owner && turn.mcpGrant == grant &&
                grant.conversationId == turn.conversationId && grant.tools.singleOrNull { it.name == call.name } == binding &&
                rows.getValue(ACTIVE + turn.conversationId)?.value == turnId) { "MCP turn scope changed" }
            val conversation = requireNotNull(rows.conversation(turn.conversationId)) { "Conversation is unavailable" }
            val agent = requireNotNull(rows.agent(conversation.agentId)) { "Agent is unavailable" }
            val default = rows.defaultAgents().singleOrNull() ?: error("Default Agent is unavailable")
            check(runtimeToolAllowedByAgent(conversation, agent, default, call.name, false)) { "MCP tool is disabled by Agent policy" }
            val spec = turn.request.tools.orEmpty().singleOrNull { it.function.name == call.name }
            check(spec?.type == "function" && spec.function.description == binding.description &&
                spec.function.parameters == binding.parameters && turn.calls.getOrNull(turn.index)?.domain() == call &&
                !turn.invocationStarted) { "MCP invocation was already claimed or its declaration changed" }
            val sessions = listOfNotNull(
                turn.cloudIdentity?.let { SyncSession(it.accountId, it.generation) },
                turn.remoteScope?.let { SyncSession(it.accountId, it.generation) },
            ).distinct()
            sessions.forEach { session ->
                permits.add(checkNotNull(cloudGate) { "Cloud provider gate is unavailable" }.registerToolPermit(session))
            }
            rows.write(turn.copy(invocationStarted = true))
            permits.toList()
        } } catch (failure: Throwable) { permits.forEach { it.release() }; throw failure }
    }

    /** Persist this before dispatch so no second caller can invoke the same external side effect. */
    suspend fun claimToolInvocation(turnId: String, call: ToolCallData) { transaction { rows, _ ->
        val turn = rows.read(turnId); turn.requireOwner(); turn.requirePhase(KotlinTurnPhase.EXECUTING_TOOL)
        rows.requireTurnCloudSession(turn)
        check(call.name != "messenger_generate_image" || !turn.imageFailed) { "A previous image call failed; start a new user turn before trying again" }
        turn.remoteScope?.let { rows.requireRemoteControl(it, turn.conversationId, requireOwnedProject = true) }
        if (call.name.startsWith("messenger_")) {
            check(turn.coreGrant?.let { it.conversationId == turn.conversationId && call.name in it.allowedNames } == true) { "Core tool was not granted to this turn" }
            rows.requireCoreToolAllowed(turn.conversationId, call.name)
        }
        check(turn.calls.getOrNull(turn.index)?.domain() == call && !turn.invocationStarted) { "Tool invocation was already claimed" }
        rows.write(turn.copy(invocationStarted = true))
    } }
    suspend fun expectedTool(turnId: String, call: ToolCallData): ToolSpecDto? {
        val turn = db.rows().read(turnId)
        if (turn.phase() !in setOf(KotlinTurnPhase.READY_TOOL, KotlinTurnPhase.EXECUTING_TOOL) || turn.calls.getOrNull(turn.index)?.domain() != call) return null
        return turn.request.tools.orEmpty().singleOrNull { it.function.name == call.name }
    }

    /** Process startup can reattach and recover every incomplete turn from the database. */
    fun observeActiveTurns(): Flow<List<KotlinTurnSnapshot>> = db.rows().observeActiveTurnKeys().map { keys ->
        keys.map { key -> snapshot(key.value) }
    }

    /** Failure messages survive process death until presented and acknowledged. */
    fun observeFailures(): Flow<List<KotlinTurnFailure>> = combine(
        db.rows().observeTurnErrorKeys(), db.rows().observeAllConversations(),
    ) { notices, conversations ->
        notices.map { row ->
            val event = if (row.key.startsWith(LEGACY_NOTICE))
                StoredTurnFailure(row.key.removePrefix(LEGACY_NOTICE), row.value)
            else json.decodeFromString<StoredTurnFailure>(row.value)
            // Older notices did not carry a conversation ID. A missing or unreadable
            // turn only removes attribution; the original failure must remain visible.
            val turnData = if (event.conversationId == null) db.rows().getValue(TURN + event.turnId)?.value else null
            val conversationId = event.conversationId ?: turnData?.let { data ->
                runCatching { json.decodeFromString<StoredTurn>(data) }.getOrNull()
                    ?.takeIf { it.id == event.turnId }?.conversationId
            }
            val conversation = conversations.firstOrNull { it.id == conversationId }
            KotlinTurnFailure(row.key, event.turnId, event.detail, conversation?.id, conversation?.title)
        }
    }

    /** A snackbar consumer can observe this until it has displayed and acknowledged it. */
    fun observeFailure(turnId: String): Flow<String?> = observeFailures().map { failures -> failures.firstOrNull { it.turnId == turnId }?.detail }
    suspend fun acknowledgeFailure(noticeId: String, expectedDetail: String) { transaction { rows, _ ->
        require(noticeId.startsWith(NOTICE) || noticeId.startsWith(LEGACY_NOTICE)) { "Invalid turn notice identity" }
        val value = rows.getValue(noticeId)?.value ?: return@transaction
        val detail = if (noticeId.startsWith(LEGACY_NOTICE)) value else json.decodeFromString<StoredTurnFailure>(value).detail
        if (detail == expectedDetail) rows.deleteValue(noticeId)
    } }

    private companion object {
        val terminal = setOf(KotlinTurnPhase.FINISHED, KotlinTurnPhase.FAILED, KotlinTurnPhase.CANCELLED, KotlinTurnPhase.INTERRUPTED_TOOL)
    }
}
