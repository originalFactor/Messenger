/*
 * Copyright 2026 originalFactor
 * Licensed under the Apache License, Version 2.0. See https://www.apache.org/licenses/LICENSE-2.0
 */
package cc.ptoe.messenger.data.repository.room

import androidx.room.Transactor
import androidx.room.useWriterConnection
import cc.ptoe.messenger.data.chat.ACTIVE_TURN_KEY_PREFIX
import cc.ptoe.messenger.data.chat.RuntimeWorkspaceFence
import cc.ptoe.messenger.data.cloud.SyncCollection
import cc.ptoe.messenger.data.cloud.SyncMutation
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.local.room.LocalRowsDao
import cc.ptoe.messenger.data.local.room.KeyValueRow
import cc.ptoe.messenger.data.local.room.ProjectRow
import cc.ptoe.messenger.data.local.room.ProviderRow
import cc.ptoe.messenger.data.local.room.CLOUD_REPLACEMENT_LOCK_KEY
import cc.ptoe.messenger.domain.model.ApiFormat
import cc.ptoe.messenger.domain.model.Project
import cc.ptoe.messenger.domain.model.Provider
import cc.ptoe.messenger.domain.repository.ProjectRepository
import cc.ptoe.messenger.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Queue synchronized changes in the same database transaction as the local write. */
private suspend fun <T> LocalDatabase.writeLocal(
    workspaceAuthority: Boolean = true,
    block: suspend (LocalRowsDao, suspend (SyncCollection, String, SyncMutation) -> Unit) -> T,
): T {
    suspend fun commit(): T = useWriterConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val sync = cloudSync()
            check(sync.value(CLOUD_REPLACEMENT_LOCK_KEY) == null) { "Cloud replacement requires completion or recovery" }
            block(rows()) { collection, id, mutation ->
                sync.enqueueLocal(collection, id, mutation)
            }
        }
    }
    return if (workspaceAuthority) RuntimeWorkspaceFence.forDatabase(this).mutate { commit() } else commit()
}

/** The supplied guard must invoke its action exactly once without reentering a guarded writer. */
private suspend fun <T> configurationMutation(
    guard: suspend (suspend () -> Unit) -> Unit,
    action: suspend () -> T,
): T {
    var result: Result<T>? = null
    guard { result = Result.success(action()) }
    return checkNotNull(result) { "Configuration writer did not execute" }.getOrThrow()
}

private suspend fun LocalRowsDao.requireNoActiveTurn(conversationId: String) {
    check(getValue(ACTIVE_TURN_KEY_PREFIX + conversationId) == null) { "Cannot change messages with an active turn" }
}

/** Kotlin-backed repositories remain opt-in until all Core features and migration are complete. */
class RoomProviderRepository(
    private val db: LocalDatabase,
    private val configurationWrite: suspend (suspend () -> Unit) -> Unit = { it() },
    private val onChanged: (String, Boolean) -> Unit = { _, _ -> },
) : ProviderRepository {
    private val rows get() = db.rows()
    override fun getAll() = rows.observeProviders().map { list -> list.map { it.domain() } }
    override fun getById(id: String) = rows.observeProvider(id).map { it?.domain() }
    override suspend fun insert(provider: Provider) = configurationWrite { db.writeLocal { dao, mark ->
        check(!db.cloudSync().hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) { "Cannot change providers with an active turn" }
        dao.upsertProviders(listOf(provider.row())); mark(SyncCollection.PROVIDER, provider.id, SyncMutation.UPSERT)
    }; onChanged(provider.id, false) }
    override suspend fun update(provider: Provider) = insert(provider)
    override suspend fun delete(id: String) = configurationWrite { db.writeLocal { dao, mark ->
        check(!db.cloudSync().hasActiveTurn(ACTIVE_TURN_KEY_PREFIX)) { "Cannot change providers with an active turn" }
        dao.deleteProvider(id); mark(SyncCollection.PROVIDER, id, SyncMutation.DELETE)
    }; onChanged(id, true) }
}

