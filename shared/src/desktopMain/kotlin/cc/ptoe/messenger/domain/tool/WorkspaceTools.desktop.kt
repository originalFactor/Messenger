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

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val agentWorkspace: File by lazy {
    File(System.getProperty("user.home"), ".messenger/agent-runtime/workspace").apply { mkdirs() }
}

internal actual suspend fun executeWorkspaceOperation(operation: WorkspaceOperation): ToolExecutionResult =
    withContext(Dispatchers.IO) {
        try {
            DesktopWorkspaceFiles(agentWorkspace).execute(operation)
        } catch (e: Exception) {
            ToolExecutionResult("Workspace operation failed: ${e.message ?: "I/O error"}", isError = true)
        }
    }

private class DesktopWorkspaceFiles(private val root: File) {
    private val rootPath = root.canonicalFile.toPath()
    private val maxBytes = 4L * 1024 * 1024

    fun execute(op: WorkspaceOperation): ToolExecutionResult = when (op) {
        is WorkspaceOperation.Glob -> {
            val matcher = globRegex(op.pattern).toRegex()
            listFiles().filter { globMatches(matcher, it) }
                .map { relative(it) }
                .take(op.maxResults + 1).toList().result(op.maxResults, "No matching files.")
        }
        is WorkspaceOperation.Grep -> grep(op)
        is WorkspaceOperation.Read -> read(op)
        is WorkspaceOperation.Edit -> edit(op)
        is WorkspaceOperation.Create -> create(op)
    }

