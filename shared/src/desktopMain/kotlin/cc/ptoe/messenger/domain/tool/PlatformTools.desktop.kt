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

package cc.ptoe.messenger.domain.tool

actual fun createBuiltinChatTools(workspaceRoot: String?): List<ChatTool> =
    if (workspaceRoot == null) emptyList()
    else listOf(TerminalTool(workspaceRoot = workspaceRoot)) + WorkspaceTool.allFor(workspaceRoot)

actual suspend fun temporaryChatWorkspace(conversationId: String): String? {
    val key = java.security.MessageDigest.getInstance("SHA-256")
        .digest(conversationId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    val dir = java.io.File(java.io.File(System.getProperty("java.io.tmpdir"), "messenger-chat-workspaces"), key)
    check(dir.isDirectory || dir.mkdirs()) { "Could not create temporary chat workspace." }
    return dir.absolutePath
}

/** Desktop always ships the shell + workspace tool implementations. */
actual fun builtinChatToolsSupported(): Boolean = true