private fun ProviderRow.domain() = Provider(id, name, baseUrl, apiKey, ApiFormat.fromWireName(apiFormat), nativeWebSearch, createdAt, updatedAt)
private fun Provider.row() = ProviderRow(id, name, baseUrl, apiKey, apiFormat.wireName, nativeWebSearch, createdAt, updatedAt)

class RoomProjectRepository(
    private val db: LocalDatabase,
    private val configurationWrite: suspend (suspend () -> Unit) -> Unit = { it() },
    private val onChanged: (String, Boolean) -> Unit = { _, _ -> },
) : ProjectRepository {
    private val rows get() = db.rows()
    override fun getAll() = rows.observeProjects().map { list -> list.map { it.domain() } }
    override fun getById(id: String) = rows.observeProject(id).map { it?.domain() }
    override suspend fun insert(project: Project) = configurationWrite { db.writeLocal { dao, mark ->
        val existing = dao.project(project.id)
        val owner = if (existing != null) existing.deviceId else {
            val stored = dao.getValue("device_id")?.value
            if (stored != null) {
                check(stored.isNotBlank()) { "Local device identity is invalid" }
                stored
            } else cc.ptoe.messenger.data.util.randomUuid().also { dao.upsertValues(listOf(KeyValueRow("device_id", it))) }
        }
        dao.upsertProjects(listOf(project.row().copy(deviceId = owner)))
        mark(SyncCollection.PROJECT, project.id, SyncMutation.UPSERT)
    }; onChanged(project.id, false) }
    override suspend fun update(project: Project) = insert(project)
    override suspend fun delete(id: String) = configurationWrite { db.writeLocal { dao, mark -> val affected = dao.conversationIdsForProject(id); dao.deleteProject(id); mark(SyncCollection.PROJECT, id, SyncMutation.DELETE); affected.forEach { mark(SyncCollection.CONVERSATION, it, SyncMutation.UPSERT) } }; onChanged(id, true) }
}

private fun ProjectRow.domain() = Project(id, name, workspace, createdAt, updatedAt)
private fun Project.row() = ProjectRow(id, name, workspace, null, createdAt, updatedAt)

