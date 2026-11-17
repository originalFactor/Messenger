/* Copyright 2026 originalFactor. Licensed under the Apache License, Version 2.0. */
package cc.ptoe.messenger.data.repository.room

import cc.ptoe.messenger.data.chat.CompositeKotlinTools
import cc.ptoe.messenger.data.chat.KotlinProviderRequestPreparer
import cc.ptoe.messenger.data.chat.KotlinToolComponent
import cc.ptoe.messenger.data.chat.KotlinCoreSkillAdapter
import cc.ptoe.messenger.data.chat.KotlinMcpToolAdapter
import cc.ptoe.messenger.data.chat.RoomKotlinTurnStore
import cc.ptoe.messenger.data.chat.coreArtifactSpec
import cc.ptoe.messenger.data.chat.coreArtifactTool
import cc.ptoe.messenger.data.chat.coreImageSpec
import cc.ptoe.messenger.data.chat.coreImageTool
import cc.ptoe.messenger.data.chat.coreLearningSpec
import cc.ptoe.messenger.data.chat.coreLearningTool
import cc.ptoe.messenger.data.chat.coreSkillSpec
import cc.ptoe.messenger.data.local.artifact.RoomArtifactService
import cc.ptoe.messenger.data.local.room.LocalDatabase
import cc.ptoe.messenger.data.repository.LearningOwnership
import cc.ptoe.messenger.domain.mcp.McpManager
import cc.ptoe.messenger.domain.mcp.McpInventorySnapshot

/** One admission snapshot, bound once to the exact turn store which owns its invocations. */
internal class IsolatedCoreTools private constructor(
    val tools: CompositeKotlinTools,
    private val reference: StoreReference,
) {
    val preparer = KotlinProviderRequestPreparer(tools)
    fun bind(store: RoomKotlinTurnStore) = reference.bind(store)

    companion object {
        val supportedNames = setOf(coreLearningSpec.function.name, coreArtifactSpec.function.name, coreSkillSpec.function.name)
        val imageName: String get() = coreImageSpec.function.name

        suspend fun create(db: LocalDatabase, conversationId: String, learning: LearningOwnership,
            artifacts: RoomArtifactService, allowedNames: Set<String>, now: () -> Long,
            imageTool: IsolatedModelImageTool? = null, mcpManager: McpManager? = null,
            mcpInventory: McpInventorySnapshot? = null, mcpNames: Set<String> = emptySet()): IsolatedCoreTools {
            val supported = supportedNames + if (imageTool != null) setOf(imageName) else emptySet()
            require(allowedNames.all { it in supported }) { "Unsupported Core tool" }
            val reference = StoreReference()
            val components = mutableListOf<KotlinToolComponent>()
            if (coreLearningSpec.function.name in allowedNames) {
                val tool = coreLearningTool(db, conversationId, reference::get, learning, now)
                components += KotlinToolComponent(setOf(coreLearningSpec.function.name), tool, tool, tool)
            }
            if (coreArtifactSpec.function.name in allowedNames) {
                val tool = coreArtifactTool(db, conversationId, reference::get, artifacts, now)
                components += KotlinToolComponent(setOf(coreArtifactSpec.function.name), tool, tool, tool)
            }
            if (coreSkillSpec.function.name in allowedNames) {
                val tool = KotlinCoreSkillAdapter.create(db, conversationId, reference::get)
                components += KotlinToolComponent(setOf(coreSkillSpec.function.name), tool, tool, tool)
            }
            if (imageName in allowedNames) {
                val selected = checkNotNull(imageTool)
                val tool = coreImageTool(db, conversationId, reference::get, selected.service, selected.authorization)
                components += KotlinToolComponent(setOf(imageName), tool, tool, tool)
            }
            if (mcpManager != null) {
                val inventory = requireNotNull(mcpInventory) { "MCP inventory is unavailable" }
                val tool = KotlinMcpToolAdapter(db, conversationId, mcpManager, inventory, mcpNames, reference::get)
                components += KotlinToolComponent(mcpNames, tool, tool, tool)
            }
            return IsolatedCoreTools(CompositeKotlinTools(components), reference)
        }
    }

    private class StoreReference {
        private var store: RoomKotlinTurnStore? = null
        fun bind(value: RoomKotlinTurnStore) { check(store == null) { "Turn store already bound" }; store = value }
        fun get(): RoomKotlinTurnStore = checkNotNull(store) { "Turn store is not bound" }
    }
}
