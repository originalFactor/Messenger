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
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
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
            listFiles().map { it to relative(it) }.filter { matcher.matches(it.second) }
                .take(op.maxResults + 1).map { it.second }.toList().result(op.maxResults, "No matching files.")
        }
        is WorkspaceOperation.Grep -> grep(op)
        is WorkspaceOperation.Read -> read(op)
        is WorkspaceOperation.Edit -> edit(op)
        is WorkspaceOperation.Create -> create(op)
    }

    private fun grep(op: WorkspaceOperation.Grep): ToolExecutionResult {
        require(op.pattern.isNotEmpty()) { "Search pattern cannot be empty." }
        val base = resolve(op.path, true)
        val filePattern = op.fileGlob?.let { globRegex(it).toRegex() }
        val regex = try { Pattern.compile(op.pattern, if (op.caseSensitive) 0 else Pattern.CASE_INSENSITIVE) }
        catch (e: PatternSyntaxException) { return ToolExecutionResult("Invalid regular expression: ${e.description}", true) }
        val matches = (if (Files.isDirectory(base)) listFiles(base.toFile()) else sequenceOf(base)).flatMap { path ->
            if (filePattern != null && !filePattern.matches(relative(path))) return@flatMap emptySequence()
            if (Files.size(path) > maxBytes) return@flatMap emptySequence()
            Files.readAllLines(path, StandardCharsets.UTF_8).asSequence().mapIndexedNotNull { index, line ->
                if (regex.matcher(line).find()) "${relative(path)}:${index + 1}:$line" else null
            }
        }.take(op.maxResults + 1).toList()
        return matches.result(op.maxResults, "No matches.")
    }

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
        require(!Files.isSymbolicLink(file.toPath())) { "Cannot write through a symbolic link." }
        val bytes = op.content.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "Content exceeds 4 MiB." }
        file.parentFile?.mkdirs()
        if (op.overwrite) Files.write(file.toPath(), bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        else Files.write(file.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        return ToolExecutionResult("Created ${relative(file.toPath())}.")
    }

    private fun resolve(raw: String, exists: Boolean): Path {
        require(raw.isNotBlank() && !raw.startsWith('/') && !raw.startsWith('\\') && !Regex("^[A-Za-z]:").containsMatchIn(raw)) { "Path must be relative to the workspace." }
        val path = rootPath.resolve(raw).normalize()
        require(path.startsWith(rootPath) && path != rootPath) { "Path escapes the workspace." }
        val canonical = if (exists) path.toFile().canonicalFile.toPath() else {
            val parent = (path.parent?.toFile() ?: root).canonicalFile.toPath()
            parent.resolve(path.fileName).normalize()
        }
        require(canonical.startsWith(rootPath) && canonical != rootPath) { "Path escapes the workspace." }
        if (exists) require(Files.exists(canonical)) { "Path does not exist." }
        return canonical
    }

    private fun listFiles(base: File = root): Sequence<Path> {
        val path = base.canonicalFile.toPath()
        return Files.walk(path).use { it.filter { item -> item != path && Files.isRegularFile(item, LinkOption.NOFOLLOW_LINKS) }.limit(20_001).toList() }.asSequence()
    }

    private fun relative(path: Path) = rootPath.relativize(path.toFile().canonicalFile.toPath()).toString().replace('\\', '/')

    private fun globRegex(glob: String): String {
        require(glob.isNotBlank() && !glob.startsWith('/') && !glob.contains('\\') && glob.split('/').none { it == ".." }) { "Glob must be a workspace-relative pattern." }
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