class RoomAgentRepository(
    private val db: LocalDatabase,
    private val configurationWrite: suspend (suspend () -> Unit) -> Unit = { it() },
    private val onChanged: (cc.ptoe.messenger.domain.model.Agent?, cc.ptoe.messenger.domain.model.Agent?) -> Unit = { _, _ -> },
) : cc.ptoe.messenger.domain.repository.AgentRepository {
    private val rows get() = db.rows()
    /** Complete startup before exposing an empty or imported database to application code. */
    suspend fun ensureDefaultAgent(seed: cc.ptoe.messenger.domain.model.Agent): cc.ptoe.messenger.domain.model.Agent =
        configurationMutation(configurationWrite) { RuntimeWorkspaceFence.forDatabase(db).mutate { rows.ensureDefaultAgent(seed.row()).domain() } }
    override fun getAll() = rows.observeAgents().map { list -> list.map { it.domain() } }
    override fun getById(id: String) = rows.observeAgent(id).map { it?.domain() }
    override fun getDefaultAgent() = rows.observeDefaultAgent().map { it?.domain() }
    override suspend fun insert(agent: cc.ptoe.messenger.domain.model.Agent) = configurationWrite {
        val demoted = db.writeLocal { dao, mark ->
            val changed = dao.upsertAgentPreservingDefault(agent.rowPreservingSampling(dao.agent(agent.id)))
            mark(SyncCollection.AGENT, agent.id, SyncMutation.UPSERT)
            changed.forEach { mark(SyncCollection.AGENT, it.id, SyncMutation.UPSERT) }
            changed
        }
        demoted.forEach { onChanged(it.domain(), it.copy(isDefault = false).domain()) }
        onChanged(null, rows.agent(agent.id)?.domain())
    }
    override suspend fun update(agent: cc.ptoe.messenger.domain.model.Agent) = configurationWrite {
        val (previous, demoted) = db.writeLocal { dao, mark ->
            val old = dao.agent(agent.id)
            val changed = dao.upsertAgentPreservingDefault(agent.rowPreservingSampling(old))
            mark(SyncCollection.AGENT, agent.id, SyncMutation.UPSERT)
            changed.forEach { mark(SyncCollection.AGENT, it.id, SyncMutation.UPSERT) }
            old?.domain() to changed
        }
        demoted.forEach { onChanged(it.domain(), it.copy(isDefault = false).domain()) }
        onChanged(previous, rows.agent(agent.id)?.domain())
    }
    override suspend fun clone(id: String): cc.ptoe.messenger.domain.model.Agent? = configurationMutation(configurationWrite) {
        val result = db.writeLocal { dao, mark ->
            val original = dao.agent(id) ?: return@writeLocal null
            val now = cc.ptoe.messenger.data.util.currentTimeMillis()
            val copy = original.copy(id = cc.ptoe.messenger.data.util.randomUuid(), name = "${original.name} (Copy)",
                isDefault = false, role = cc.ptoe.messenger.domain.model.Agent.ROLE_CHAT, createdAt = now, updatedAt = now)
            check(dao.agent(copy.id) == null) { "Agent ID collision" }
            val demoted = dao.upsertAgentPreservingDefault(copy)
            mark(SyncCollection.AGENT, copy.id, SyncMutation.UPSERT)
            demoted.forEach { mark(SyncCollection.AGENT, it.id, SyncMutation.UPSERT) }
            checkNotNull(dao.agent(copy.id)) to demoted
        } ?: return@configurationMutation null
        result.second.forEach { onChanged(it.domain(), it.copy(isDefault = false).domain()) }
        result.first.domain().also { onChanged(null, it) }
    }
    override suspend fun delete(id: String) = configurationWrite {
        val previous = rows.agent(id)?.domain() ?: return@configurationWrite
        val seed = cc.ptoe.messenger.domain.model.Agent.builtinTitleAgent(cc.ptoe.messenger.data.util.currentTimeMillis())
        val deleted = db.writeLocal { dao, mark ->
            val affected = dao.conversationIdsForAgent(id)
            check(affected.none { dao.getValue(ACTIVE_TURN_KEY_PREFIX + it) != null }) { "Cannot delete an agent with an active turn" }
            val removed = dao.deleteAgentPreservingDefault(id, seed.row())
            if (removed) {
                mark(SyncCollection.AGENT, id, SyncMutation.DELETE)
                affected.forEach { mark(SyncCollection.CONVERSATION, it, SyncMutation.DELETE) }
            }
            removed
        }
        if (deleted) onChanged(previous, null)
    }
}

internal fun cc.ptoe.messenger.data.local.room.AgentRow.domain() = cc.ptoe.messenger.domain.model.Agent(
    id, name, avatar, systemPrompt, description, defaultModelId, temperature?.toFloat() ?: 0.7f, topP?.toFloat() ?: 1f,
    maxTokens?.toInt(), reasoningEffort, isDefault, followDefaultSystemPrompt, followDefaultModel, followDefaultTemperature,
    followDefaultTopP, followDefaultMaxTokens, followDefaultReasoningEffort, marketAgentId, marketAgentVersion,
    marketAgentRole, role, toolsEnabled, toolsFollowDefault, cc.ptoe.messenger.data.util.ToolsConfigCodec.decode(toolsConfig), createdAt, updatedAt)
internal fun cc.ptoe.messenger.domain.model.Agent.row() = cc.ptoe.messenger.data.local.room.AgentRow(
    id, name, avatar, systemPrompt, description, defaultModelId, temperature.toDouble(), topP.toDouble(), maxTokens?.toLong(),
    reasoningEffort, isDefault, followDefaultSystemPrompt, followDefaultModel, followDefaultTemperature, followDefaultTopP,
    followDefaultMaxTokens, followDefaultReasoningEffort, marketAgentId, marketAgentVersion, marketAgentRole, role, toolsEnabled,
    toolsFollowDefault, cc.ptoe.messenger.data.util.ToolsConfigCodec.encode(toolsConfig), createdAt, updatedAt)

