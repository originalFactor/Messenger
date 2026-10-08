/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.ptoe.messenger.data.repository

import cc.ptoe.messenger.core.CoreBridge
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.domain.repository.AgentRepository
import cc.ptoe.messenger.domain.repository.CurrentAgentRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

import cc.ptoe.messenger.data.util.currentTimeMillis

@OptIn(ExperimentalCoroutinesApi::class)
class RustCurrentAgentRepository(
    private val coreBridge: CoreBridge,
    private val agentRepository: AgentRepository
) : CurrentAgentRepository {

    private val version = MutableStateFlow(0L)

    init {
        coreBridge.subscribe { kind, ids ->
            if ((kind == "Kv" && ids.contains("current_agent_id")) || kind == "All" || kind == "Other") {
                version.value = currentTimeMillis()
            }
        }
    }

    override val currentAgentId: Flow<String?> = version.map {
        coreBridge.kvGet("current_agent_id")?.takeIf { it.isNotBlank() }
    }

    override val currentAgent: Flow<Agent?> = currentAgentId.flatMapLatest { id ->
        if (id == null) {
            agentRepository.getDefaultAgent()
        } else {
            agentRepository.getById(id)
        }
    }

    override suspend fun setCurrentAgentId(agentId: String?) {
        if (agentId == null) {
            coreBridge.kvDelete("current_agent_id")
        } else {
            coreBridge.kvSet("current_agent_id", agentId)
        }
        version.value = currentTimeMillis()
    }
}
