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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Workspace file operations served to the main app over AIDL. Paths are
 * relative to the workspace root unless absolute — arguments are never
 * path-checked. The sandbox is THIS app's own UID (the workspace lives in
 * this data directory, and the OS bounds what these operations can touch),
 * so reads go anywhere this app can read and writes anywhere it can write.
 */
internal object WorkspaceOps {
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_FILES_SCANNED = 20_000

    fun glob(root: File, pattern: String, maxResults: Int): ToolResult = guarded {
        require(pattern.isNotBlank()) { "Glob pattern cannot be empty." }
        val matcher = globRegex(pattern).toRegex()
        val files = walkFiles(root).filter { globMatches(matcher, root, it) }
            .take(maxResults + 1).toList()
        boundedList(files.map { relative(root, it) }, maxResults, "No matching files.")
    }

    fun grep(root: File, pattern: String, path: String, fileGlob: String?, caseSensitive: Boolean, fixedString: Boolean, maxResults: Int, rgBinary: File?): ToolResult = guarded {
        require(pattern.isNotEmpty()) { "Search pattern cannot be empty." }
        val rg = rgBinary?.takeIf { it.isFile && it.canExecute() }
            ?: return@guarded ToolResult("ripgrep is not available in the runtime.", isError = true)
        val base = resolve(root, path, mustExist = true)
        // Run rg with the workspace as cwd and a workspace-relative search
        // path so output paths arrive workspace-relative verbatim; absolute
        // roots outside the workspace keep absolute (forward-slashed) paths.
        val workspaceRoot = root.canonicalFile
        val insideWorkspace = base.canonicalFile.path.startsWith(workspaceRoot.path + File.separator)
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
            if (!caseSensitive) add("--ignore-case")
            if (fixedString) add("--fixed-strings")
            fileGlob?.let { add("-g"); add(it) }
            add("--")
            add(pattern)
            add(searchPath)
        }
        val process = ProcessBuilder(command)
            .directory(workspaceRoot)
            .redirectErrorStream(false)
            .start()
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
        val rows = mutableListOf<String>()
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                rows += if (fileRootPrefix != null) {
                    if (line.isNotEmpty()) "$fileRootPrefix:$line" else line
                } else {
                    // Row shape: `path\0line:text` — the NUL is the field
                    // separator, so colons inside the matched text survive.
                    val nul = line.indexOf('\u0000')
                    if (nul >= 0) {
                        normalizePath(line.substring(0, nul)) + ":" + line.substring(nul + 1)
                    } else {
                        normalizePath(line)
                    }
                }
                if (rows.size > maxResults) break
            }
        }
        val stderr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        val exit = process.waitFor()
        if (exit == 2) {
            return@guarded ToolResult(stderr.lineSequence().firstOrNull { it.isNotBlank() } ?: "ripgrep failed.", isError = true)
        }
        val truncated = rows.size > maxResults
        if (truncated) rows.removeAt(rows.lastIndex)
        val body = if (rows.isEmpty()) "No matches." else rows.joinToString("\n")
        ToolResult(body + if (truncated) "\n(results truncated at $maxResults)" else "", isError = false)
    }

    /** Normalizes row path separators to forward slashes. */
    private fun normalizePath(path: String): String = path.replace('\\', '/')

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

    /**
     * Resolves a path argument without confinement: absolute paths are used
     * as-is, relative paths resolve against the workspace root. The sandbox
     * (this app's UID) bounds what the operation can touch — never the
     * argument.
     */
    private fun resolve(root: File, raw: String, mustExist: Boolean): File {
        require(raw.isNotBlank()) { "Path cannot be empty." }
        val base = if (raw.startsWith('/')) File(raw).toPath() else root.toPath().resolve(raw)
        val candidate = base.normalize().toFile()
        if (mustExist) require(candidate.exists()) { "File does not exist." }
        return candidate
    }

    /** Glob matches the workspace-relative path, or the absolute path for absolute patterns. */
    private fun globMatches(matcher: Regex, root: File, path: Path): Boolean {
        val absolute = path.toString().replace('\\', '/')
        return matcher.matches(relative(root, path)) || matcher.matches(absolute)
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
