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

import cc.ptoe.messenger.presentation.platform.AndroidContextHolder
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

@Volatile
private var enhancedRuntimeActive = false

actual suspend fun executeShellCommand(
    command: String,
    timeoutMs: Long,
    workingDir: String?,
    onOutput: ((String) -> Unit)?
): ShellResult {
    // Shell execution exists ONLY through the companion runtime (its legacy
    // SELinux domain can exec app data; the main app's cannot). Defensive
    // error result — agent tools are disabled and the terminal screen gates
    // on availability, so this branch should not be reached.
    val bridge = ShellRuntimeRegistry.bridge
        ?: return ShellResult(output = "Messenger Runtime companion app is not installed.", exitCode = -1)
    return bridge.execute(command, timeoutMs, workingDir, onOutput)
        ?: ShellResult(output = "Messenger Runtime companion app is not available.", exitCode = -1)
}

actual suspend fun ensureShellRuntime(): String {
    val bridge = ShellRuntimeRegistry.bridge
        ?: throw IllegalStateException("Messenger Runtime companion app is not installed.")
    return bridge.ensureRuntime()
        ?: throw IllegalStateException("Messenger Runtime companion app is not available.")
}

actual fun isEnhancedShellRuntimeActive(): Boolean = enhancedRuntimeActive

actual fun isShellRuntimeAvailable(): Boolean = ShellRuntimeRegistry.bridge?.isInstalled() == true

/** Shared workspace root for main-app file tools (the companion's shell runs here too). */
private object AgentWorkspace {
    fun path(): File = File(AndroidContextHolder.appContext.filesDir, "agent-runtime/workspace").apply { mkdirs() }
}

internal actual suspend fun executeWorkspaceOperation(operation: WorkspaceOperation): ToolExecutionResult =
    withContext(Dispatchers.IO) { WorkspaceFileOperations(AgentWorkspace.path()).execute(operation) }

private class WorkspaceFileOperations(private val root: File) {
    private val maxFileBytes = 4L * 1024 * 1024
    private val maxFilesScanned = 20_000

    fun execute(operation: WorkspaceOperation): ToolExecutionResult = try {
        when (operation) {
            is WorkspaceOperation.Glob -> glob(operation)
            is WorkspaceOperation.Grep -> grep(operation)
            is WorkspaceOperation.Read -> read(operation)
            is WorkspaceOperation.Edit -> edit(operation)
            is WorkspaceOperation.Create -> create(operation)
        }
    } catch (e: Exception) {
        ToolExecutionResult("Workspace operation failed: ${e.message ?: "I/O error"}", isError = true)
    }

    private fun glob(op: WorkspaceOperation.Glob): ToolExecutionResult {
        val matcher = globRegex(op.pattern).toRegex()
        val files = walkFiles().filter { matcher.matches(root.toPath().relativize(it).toString().replace('\\', '/')) }
            .take(op.maxResults + 1).toList()
        return boundedList(files.map { relative(it) }, op.maxResults, "No matching files.")
    }

    private fun grep(op: WorkspaceOperation.Grep): ToolExecutionResult {
        if (op.pattern.isEmpty()) return ToolExecutionResult("Search pattern cannot be empty.", isError = true)
        val base = resolve(op.path, mustExist = true)
        val fileMatcher = op.fileGlob?.let { globRegex(it).toRegex() }
        val regex = try {
            Pattern.compile(op.pattern, if (op.caseSensitive) 0 else Pattern.CASE_INSENSITIVE)
        } catch (e: PatternSyntaxException) {
            return ToolExecutionResult("Invalid regular expression: ${e.description}", isError = true)
        }
        val candidates = if (base.isDirectory) walkFiles(base) else sequenceOf(base.toPath())
        val results = mutableListOf<String>()
        for (file in candidates) {
            if (fileMatcher != null && !fileMatcher.matches(relative(file))) continue
            if (!Files.isRegularFile(file) || Files.size(file) > maxFileBytes) continue
            val lines = Files.readAllLines(file, StandardCharsets.UTF_8)
            lines.forEachIndexed { index, line ->
                if (regex.matcher(line).find()) results += "${relative(file)}:${index + 1}:$line"
            }
            if (results.size > op.maxResults) break
        }
        return boundedList(results, op.maxResults, "No matches.")
    }

