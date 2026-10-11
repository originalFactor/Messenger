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

package cc.ptoe.messenger.domain.usecase

import cc.ptoe.messenger.data.util.currentTimeMillis
import cc.ptoe.messenger.data.util.logW
import cc.ptoe.messenger.domain.model.Conversation
import cc.ptoe.messenger.domain.repository.ConversationRepository
import cc.ptoe.messenger.domain.repository.MessageRepository
import kotlinx.coroutines.flow.first

/**
 * 空会话自动清理：删除「建好后从未发过消息」的会话，避免点开一次聊天列表
 * 就多出一条空记录的堆积。
 *
 * 由聊天列表页在进入时触发（[cleanUp]），不做后台轮询。删除走
 * [ConversationRepository.delete]，因此与手动删除同路：云端墓碑标记、
 * 消息级联清理都照常发生。
 *
 * 两道豁免，避免误删用户仍有意图的会话：
 * 1. **宽限期**：`updatedAt` 距现在不足 [gracePeriodMillis] 的会话跳过 ——
 *    新建会话后用户要选 Agent、配模型、挑图片，这期间它一直是空的；
 * 2. **已命名**：标题被用户手动改过（不再是「新对话」占位）的会话跳过 ——
 *    改名是明确的保留意图（判定复用
 *    [ConversationTitleGenerator.isUntitledConversation]）。
 *
 * 会话属于哪个项目、绑定了哪个 Agent 都不影响判定：空就是空。
 */
class EmptyConversationCleaner(
    private val conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    private val gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MILLIS,
    private val now: () -> Long = ::currentTimeMillis
) {

    /**
     * 扫描全部会话并删除符合条件的空会话，返回实际删除的条数。
     *
     * [excluding] 是调用方要求保护的会话 id（例如双栏布局里右栏正在展示的
     * 会话），通常为空。
     */
    suspend fun cleanUp(excluding: Set<String> = emptySet()): Int {
        val deadline = now() - gracePeriodMillis
        val candidates = conversationRepository.getAll().first().filter { conversation ->
            conversation.id !in excluding &&
                conversation.updatedAt <= deadline &&
                ConversationTitleGenerator.isUntitledConversation(conversation.title) &&
                conversation.hasNoMessagesYet()
        }

        var deleted = 0
        for (conversation in candidates) {
            val removed = runCatching { conversationRepository.delete(conversation.id) }
                .onFailure { logW(TAG, "Failed to delete empty conversation ${conversation.id}", it) }
                .isSuccess
            if (removed) deleted++
        }
        return deleted
    }

    /**
     * 是否确实没有任何消息。`lastMessage` 非空即可直接判定有消息（发送时写入），
     * 省掉一次消息查询；纯图片消息的 `lastMessage` 是空串，仍需回查。
     */
    private suspend fun Conversation.hasNoMessagesYet(): Boolean {
        if (!lastMessage.isNullOrBlank()) return false
        return messageRepository.getByConversationId(id).first().isEmpty()
    }

    companion object {
        private const val TAG = "EmptyConversationCleaner"

        /** 宽限期：新建会话在这段时间内即使没有消息也不会被清理。 */
        const val DEFAULT_GRACE_PERIOD_MILLIS: Long = 5 * 60 * 1000L
    }
}
