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

package cc.ptoe.messenger.presentation.utils

/**
 * 剥离 AI 回复中的思考块（think/thinking 标签包裹的内容），用于聊天列表预览等纯文本场景。
 * 兼容带属性/大小写混合的标签、thinking 变体，以及流式中断导致的未闭合 think 块。
 * 语义：
 *  - 移除所有已闭合的 think 块（不限于开头 — reasoning_content 流中思考与正文可能交替出现）；
 *  - 移除末尾未闭合的 think 块（从开标签到结尾全部视为思考内容，流式进行中即为该状态）；
 *  - 结果 trim 后返回（可能为空字符串，调用方需处理空白回退）。
 * 使用 [\\s\\S] 代替 . 以可靠匹配跨行内容，不依赖 dotall 标志。
 */
fun stripThinkBlock(content: String): String {
    val closedBlock = Regex(
        "<think(?:ing)?(?:\\s[^>]*)?>[\\s\\S]*?</think(?:ing)?>",
        RegexOption.IGNORE_CASE,
    )
    val unclosedTailBlock = Regex(
        "<think(?:ing)?(?:\\s[^>]*)?>[\\s\\S]*$",
        RegexOption.IGNORE_CASE,
    )
    return content
        .replace(closedBlock, "")
        .replace(unclosedTailBlock, "")
        .trim()
}

/**
 * 从 content 中提取首个 think 块的内部文本和剩余正文。
 * 用于将显示用的 `<think>` 标签还原为 `reasoning_content` 字段发送给 API。
 *
 * @return Pair(reasoningContent, remainingContent)
 */
fun extractThinkContent(content: String): Pair<String?, String> {
    val regex = Regex("^<think(?:ing)?>([\\s\\S]*?)</think(?:ing)?>\\s*")
    val match = regex.find(content)
    return if (match != null) {
        val reasoning = match.groupValues[1]
        val remaining = content.substring(match.range.last + 1).trimStart()
        Pair(reasoning, remaining)
    } else {
        Pair(null, content)
    }
}