    private fun read(op: WorkspaceOperation.Read): ToolExecutionResult {
        val file = resolve(op.path, mustExist = true)
        require(file.isFile) { "Path is not a file." }
        require(file.length() <= maxFileBytes) { "File exceeds the 4 MiB read limit." }
        val lines = file.readLines(Charsets.UTF_8)
        val start = (op.startLine - 1).coerceAtMost(lines.size)
        val end = (start + op.maxLines).coerceAtMost(lines.size)
        val content = lines.subList(start, end).mapIndexed { index, line -> "${start + index + 1}: $line" }.joinToString("\n")
        val suffix = if (end < lines.size) "\n(output truncated; ${lines.size - end} more lines)" else ""
        return ToolExecutionResult(content + suffix.ifEmpty { if (content.isEmpty()) "(empty file)" else "" })
    }

    private fun edit(op: WorkspaceOperation.Edit): ToolExecutionResult {
        require(op.oldText.isNotEmpty()) { "oldText cannot be empty." }
        val file = resolve(op.path, mustExist = true)
        require(file.isFile && file.length() <= maxFileBytes) { "File is missing, not a regular file, or exceeds 4 MiB." }
        val original = file.readText(Charsets.UTF_8)
        val matches = original.windowed(op.oldText.length).count { it == op.oldText }
        require(matches > 0) { "oldText was not found; no changes made." }
        require(op.replaceAll || matches == 1) { "oldText matched $matches times; set replaceAll=true to replace all." }
        val updated = if (op.replaceAll) original.replace(op.oldText, op.newText) else original.replaceFirst(op.oldText, op.newText)
        require(updated.toByteArray(Charsets.UTF_8).size <= maxFileBytes) { "Edited file exceeds 4 MiB." }
        file.writeText(updated, Charsets.UTF_8)
        return ToolExecutionResult("Updated ${relative(file.toPath())} (${if (op.replaceAll) matches else 1} replacement${if (matches == 1) "" else "s"}).")
    }

    private fun create(op: WorkspaceOperation.Create): ToolExecutionResult {
        val file = resolve(op.path, mustExist = false)
        require(!Files.isSymbolicLink(file.toPath())) { "Cannot write through a symbolic link." }
        require(op.content.toByteArray(Charsets.UTF_8).size <= maxFileBytes) { "Content exceeds 4 MiB." }
        file.parentFile?.mkdirs()
        if (!op.overwrite) {
            Files.write(file.toPath(), op.content.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } else {
            Files.write(file.toPath(), op.content.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        }
        return ToolExecutionResult("Created ${relative(file.toPath())}.")
    }

    private fun resolve(raw: String, mustExist: Boolean): File {
        require(raw.isNotBlank() && !raw.startsWith('/') && !raw.startsWith('\\') && !Regex("^[A-Za-z]:").containsMatchIn(raw)) { "Path must be relative to the workspace." }
        val normalized = root.toPath().resolve(raw).normalize()
        require(normalized.startsWith(root.toPath().normalize())) { "Path escapes the workspace." }
        val candidate = normalized.toFile()
        val canonicalRoot = root.canonicalFile.toPath()
        val canonical = if (mustExist) candidate.canonicalFile.toPath() else {
            val parent = (candidate.parentFile ?: root).canonicalFile.toPath()
            parent.resolve(candidate.name).normalize()
        }
        require(canonical.startsWith(canonicalRoot) && canonical != canonicalRoot) { "Path escapes the workspace." }
        if (mustExist) require(candidate.exists()) { "File does not exist." }
        return canonical.toFile()
    }

    private fun walkFiles(start: File = root): Sequence<Path> {
        val base = start.canonicalFile.toPath()
        return Files.walk(base).use { stream ->
            stream.filter { path -> path != base && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                .limit((maxFilesScanned + 1).toLong()).toList().asSequence()
        }.also { require(it.count() <= maxFilesScanned) { "Workspace scan exceeds $maxFilesScanned files." } }
    }

    private fun relative(path: Path) = root.canonicalFile.toPath().relativize(path.toFile().canonicalFile.toPath()).toString().replace('\\', '/')

    private fun boundedList(rows: List<String>, limit: Int, empty: String): ToolExecutionResult =
        ToolExecutionResult(if (rows.isEmpty()) empty else rows.take(limit).joinToString("\n") + if (rows.size > limit) "\n(results truncated at $limit)" else "")

    private fun globRegex(glob: String): String {
        require(glob.isNotBlank() && !glob.startsWith('/') && !glob.contains('\\')) { "Glob must be a relative forward-slash pattern." }
        require(glob.split('/').none { it == ".." }) { "Glob cannot traverse parent directories." }
        val out = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when {
                glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3 }
                glob.startsWith("**", i) -> { out.append(".*"); i += 2 }
                glob[i] == '*' -> { out.append("[^/]*"); i++ }
                glob[i] == '?' -> { out.append("[^/]"); i++ }
                else -> { if (glob[i] in ".()[]{}+$^|\\") out.append('\\'); out.append(glob[i]); i++ }
            }
        }
        return out.append('$').toString()
    }
}