/** Domain defaults cannot distinguish a null database value from an explicit default. */
private fun cc.ptoe.messenger.domain.model.Agent.rowPreservingSampling(old: cc.ptoe.messenger.data.local.room.AgentRow?) = row().let { mapped ->
    if (old == null) mapped else mapped.copy(
        temperature = if ((old.temperature?.toFloat() ?: 0.7f) == temperature) old.temperature else mapped.temperature,
        topP = if ((old.topP?.toFloat() ?: 1f) == topP) old.topP else mapped.topP,
    )
}

class RoomModelRepository(
    private val db: LocalDatabase,
    private val configurationWrite: suspend (suspend () -> Unit) -> Unit = { it() },
    private val onChanged: (String, Boolean) -> Unit = { _, _ -> },
) : cc.ptoe.messenger.domain.repository.ModelRepository {
    private val rows get() = db.rows()
    override fun getAll() = rows.observeModels().map { list -> list.map { it.domain() } }
    override fun getByProviderId(providerId: String) = getAll().map { list -> list.filter { it.providerId == providerId } }
    override fun getEnabledByProviderId(providerId: String) = getByProviderId(providerId).map { list -> list.filter { it.isEnabled } }
    override fun getById(id: String) = rows.observeModel(id).map { it?.domain() }
    override suspend fun insert(model: cc.ptoe.messenger.domain.model.ChatModel) = insertAll(listOf(model))
    override suspend fun insertAll(models: List<cc.ptoe.messenger.domain.model.ChatModel>) = configurationWrite {
        db.writeLocal { dao, mark ->
            val oldOwners = models.mapNotNull { dao.model(it.id)?.providerId }
            dao.upsertModelsPreservingMetadata(models.map { it.row() })
            (oldOwners + models.map { it.providerId }).distinct().forEach { mark(SyncCollection.PROVIDER, it, SyncMutation.UPSERT) }
        }
        models.map { it.providerId }.distinct().forEach { onChanged(it, false) }
    }
    override suspend fun update(model: cc.ptoe.messenger.domain.model.ChatModel) = insert(model)
    override suspend fun delete(id: String) = deleteBatch(listOf(id))
    override suspend fun deleteBatch(ids: List<String>) { if (ids.isNotEmpty()) configurationWrite {
        val owners = db.writeLocal { dao, mark ->
            val idsToNotify = ids.mapNotNull { dao.model(it)?.providerId }.distinct()
            dao.deleteModels(ids)
            idsToNotify.forEach { mark(SyncCollection.PROVIDER, it, SyncMutation.UPSERT) }
            idsToNotify
        }
        owners.forEach { onChanged(it, false) }
    } }
    override suspend fun setEnabled(id: String, isEnabled: Boolean) = setEnabledBatch(listOf(id), isEnabled)
    override suspend fun setEnabledBatch(ids: List<String>, isEnabled: Boolean) {
        if (ids.isNotEmpty()) configurationWrite {
            val owners = db.writeLocal { dao, mark ->
                val idsToNotify = ids.mapNotNull { dao.model(it)?.providerId }.distinct()
                dao.setModelsEnabled(ids, isEnabled)
                idsToNotify.forEach { mark(SyncCollection.PROVIDER, it, SyncMutation.UPSERT) }
                idsToNotify
            }
            owners.forEach { onChanged(it, false) }
        }
    }
}

