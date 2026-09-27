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

package cc.ptoe.messenger.domain.model

data class Agent(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val systemPrompt: String,
    val defaultModelId: String?,
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int?,
    val reasoningEffort: String? = null,
    val isDefault: Boolean = false,
    val followDefaultSystemPrompt: Boolean = false,
    val followDefaultModel: Boolean = false,
    val followDefaultTemperature: Boolean = false,
    val followDefaultTopP: Boolean = false,
    val followDefaultMaxTokens: Boolean = false,
    val followDefaultReasoningEffort: Boolean = false,
    val marketAgentId: String? = null,
    val marketAgentVersion: Long? = null,
    val marketAgentRole: String? = null,
    val role: String = ROLE_CHAT,
    /** 是否随请求向模型声明内置工具（终端等；仅当平台注册了工具时生效）。 */
    val toolsEnabled: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        /** 普通聊天 Agent（用户创建的默认角色）。 */
        const val ROLE_CHAT = "chat"

        /** 功能型角色：为对话生成标题（仅供内置标题智能体使用）。 */
        const val ROLE_TITLE = "title"

        /** 内置标题生成智能体的保留 ID，永不参与云同步。 */
        const val BUILTIN_TITLE_AGENT_ID = "builtin-title-agent"

        /**
         * 内置智能体的显示名。与「默认 Agent」同理按字面存库（isDefaultAgentBaseline
         * 等基线判断依赖字面比较），不做资源本地化。
         */
        const val BUILTIN_TITLE_AGENT_NAME = "标题生成"

        /**
         * 内置标题智能体的默认系统提示词。语言无关：要求用用户消息的语言、
         * 只回标题本身，具体约束由生成流程在调用侧再收紧。
         */
        const val BUILTIN_TITLE_AGENT_PROMPT =
            "You generate short conversation titles. Based on the messages you receive, " +
                "reply with a single concise title in the same language as the user's messages. " +
                "Reply with the title text only — no quotes, no trailing punctuation, no explanations."

        /** 构造内置标题生成智能体的种子实例（仅首次缺行时插入，之后不覆盖用户编辑）。 */
        fun builtinTitleAgent(now: Long): Agent = Agent(
            id = BUILTIN_TITLE_AGENT_ID,
            name = BUILTIN_TITLE_AGENT_NAME,
            systemPrompt = BUILTIN_TITLE_AGENT_PROMPT,
            defaultModelId = null,
            temperature = 0.3f,
            topP = 1.0f,
            maxTokens = null,
            isDefault = false,
            role = ROLE_TITLE,
            createdAt = now,
            updatedAt = now
        )
    }
}
