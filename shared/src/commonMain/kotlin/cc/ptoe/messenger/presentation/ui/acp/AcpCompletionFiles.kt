package cc.ptoe.messenger.presentation.ui.acp

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.data.util.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Read through the workspace host: Android projects belong to the Runtime UID. */
internal suspend fun acpCompletionFiles(core: CoreBridge, sessionId: String, query: String): List<String> = withContext(ioDispatcher) {
    val conversation = core.getConversationJson(sessionId)?.let { Json.parseToJsonElement(it).jsonObject }
        ?: return@withContext emptyList()
    val projectId = conversation.text("project_id").ifEmpty { conversation.text("projectId") }
    if (projectId.isBlank()) return@withContext emptyList()
    val project = core.getProjectJson(projectId)?.let { Json.parseToJsonElement(it).jsonObject }
        ?: return@withContext emptyList()
    val workspace = project.text("workspace")
    if (workspace.isBlank()) return@withContext emptyList()
    searchAcpWorkspaceFiles(workspace, query)
}