private fun cc.ptoe.messenger.data.local.room.ModelRow.domain() = cc.ptoe.messenger.domain.model.ChatModel(
    id, providerId, modelId, displayName, isEnabled, contextWindow, inputRate, outputRate, cacheReadRate, cacheWriteRate,
    cc.ptoe.messenger.domain.model.ModelModality.fromCsv(inputModalities), cc.ptoe.messenger.domain.model.ModelModality.fromCsv(outputModalities),
    supportsToolCalling, supportsThinking, supportsJsonOutput, supportsTemperature, createdAt)
private fun cc.ptoe.messenger.domain.model.ChatModel.row() = cc.ptoe.messenger.data.local.room.ModelRow(
    id, providerId, modelId, displayName, isEnabled, contextWindow, inputRate, outputRate, cacheReadRate, cacheWriteRate,
    cc.ptoe.messenger.domain.model.ModelModality.toCsv(inputModalities), cc.ptoe.messenger.domain.model.ModelModality.toCsv(outputModalities),
    supportsToolCalling, supportsThinking, supportsJsonOutput, supportsTemperature, createdAt, null, null)

/** ACP conversations are persisted separately from Room conversation rows. Implementations
 * must enforce active-turn protection and preserve their agent/workspace bindings. */
interface AcpConversationStore {
    fun observeAll(): Flow<List<cc.ptoe.messenger.domain.model.Conversation>>
    suspend fun messagesForSync(id: String): List<cc.ptoe.messenger.domain.model.Message> = error("ACP history is unavailable")
    suspend fun update(conversation: cc.ptoe.messenger.domain.model.Conversation)
    suspend fun delete(id: String)
}

class RoomConversationRepository(
    private val db: LocalDatabase,
    private val acp: AcpConversationStore,
    private val onChanged: (String, Boolean) -> Unit = { _, _ -> },
) : cc.ptoe.messenger.domain.repository.ConversationRepository {
    private val rows get() = db.rows()
    override fun getAll() = combine(rows.observeAllConversations(), acp.observeAll()) { local, external ->
        val native = local.map { it.domain() }
        check(external.all { it.id.startsWith("acp:") } && native.none { it.id.startsWith("acp:") }) { "Invalid ACP conversation identity" }
        (native + external).sortedByDescending { it.updatedAt }
    }
    override fun getByAgentId(agentId: String) = getAll().map { list -> list.filter { it.agentId == agentId } }
    override fun getByProjectId(projectId: String) = getAll().map { list -> list.filter { it.projectId == projectId } }
    override fun getById(id: String) = getAll().map { list -> list.firstOrNull { it.id == id } }
    override suspend fun insert(conversation: cc.ptoe.messenger.domain.model.Conversation) {
        if (conversation.id.startsWith("acp:")) error("ACP conversations must be created through the ACP session service")
        db.writeLocal { dao, mark -> dao.upsertConversations(listOf(conversation.row())); mark(SyncCollection.CONVERSATION, conversation.id, SyncMutation.UPSERT) }; onChanged(conversation.id, false)
    }
    override suspend fun update(conversation: cc.ptoe.messenger.domain.model.Conversation) {
        if (conversation.id.startsWith("acp:")) { acp.update(conversation); return }
        db.writeLocal { dao, mark -> dao.upsertConversations(listOf(conversation.row())); mark(SyncCollection.CONVERSATION, conversation.id, SyncMutation.UPSERT) }; onChanged(conversation.id, false)
    }
    override suspend fun updateLastMessage(id: String, lastMessage: String, updatedAt: Long) {
        check(!id.startsWith("acp:")) { "ACP preview is derived from its session" }
        db.writeLocal { dao, mark -> dao.updateLastMessage(id, lastMessage, updatedAt); mark(SyncCollection.CONVERSATION, id, SyncMutation.UPSERT) }; onChanged(id, false)
    }
    override suspend fun delete(id: String) {
        if (id.startsWith("acp:")) { acp.delete(id); return }
        db.writeLocal { dao, mark ->
            check(dao.getValue(ACTIVE_TURN_KEY_PREFIX + id) == null) { "Cannot delete a conversation with an active turn" }
            dao.deleteConversation(id); mark(SyncCollection.CONVERSATION, id, SyncMutation.DELETE)
        }; onChanged(id, true)
    }
    override suspend fun sweepEmptyConversations(excludeIds: List<String>): List<String> {
        val deleted = db.writeLocal { dao, mark ->
            val deleted = dao.sweepEmptyConversations(cc.ptoe.messenger.data.util.currentTimeMillis() - 5 * 60 * 1000, excludeIds)
            deleted.forEach { mark(SyncCollection.CONVERSATION, it, SyncMutation.DELETE) }
            deleted
        }
        deleted.forEach { onChanged(it, true) }
        return deleted
    }
}