    private fun grep(op: WorkspaceOperation.Grep): ToolExecutionResult {
        require(op.pattern.isNotEmpty()) { "Search pattern cannot be empty." }
        val rg = DesktopRipgrep.resolve()
            ?: return ToolExecutionResult("ripgrep is not available: install `rg` on PATH or reinstall the app.", true)
        val base = resolve(op.path, true).toFile().canonicalFile
        val workspaceRoot = agentWorkspace.canonicalFile
        // Inside the workspace: run rg with the workspace as cwd and a
        // workspace-relative search path so output paths arrive
        // workspace-relative verbatim. Outside it: absolute root, absolute
        // output paths (rewritten to forward slashes below).
        val insideWorkspace = base.path.startsWith(workspaceRoot.path + File.separator)
        val searchPath = if (insideWorkspace) {
            workspaceRoot.toPath().relativize(base.toPath()).toString().replace('\\', '/').ifEmpty { "." }
        } else {
            base.absolutePath.replace('\\', '/')
        }
        val command = buildList {
            add(rg.absolutePath)
            add("--no-heading")
            add("-n")
            // NUL terminates the path field so a colon inside a matched line
            // stays part of the text: `--no-heading -n` prints
            // `path:line:text`, which is ambiguous when the text contains a
            // colon (every later ':' then reads as a field separator).
            add("--null")
            add("--no-config")
            add("--hidden")
            add("--no-ignore")
            add("--path-separator")
            add("/")
            add("-g")
            add("!.git")
            if (!op.caseSensitive) add("--ignore-case")
            if (op.fixedString) add("--fixed-strings")
            op.fileGlob?.let { add("-g"); add(it) }
            add("--")
            add(op.pattern)
            add(searchPath)
        }
        val process = try {
            ProcessBuilder(command).directory(workspaceRoot).redirectErrorStream(false).start()
        } catch (e: Exception) {
            return ToolExecutionResult("Failed to start ripgrep: ${e.message}", true)
        }
        // When the search root is a single FILE, rg prints `line:text`
        // without any filename — prefix the workspace-relative file so rows
        // keep the contract ("dir/a.txt:2:...").
        val fileRootPrefix = if (base.isFile) {
            if (insideWorkspace) {
                workspaceRoot.toPath().relativize(base.toPath()).toString().replace('\\', '/')
            } else {
                base.absolutePath.replace('\\', '/')
            }
        } else {
            null
        }
        val p = process
        try {
            val rows = mutableListOf<String>()
            p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    rows += if (fileRootPrefix != null) {
                        // Row shape: `line:text` (rg omits the filename for a
                        // single-file search root).
                        if (line.isNotEmpty()) "$fileRootPrefix:$line" else line
                    } else {
                        // Row shape: `path\0line:text` — the NUL is the field
                        // separator, so colons inside the matched text survive.
                        val nul = line.indexOf('\u0000')
                        if (nul >= 0) {
                            normalizePath(line.substring(0, nul)) + ":" + line.substring(nul + 1)
                        } else {
                            line
                        }
                    }
                    if (rows.size > op.maxResults) break
                }
            }
            val stderr = p.errorStream.bufferedReader(Charsets.UTF_8).readText()
            val exit = p.waitFor()
            if (exit == 2) {
                return ToolExecutionResult(stderr.lineSequence().firstOrNull { it.isNotBlank() } ?: "ripgrep failed.", true)
            }
            val truncated = rows.size > op.maxResults
            if (truncated) rows.removeAt(rows.lastIndex)
            val body = if (rows.isEmpty()) "No matches." else rows.joinToString("\n")
            return ToolExecutionResult(body + if (truncated) "\n(results truncated at ${op.maxResults})" else "", isError = false)
        } finally {
            p.destroyForcibly()
        }
    }

    /**
     * rg with the workspace cwd emits relative paths inside the workspace
     * and absolute paths (under `--path-separator /`) for absolute roots
     * outside it. Row paths are normalized to forward slashes.
     */
    private fun normalizePath(path: String): String = path.replace('\\', '/')

    private fun read(op: WorkspaceOperation.Read): ToolExecutionResult {
        val file = resolve(op.path, true).toFile()
        require(file.isFile && file.length() <= maxBytes) { "Path is not a regular text file or exceeds 4 MiB." }
        val lines = file.readLines(Charsets.UTF_8)
        val from = (op.startLine - 1).coerceAtMost(lines.size)
        val until = (from + op.maxLines).coerceAtMost(lines.size)
        val body = lines.subList(from, until).mapIndexed { i, line -> "${from + i + 1}: $line" }.joinToString("\n")
        return ToolExecutionResult(body.ifEmpty { "(empty file)" } + if (until < lines.size) "\n(output truncated; ${lines.size - until} more lines)" else "")
    }

    private fun edit(op: WorkspaceOperation.Edit): ToolExecutionResult {
        require(op.oldText.isNotEmpty()) { "oldText cannot be empty." }
        val file = resolve(op.path, true).toFile()
        require(file.isFile && file.length() <= maxBytes) { "File is missing, not a regular file, or exceeds 4 MiB." }
        val source = file.readText(Charsets.UTF_8)
        val count = source.windowed(op.oldText.length).count { it == op.oldText }
        require(count > 0) { "oldText was not found; no changes made." }
        require(op.replaceAll || count == 1) { "oldText matched $count times; set replaceAll=true to replace all." }
        val updated = if (op.replaceAll) source.replace(op.oldText, op.newText) else source.replaceFirst(op.oldText, op.newText)
        require(updated.toByteArray(Charsets.UTF_8).size <= maxBytes) { "Edited file exceeds 4 MiB." }
        file.writeText(updated, Charsets.UTF_8)
        return ToolExecutionResult("Updated ${relative(file.toPath())}.")
    }

    private fun create(op: WorkspaceOperation.Create): ToolExecutionResult {
        val file = resolve(op.path, false).toFile()
        val bytes = op.content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "Content exceeds 4 MiB." }
        file.parentFile?.mkdirs()
        if (op.overwrite) Files.write(file.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        else Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return ToolExecutionResult("Created ${relative(file.toPath())}.")
    }

    /** Resolves a path argument without confinement: absolute paths are used
     * as-is, relative paths resolve against the workspace root. The user
     * process is the sandbox — arguments are never path-checked. */
    private fun resolve(raw: String, exists: Boolean): Path {
        require(raw.isNotBlank()) { "Path cannot be empty." }
        val base = if (Path.of(raw).isAbsolute) Path.of(raw) else rootPath.resolve(raw)
        val candidate = base.normalize()
        if (exists) require(Files.exists(candidate)) { "Path does not exist." }
        return candidate
    }

    /** Glob matches the workspace-relative path, or the absolute path for absolute patterns. */
    private fun globMatches(matcher: Regex, path: Path): Boolean {
        val absolute = path.toString().replace('\\', '/')
        return matcher.matches(relative(path)) || matcher.matches(absolute)
    }

    private fun listFiles(base: File = root): Sequence<Path> {
        val path = base.canonicalFile.toPath()
        return Files.walk(path).use { it.filter { item -> item != path && Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS) }.limit(20_001).toList() }.asSequence()
    }

    private fun relative(path: Path): String = try {
        rootPath.relativize(path.toFile().canonicalFile.toPath()).toString().replace('\\', '/')
    } catch (_: IllegalArgumentException) {
        // 不同的 Windows 盘符无法 relativize，直接用绝对路径展示
        path.toFile().canonicalFile.toPath().toString().replace('\\', '/')
    }

    private fun globRegex(glob: String): String {
        val regex = StringBuilder("^")
        var i = 0
        while (i < glob.length) when {
            glob.startsWith("**/", i) -> { regex.append("(?:.*/)?"); i += 3 }
            glob.startsWith("**", i) -> { regex.append(".*"); i += 2 }
            glob[i] == '*' -> { regex.append("[^/]*"); i++ }
            glob[i] == '?' -> { regex.append("[^/]"); i++ }
            else -> { if (glob[i] in ".()[]{}+$^|\\") regex.append('\\'); regex.append(glob[i++]) }
        }
        return regex.append('$').toString()
    }

    private fun List<String>.result(limit: Int, empty: String) = ToolExecutionResult(
        if (isEmpty()) empty else take(limit).joinToString("\n") + if (size > limit) "\n(results truncated at $limit)" else ""
    )
}
