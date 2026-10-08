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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** File-navigation and file-mutation tools for the Agent. Paths are relative
 * to the agent workspace unless absolute; the sandbox confines what the
 * operations can actually touch — arguments are never path-checked. */
class WorkspaceTool(
    private val operationName: String,
    /** 该项目的 workspace 绝对路径；相对路径按它解析。 */
    private val workspaceRoot: String
) : ChatTool {
    override val name: String = operationName

    override val description: String = when (name) {
        GLOB -> "Find files matching a glob such as **/*.kt, relative to the agent workspace."
        GREP -> "Fast full-workspace text search with ripgrep: a regular expression (Rust syntax) or fixed string across files; path scopes the search to a file or directory and defaults to the whole workspace."
        READ -> "Read a line range from a text file; paths are relative to the agent workspace unless absolute."
        EDIT -> "Replace exact text in one file; replacement must match exactly once unless replaceAll is true. Paths are relative to the agent workspace unless absolute."
        CREATE -> "Create a text file. Existing files are not overwritten unless overwrite is true. Paths are relative to the agent workspace unless absolute."
        else -> error("Unknown workspace tool: $operationName")
    }

    override val writeAccess: Boolean get() = name == EDIT || name == CREATE

    override val parametersJson: String = SCHEMAS.getValue(name)

    override suspend fun execute(argumentsJson: String): ToolExecutionResult {
        val json = try {
            Json.parseToJsonElement(argumentsJson) as? JsonObject
                ?: return invalidArguments()
        } catch (_: Exception) {
            return invalidArguments()
        }
        fun string(key: String): String? = (json[key] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        fun int(key: String, default: Int): Int? =
            (json[key] as? JsonPrimitive)?.intOrNull ?: default.takeIf { json[key] == null }
        fun bool(key: String, default: Boolean): Boolean? =
            (json[key] as? JsonPrimitive)?.booleanOrNull ?: default.takeIf { json[key] == null }

        val operation = when (name) {
            GLOB -> WorkspaceOperation.Glob(
                pattern = string("pattern") ?: return invalidArguments(),
                maxResults = int("maxResults", DEFAULT_RESULTS)?.coerceIn(1, MAX_RESULTS) ?: return invalidArguments()
            )
            GREP -> WorkspaceOperation.Grep(
                pattern = string("pattern") ?: return invalidArguments(),
                path = string("path") ?: ".",
                fileGlob = string("fileGlob"),
                caseSensitive = bool("caseSensitive", true) ?: return invalidArguments(),
                fixedString = bool("fixedString", false) ?: return invalidArguments(),
                maxResults = int("maxResults", DEFAULT_RESULTS)?.coerceIn(1, MAX_RESULTS) ?: return invalidArguments()
            )
            READ -> WorkspaceOperation.Read(
                path = string("path") ?: return invalidArguments(),
                startLine = int("startLine", 1)?.coerceAtLeast(1) ?: return invalidArguments(),
                maxLines = int("maxLines", DEFAULT_LINES)?.coerceIn(1, MAX_LINES) ?: return invalidArguments()
            )
            EDIT -> WorkspaceOperation.Edit(
                path = string("path") ?: return invalidArguments(),
                oldText = string("oldText") ?: return invalidArguments(),
                newText = string("newText") ?: return invalidArguments(),
                replaceAll = bool("replaceAll", false) ?: return invalidArguments()
            )
            CREATE -> WorkspaceOperation.Create(
                path = string("path") ?: return invalidArguments(),
                content = string("content") ?: return invalidArguments(),
                overwrite = bool("overwrite", false) ?: return invalidArguments()
            )
            else -> return ToolExecutionResult("Unknown workspace tool: $name", isError = true)
        }
        return executeWorkspaceOperation(operation, workspaceRoot)
    }

    private fun invalidArguments() = ToolExecutionResult("Invalid arguments for $name.", isError = true)

    companion object {
        const val GLOB = "glob"
        const val GREP = "grep"
        const val READ = "read"
        const val EDIT = "edit"
        const val CREATE = "create"
        const val DEFAULT_RESULTS = 200
        const val MAX_RESULTS = 1000
        const val DEFAULT_LINES = 200
        const val MAX_LINES = 2000

        private val SCHEMAS = mapOf(
            GLOB to """{"type":"object","properties":{"pattern":{"type":"string"},"maxResults":{"type":"integer","minimum":1,"maximum":1000}},"required":["pattern"]}""",
            GREP to """{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string"},"fileGlob":{"type":"string"},"caseSensitive":{"type":"boolean"},"fixedString":{"type":"boolean"},"maxResults":{"type":"integer","minimum":1,"maximum":1000}},"required":["pattern"]}""",
            READ to """{"type":"object","properties":{"path":{"type":"string"},"startLine":{"type":"integer","minimum":1},"maxLines":{"type":"integer","minimum":1,"maximum":2000}},"required":["path"]}""",
            EDIT to """{"type":"object","properties":{"path":{"type":"string"},"oldText":{"type":"string"},"newText":{"type":"string"},"replaceAll":{"type":"boolean"}},"required":["path","oldText","newText"]}""",
            CREATE to """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"},"overwrite":{"type":"boolean"}},"required":["path","content"]}"""
        )

        /** 五个工作区工具（glob/grep/read/edit/create），绑定到同一 workspace。 */
        fun allFor(workspaceRoot: String): List<WorkspaceTool> =
            listOf(GLOB, GREP, READ, EDIT, CREATE).map { WorkspaceTool(it, workspaceRoot) }
    }

}
