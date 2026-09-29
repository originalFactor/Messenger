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

package cc.ptoe.messenger.runtime

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Workspace-confined file operations served to the main app over AIDL. The
 * workspace lives in THIS app's data directory (own UID), so the main app
 * cannot touch it directly — every operation is bounds-checked here.
 */
internal object WorkspaceOps {
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_FILES_SCANNED = 20_000

    fun glob(root: File, pattern: String, maxResults: Int): ToolResult = guarded {
        require(pattern.isNotBlank() && !pattern.startsWith('/') && !pattern.contains('\\')) {
            "Glob must be a relative forward-slash pattern."
        }
        require(pattern.split('/').none { it == ".." }) { "Glob cannot traverse parent directories." }
        val matcher = globRegex(pattern).toRegex()
        val files = walkFiles(root).filter { matcher.matches(root.toPath().relativize(it).toString().replace('\\', '/')) }
            .take(maxResults + 1).toList()
        boundedList(files.map { relative(root, it) }, maxResults, "No matching files.")
    }

    fun grep(root: File, pattern: String, path: String, fileGlob: String?, caseSensitive: Boolean, maxResults: Int): ToolResult = guarded {
        require(pattern.isNotEmpty()) { "Search pattern cannot be empty." }
        val base = resolve(root, path, mustExist = true)
        val fileMatcher = fileGlob?.let { globRegex(it).toRegex() }
        val regex = try {
            Pattern.compile(pattern, if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE)
        } catch (e: PatternSyntaxException) {
            return@guarded ToolResult("Invalid regular expression: ${e.description}", isError = true)
        }
        val candidates = if (base.isDirectory) walkFiles(base) else sequenceOf(base.toPath())
        val results = mutableListOf<String>()
        for (file in candidates) {
            if (fileMatcher != null && !fileMatcher.matches(relative(root, file))) continue
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) continue
            val lines = Files.readAllLines(file, StandardCharsets.UTF_8)
            lines.forEachIndexed { index, line ->
                if (regex.matcher(line).find()) results += "${relative(root, file)}:${index + 1}:$line"
            }
            if (results.size > maxResults) break
        }
        boundedList(results, maxResults, "No matches.")
    }

    fun read(root: File, path: String, startLine: Int, maxLines: Int): ToolResult = guarded {
        val file = resolve(root, path, mustExist = true)
        require(file.isFile) { "Path is not a file." }
        require(file.length() <= MAX_FILE_BYTES) { "File exceeds the 4 MiB read limit." }
        val lines = file.readLines(Charsets.UTF_8)
        val start = (startLine - 1).coerceAtMost(lines.size)
        val end = (start + maxLines).coerceAtMost(lines.size)
        val content = lines.subList(start, end).mapIndexed { index, line -> "${start + index + 1}: $line" }.joinToString("\n")
        val suffix = if (end < lines.size) "\n(output truncated; ${lines.size - end} more lines)" else ""
        ToolResult(content + suffix.ifEmpty { if (content.isEmpty()) "(empty file)" else "" }, isError = false)
    }

    fun edit(root: File, path: String, oldText: String, newText: String, replaceAll: Boolean): ToolResult = guarded {
        require(oldText.isNotEmpty()) { "oldText cannot be empty." }
        val file = resolve(root, path, mustExist = true)
        require(file.isFile && file.length() <= MAX_FILE_BYTES) { "File is missing, not a regular file, or exceeds 4 MiB." }
        val original = file.readText(Charsets.UTF_8)
        val matches = original.windowed(oldText.length).count { it == oldText }
        require(matches > 0) { "oldText was not found; no changes made." }
        require(replaceAll || matches == 1) { "oldText matched $matches times; set replaceAll=true to replace all." }
        val updated = if (replaceAll) original.replace(oldText, newText) else original.replaceFirst(oldText, newText)
        require(updated.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Edited file exceeds 4 MiB." }
        file.writeText(updated, Charsets.UTF_8)
        ToolResult("Updated ${relative(root, file.toPath())} (${if (replaceAll) matches else 1} replacement${if (matches == 1) "" else "s"}).", isError = false)
    }

    fun create(root: File, path: String, content: String, overwrite: Boolean): ToolResult = guarded {
        val file = resolve(root, path, mustExist = false)
        require(!Files.isSymbolicLink(file.toPath())) { "Cannot write through a symbolic link." }
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "Content exceeds 4 MiB." }
        file.parentFile?.mkdirs()
        if (!overwrite) {
            Files.write(file.toPath(), content.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } else {
            Files.write(file.toPath(), content.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        }
        ToolResult("Created ${relative(root, file.toPath())}.", isError = false)
    }

    private inline fun guarded(block: () -> ToolResult): ToolResult = try {
        block()
    } catch (e: Exception) {
        ToolResult(e.message ?: "I/O error", isError = true)
    }

    private fun resolve(root: File, raw: String, mustExist: Boolean): File {
        require(raw.isNotBlank() && !raw.startsWith('/') && !raw.startsWith('\\') && !Regex("^[A-Za-z]:").containsMatchIn(raw)) {
            "Path must be relative to the workspace."
        }
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

    private fun walkFiles(start: File): Sequence<Path> {
        val base = start.canonicalFile.toPath()
        return Files.walk(base).use { stream ->
            stream.filter { path -> path != base && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                .limit((MAX_FILES_SCANNED + 1).toLong()).toList().asSequence()
        }.also { require(it.count() <= MAX_FILES_SCANNED) { "Workspace scan exceeds $MAX_FILES_SCANNED files." } }
    }

    private fun relative(root: File, path: Path): String =
        root.canonicalFile.toPath().relativize(path.toFile().canonicalFile.toPath()).toString().replace('\\', '/')

    private fun boundedList(rows: List<String>, limit: Int, empty: String): ToolResult =
        ToolResult(
            if (rows.isEmpty()) empty else rows.take(limit).joinToString("\n") + if (rows.size > limit) "\n(results truncated at $limit)" else "",
            isError = false
        )

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
