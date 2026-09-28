/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package cc.ptoe.messenger.domain.tool

/** Operations implemented by the platform-specific, workspace-confined file layer. */
sealed class WorkspaceOperation {
    data class Glob(val pattern: String, val maxResults: Int) : WorkspaceOperation()

    data class Grep(
        val pattern: String,
        val path: String,
        val fileGlob: String?,
        val caseSensitive: Boolean,
        val maxResults: Int
    ) : WorkspaceOperation()

    data class Read(val path: String, val startLine: Int, val maxLines: Int) : WorkspaceOperation()

    data class Edit(
        val path: String,
        val oldText: String,
        val newText: String,
        val replaceAll: Boolean
    ) : WorkspaceOperation()

    data class Create(val path: String, val content: String, val overwrite: Boolean) : WorkspaceOperation()
}

/** Executes an operation against the platform's app-private agent workspace. */
internal expect suspend fun executeWorkspaceOperation(operation: WorkspaceOperation): ToolExecutionResult
