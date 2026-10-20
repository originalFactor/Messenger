/*
 * Copyright 2026 originalFactor
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

package cc.ptoe.messenger.presentation.utils

import androidx.compose.runtime.Composable
import cc.ptoe.messenger.domain.model.Agent
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.agent_builtin_default_name
import cc.ptoe.messenger.generated.resources.agent_builtin_title_name
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** 默认 Agent 可能被写进库的种子字面值（含历史英文值）。 */
private val DEFAULT_AGENT_NAME_LITERALS = setOf(Agent.DEFAULT_AGENT_NAME, "Default Agent")

/** 内置标题生成 Agent 可能被写进库的种子字面值（含历史英文值）。 */
private val TITLE_AGENT_NAME_LITERALS = setOf(Agent.BUILTIN_TITLE_AGENT_NAME, "Title Generator")

/**
 * 内置 Agent 的种子名称按字面存库（云同步与「是否已有本地数据」的基线判断都按行
 * 判定，不依赖名称），但**展示**时跟随系统语言：用户没有改过名的内置 Agent 显示
 * 本地化名称，改过名的照原样显示。
 *
 * 判定同时要求「内置身份」和「种子字面值」，因此用户重命名内置 Agent 之后不再被
 * 本地化；把标题生成角色转移给自建 Agent 时，那个 Agent 保留自己的名字（只有保留
 * ID 的内置标题智能体才本地化）。
 */
fun builtinAgentNameResource(
    id: String,
    isDefault: Boolean,
    name: String
): StringResource? = when {
    isDefault && name in DEFAULT_AGENT_NAME_LITERALS -> Res.string.agent_builtin_default_name
    id == Agent.BUILTIN_TITLE_AGENT_ID && name in TITLE_AGENT_NAME_LITERALS ->
        Res.string.agent_builtin_title_name
    else -> null
}

/** 内置 Agent 的本地化名称；非内置或已被重命名时返回 null。 */
fun Agent.builtinDisplayNameResource(): StringResource? =
    builtinAgentNameResource(id = id, isDefault = isDefault, name = name)

/** 展示用名称（Composable 上下文）。 */
@Composable
fun Agent.displayName(): String =
    builtinDisplayNameResource()?.let { stringResource(it) } ?: name

/**
 * 同上，但按字段判定 —— 供只持有 id / `isDefault` / `name` 的表单状态
 * （`AgentEditUiState`，新建时 id 为 null）使用。
 */
@Composable
fun builtinAgentDisplayName(id: String?, isDefault: Boolean, name: String): String =
    builtinAgentNameResource(id = id.orEmpty(), isDefault = isDefault, name = name)
        ?.let { stringResource(it) } ?: name

/** 展示用名称（挂起上下文：ViewModel、平台服务等非 Composable 代码）。 */
suspend fun Agent.resolvedDisplayName(): String =
    builtinDisplayNameResource()?.let { getString(it) } ?: name

/**
 * Agent id → 展示用名称。供选择器之类的非 Composable `itemLabel` 回调使用
 * （在 Composable 作用域里一次性解析，避免在普通 lambda 里调用 `stringResource`）。
 */
@Composable
fun agentDisplayNames(agents: List<Agent>): Map<String, String> =
    agents.associate { it.id to it.displayName() }