private fun cc.ptoe.messenger.data.local.room.ConversationRow.domain() = cc.ptoe.messenger.domain.model.Conversation(
    id = id, title = title, providerId = providerId, agentId = agentId, projectId = projectId, overrideModelId = overrideModelId,
    overrideTemperature = overrideTemperature?.toFloat(), overrideTopP = overrideTopP?.toFloat(), overrideMaxTokens = overrideMaxTokens?.toInt(),
    overrideReasoningEffort = overrideReasoningEffort, overrideToolsEnabled = overrideToolsEnabled,
    overrideToolsConfig = overrideToolsConfig?.let { cc.ptoe.messenger.data.util.ToolsConfigCodec.decode(it) }, writable = writable,
    createdAt = createdAt, updatedAt = updatedAt, lastMessage = lastMessage, reasoningFormat = reasoningFormat,
    contextSummary = contextSummary, contextSummaryUntil = contextSummaryUntil, contextTokens = contextTokens, contextTokensAt = contextTokensAt)
private fun cc.ptoe.messenger.domain.model.Conversation.row() = cc.ptoe.messenger.data.local.room.ConversationRow(
    id, title, providerId, agentId, projectId, overrideModelId, overrideTemperature?.toDouble(), overrideTopP?.toDouble(), overrideMaxTokens?.toLong(),
    overrideReasoningEffort, overrideToolsEnabled, overrideToolsConfig?.let { cc.ptoe.messenger.data.util.ToolsConfigCodec.encode(it) }, writable,
    createdAt, updatedAt, lastMessage, reasoningFormat, contextSummary, contextSummaryUntil, contextTokens, contextTokensAt)

