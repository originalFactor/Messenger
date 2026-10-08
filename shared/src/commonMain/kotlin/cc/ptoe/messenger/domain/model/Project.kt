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
 * A project: a named workspace owning a set of conversations.
 *
 * Only a conversation that belongs to a project can call the workspace-bound
 * tools (the terminal and glob/grep/read/edit/create); a plain conversation
 * declares no tools at all.
 */
data class Project(
    val id: String,
    val name: String,
    /** Absolute workspace directory the project's tools operate in. */
    val workspace: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Directory name for an auto-created workspace: the normalized project name. */
    val workspaceFolderName: String get() = normalizeWorkspaceFolderName(name)
}

/**
 * Normalizes a project name into a single safe directory name: lowercase,
 * with every run of characters that is unsafe in a path (separators, spaces,
 * punctuation) collapsed to a single `-`, trimmed. Returns `project` when the
 * name normalizes to nothing (e.g. a name made only of punctuation).
 */
fun normalizeWorkspaceFolderName(name: String): String {
    val slug = buildString {
        var pendingSeparator = false
        for (ch in name.trim().lowercase()) {
            if (ch.isLetterOrDigit() && ch.code < 128) {
                if (pendingSeparator && isNotEmpty()) append('-')
                pendingSeparator = false
                append(ch)
            } else {
                pendingSeparator = true
            }
        }
    }
    return slug.ifEmpty { "project" }
}