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

/**
 * OpenAI-compatible multimodal content part. The supported set is text and
 * image_url, which is what every LLM that supports vision expects.
 *
 * Image sources are stored as [MessageImage] so the domain layer does not
 * depend on Android [android.net.Uri] or the Coil API.
 */
sealed class ContentPart {
    data class Text(val text: String) : ContentPart()

    data class Image(val image: MessageImage) : ContentPart()

    /**
     * assistant 消息上的工具调用记录（[MessageRole.TOOL] 的配对请求来源）。
     * [arguments] 是模型给出的原始 JSON 参数字符串。
     */
    data class ToolCall(
        val callId: String,
        val name: String,
        val arguments: String
    ) : ContentPart()

    /**
     * role=TOOL 结果消息上的执行结果；请求时回显为 `role:"tool"` +
     * `tool_call_id`。 [isError] 仅用于 UI 状态着色，请求侧不区分。
     */
    data class ToolResult(
        val callId: String,
        val name: String,
        val output: String,
        val isError: Boolean = false
    ) : ContentPart()
}

/**
 * Image payload for a [ContentPart.Image]. We keep both variants so the
 * upstream API can be sent data: URIs and the local UI can load the cached
 * file path.
 */
data class MessageImage(
    /**
     * Data URI (`data:image/png;base64,...`) that can be sent straight to
     * the OpenAI-compatible chat API. Stored at send time so the message
     * can be persisted without re-encoding the file.
     */
    val dataUri: String,
    /**
     * Absolute path to a cached copy of the original bitmap inside the
     * app's private files directory. UI uses this through Coil so we
     * never need storage permissions at render time.
     */
    val localPath: String
)
