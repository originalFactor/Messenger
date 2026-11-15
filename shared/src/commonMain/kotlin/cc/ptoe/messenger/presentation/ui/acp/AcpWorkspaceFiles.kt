/*
 * Copyright 2026 originalFactor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package cc.ptoe.messenger.presentation.ui.acp

import cc.ptoe.messenger.domain.tool.WorkspaceOperation
import cc.ptoe.messenger.domain.tool.executeWorkspaceOperation

/** Finds workspace-relative paths without interpolating user input into a shell command. */
internal suspend fun searchAcpWorkspaceFiles(workspace: String, query: String): List<String> {
    if (workspace.isBlank() || query.length > 200 || query.startsWith('/') ||
        query.any { it == '*' || it == '?' || it == '\\' || it.code < 32 || it.code == 127 } ||
        query.split('/').any { it == ".." }) return emptyList()

    val limit = 100
    val result = executeWorkspaceOperation(WorkspaceOperation.Glob("**/*$query*", limit), workspace)
    if (result.isError) throw IllegalStateException(result.output)
    if (result.output == "No matching files.") return emptyList()
    return result.output.lineSequence()
        .filter { path ->
            path.isNotBlank() && !path.startsWith('/') && !path.startsWith("(results truncated at ") &&
                path.split('/').none { it == ".." || it == ".git" } &&
                path.contains(query, ignoreCase = false)
        }
        .distinct().sorted().toList()
}