class RoomMessageRepository(private val db: LocalDatabase, private val onChanged: (String) -> Unit = {},
    private val acp: AcpConversationStore? = null) : cc.ptoe.messenger.domain.repository.MessageRepository {
    private val rows get() = db.rows()
    override fun getByConversationId(conversationId: String) = rows.observeMessages(conversationId).map { list -> list.map { it.domain() } }
    override fun observeChanges(conversationId: String) = rows.observeMessageCount(conversationId).map { }
    override suspend fun getRecent(conversationId: String, limit: Int): List<cc.ptoe.messenger.domain.model.Message> {
        require(limit >= 0) { "Message limit must be non-negative" }
        return rows.recentMessages(conversationId, limit).asReversed().map { it.domain() }
    }
    override suspend fun getBefore(conversationId: String, beforeId: String, limit: Int): List<cc.ptoe.messenger.domain.model.Message> {
        require(limit >= 0) { "Message limit must be non-negative" }
        return rows.messagesBefore(conversationId, beforeId, limit).asReversed().map { it.domain() }
    }
    override suspend fun getFrom(conversationId: String, fromId: String) = rows.messagesFrom(conversationId, fromId).map { it.domain() }
    override suspend fun getByConversationIds(conversationIds: List<String>): List<cc.ptoe.messenger.domain.model.Message> {
        val native = conversationIds.filterNot { it.startsWith("acp:") }
        val byConversation = if (native.isEmpty()) emptyMap() else rows.messagesFor(native).groupBy { it.conversationId }
        return conversationIds.flatMap { id ->
            if (id.startsWith("acp:")) requireNotNull(acp) { "ACP history is unavailable" }.messagesForSync(id)
            else byConversation[id].orEmpty().map { it.domain() }
        }
    }
    override suspend fun insert(message: cc.ptoe.messenger.domain.model.Message) = insertAll(listOf(message))
    override suspend fun insertAll(messages: List<cc.ptoe.messenger.domain.model.Message>) {
        db.writeLocal(workspaceAuthority = false) { dao, mark ->
            val previous = messages.mapNotNull { dao.message(it.id)?.conversationId }
            (previous + messages.map { it.conversationId }).distinct().forEach { dao.requireNoActiveTurn(it) }
            dao.upsertMessagesPreservingUnknownParts(messages.map { it.row() })
            (previous + messages.map { it.conversationId }).distinct().forEach { mark(SyncCollection.CONVERSATION, it, SyncMutation.UPSERT) }
        }
        messages.map { it.conversationId }.distinct().forEach(onChanged)
    }
    override suspend fun update(message: cc.ptoe.messenger.domain.model.Message) = insert(message)
    override suspend fun delete(id: String) { val previous = db.writeLocal(workspaceAuthority = false) { dao, mark ->
        val old = dao.message(id); old?.let { dao.requireNoActiveTurn(it.conversationId) }; dao.deleteMessage(id)
        old?.let { mark(SyncCollection.CONVERSATION, it.conversationId, SyncMutation.UPSERT) }; old
    }; previous?.let { onChanged(it.conversationId) } }
    override suspend fun deleteByConversationId(conversationId: String) { db.writeLocal(workspaceAuthority = false) { dao, mark -> dao.requireNoActiveTurn(conversationId); dao.deleteMessagesFor(conversationId); mark(SyncCollection.CONVERSATION, conversationId, SyncMutation.UPSERT) }; onChanged(conversationId) }
    override suspend fun getConversationIdById(id: String) = rows.message(id)?.conversationId
}

/** Device-local selected agent, stored alongside the imported Core KV values. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomCurrentAgentRepository(
    private val db: LocalDatabase,
    private val agents: cc.ptoe.messenger.domain.repository.AgentRepository,
) : cc.ptoe.messenger.domain.repository.CurrentAgentRepository {
    private val key = "current_agent_id"
    override val currentAgentId = db.rows().observeValue(key).map { it?.value?.takeIf(String::isNotBlank) }.distinctUntilChanged()
    override val currentAgent = currentAgentId.flatMapLatest { id ->
        if (id == null) agents.getDefaultAgent() else agents.getById(id)
    }
    override suspend fun setCurrentAgentId(agentId: String?) {
        db.writeLocal { dao, _ ->
            if (agentId == null) dao.deleteValue(key) else dao.upsertValues(listOf(KeyValueRow(key, agentId)))
        }
    }
}

private fun cc.ptoe.messenger.data.local.room.MessageRow.domain() = cc.ptoe.messenger.domain.model.Message(
    id, conversationId, when (role.lowercase()) { "user" -> cc.ptoe.messenger.domain.model.MessageRole.USER; "assistant" -> cc.ptoe.messenger.domain.model.MessageRole.ASSISTANT; "system" -> cc.ptoe.messenger.domain.model.MessageRole.SYSTEM; else -> cc.ptoe.messenger.domain.model.MessageRole.TOOL },
    content, cc.ptoe.messenger.data.local.ContentPartCodec.decode(partsJson), timestamp,
    when (status.lowercase()) { "sending" -> cc.ptoe.messenger.domain.model.MessageStatus.SENDING; "error" -> cc.ptoe.messenger.domain.model.MessageStatus.ERROR; else -> cc.ptoe.messenger.domain.model.MessageStatus.SENT }, errorMessage)
private fun cc.ptoe.messenger.domain.model.Message.row() = cc.ptoe.messenger.data.local.room.MessageRow(
    id, conversationId, role.name.lowercase(), content, cc.ptoe.messenger.data.local.ContentPartCodec.encode(parts), timestamp, status.name.lowercase(), errorMessage)
