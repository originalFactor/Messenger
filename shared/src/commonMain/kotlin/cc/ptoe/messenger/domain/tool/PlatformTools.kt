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

package cc.ptoe.messenger.domain.tool

/**
 * Platform-built-in chat tools bound to [workspaceRoot], the owning project's
 * workspace directory. `null` means the conversation belongs to no project:
 * there is no working directory, so NO tool is registered (in particular not
 * the workspace-bound ones) rather than falling back to a default.
 */
expect fun createBuiltinChatTools(workspaceRoot: String?): List<ChatTool>
