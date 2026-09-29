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

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Result of one shell command executed by the runtime. */
data class RuntimeShellResult(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean = false
)

/**
 * Installs and executes the pinned Termux bootstrap. Lives in the companion
 * runtime app whose OWN UID + targetSdk 28 keep the process in the legacy
 * untrusted_app_27 SELinux domain (execute/execute_no_trans on
 * app_data_file intact) — the main app's targetSdk-29+ domain is blocked by
 * the Android 10+ W^X rule, and a sharedUserId with the main app would pull
 * this process into it too (PackageManager derives the shared user's seInfo
 * from the highest targetSdk member at install time).
 *
 * The runtime and workspace live in THIS app's files dir; the main app
 * reaches the workspace only through the AIDL workspace operations.
 */
internal object TermuxRuntime {
    private const val ASSET_NAME = "agent-runtime/bootstrap.zip"
    private const val VERSION = "bootstrap-2026.09.27-r1+apt.android-7"
    private const val MARKER_NAME = ".messenger-runtime.properties"
    private const val SYMLINKS_NAME = "SYMLINKS.txt"
    private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    private const val MAX_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
    private const val MAX_OUTPUT_BYTES = 1_000_000
    private const val TERMUX_EXEC_LIBRARY = "lib/libtermux-exec.so"

    private val installMutex = Mutex()

    private class RuntimePaths(val prefix: File, val workspace: File, val shell: File)

    private fun baseDir(context: Context): File = File(context.filesDir, "agent-runtime")

    /** Workspace root (created on demand; no bootstrap install required). */
    fun workspace(context: Context): File =
        File(baseDir(context), "workspace").apply { mkdirs() }

    suspend fun ensureWorkspace(context: Context): String = withContext(Dispatchers.IO) {
        installMutex.withLock { ensureInstalled(context) }.workspace.absolutePath
    }

    suspend fun workspaceGlob(context: Context, pattern: String, maxResults: Int): ToolResult =
        withContext(Dispatchers.IO) { WorkspaceOps.glob(workspace(context), pattern, maxResults) }

    suspend fun workspaceGrep(
        context: Context,
        pattern: String,
        path: String,
        fileGlob: String?,
        caseSensitive: Boolean,
        maxResults: Int
    ): ToolResult = withContext(Dispatchers.IO) {
        WorkspaceOps.grep(workspace(context), pattern, path, fileGlob, caseSensitive, maxResults)
    }

    suspend fun workspaceRead(context: Context, path: String, startLine: Int, maxLines: Int): ToolResult =
        withContext(Dispatchers.IO) { WorkspaceOps.read(workspace(context), path, startLine, maxLines) }

    suspend fun workspaceEdit(
        context: Context,
        path: String,
        oldText: String,
        newText: String,
        replaceAll: Boolean
    ): ToolResult = withContext(Dispatchers.IO) {
        WorkspaceOps.edit(workspace(context), path, oldText, newText, replaceAll)
    }

    suspend fun workspaceCreate(context: Context, path: String, content: String, overwrite: Boolean): ToolResult =
        withContext(Dispatchers.IO) { WorkspaceOps.create(workspace(context), path, content, overwrite) }

    suspend fun execute(
        context: Context,
        command: String,
        timeoutMs: Long,
        workingDir: String?,
        onOutput: ((String) -> Unit)?
    ): RuntimeShellResult {
        val runtime = try {
            withContext(Dispatchers.IO) { installMutex.withLock { ensureInstalled(context) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RuntimeShellResult(
                output = "Shell runtime is unavailable: ${e.message ?: "installation failed"}",
                exitCode = -1
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                val workspace = runtime.workspace
                val directory = workingDir?.let(::File)?.takeIf { it.isDirectory } ?: workspace
                val process = startProcess(runtime, command, directory, workspace)
                try {
                    coroutineScope {
                        val stdout = async { drain(process.inputStream, onOutput) }
                        val stderr = async { drain(process.errorStream, onOutput) }
                        val exited = runInterruptible { process.waitFor(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS) }
                        if (!exited) {
                            terminate(process)
                            runCatching { process.inputStream.close() }
                            runCatching { process.errorStream.close() }
                            stdout.cancelAndJoin()
                            stderr.cancelAndJoin()
                            RuntimeShellResult(
                                output = "Command timed out after ${timeoutMs / 1000} seconds and was terminated.",
                                exitCode = -1,
                                timedOut = true
                            )
                        } else {
                            val output = buildString {
                                append(stdout.await())
                                val error = stderr.await()
                                if (error.isNotEmpty()) {
                                    if (isNotEmpty()) append('\n')
                                    append(error)
                                }
                            }
                            RuntimeShellResult(output = output, exitCode = process.exitValue())
                        }
                    }
                } finally {
                    terminate(process)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RuntimeShellResult(output = "Failed to execute shell command: ${e.message ?: "exec failed"}", exitCode = -1)
        }
    }

    private fun ensureInstalled(context: Context): RuntimePaths {
        val abi = supportedAbi()
            ?: error("This device ABI is not supported by the packaged runtime.")
        val base = baseDir(context)
        val prefix = File(base, "runtime-$abi")
        val workspace = workspace(context)
        if (isValid(prefix, abi)) {
            return RuntimePaths(prefix, workspace, File(prefix, "bin/sh"))
        }

        base.mkdirs()
        val staging = File(base, ".runtime-$abi-${UUID.randomUUID()}")
        val backup = File(base, ".runtime-$abi-backup")
        try {
            extractBootstrap(context.assets.open(ASSET_NAME), staging, abi)
            writeMarker(staging, abi)
            if (backup.exists()) backup.deleteRecursively()
            if (prefix.exists()) Files.move(prefix.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try {
                Files.move(staging.toPath(), prefix.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                if (!prefix.exists() && backup.exists()) {
                    Files.move(backup.toPath(), prefix.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
                throw e
            }
            if (backup.exists()) backup.deleteRecursively()
            workspace.mkdirs()
            return RuntimePaths(prefix, workspace, File(prefix, "bin/sh"))
        } catch (e: Exception) {
            if (staging.exists()) staging.deleteRecursively()
            if (!prefix.exists() && backup.exists()) {
                runCatching { Files.move(backup.toPath(), prefix.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            }
            throw e
        }
    }

    private fun extractBootstrap(input: InputStream, staging: File, abi: String) {
        staging.mkdirs()
        val regularFiles = linkedSetOf<String>()
        val archiveDirectories = linkedSetOf<String>()
        val seenEntries = linkedSetOf<String>()
        val symlinkBytes = ByteArrayOutputStream()
        var totalBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = RuntimePathPolicy.normalizeArchivePath(entry.name)
                    ?.takeIf { it.isNotEmpty() }
                    ?: error("Unsafe bootstrap archive entry: ${entry.name}")
                check(seenEntries.add(path)) { "Duplicate bootstrap archive entry: ${entry.name}" }
                if (path == SYMLINKS_NAME) {
                    symlinkBytes.write(zip.readBounded(MAX_ENTRY_BYTES))
                } else if (!entry.isDirectory && !entry.name.endsWith('/')) {
                    regularFiles.add(path)
                    val output = File(staging, path)
                    output.parentFile?.mkdirs()
                    var written = 0L
                    output.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            written += count
                            totalBytes += count
                            check(written <= MAX_ENTRY_BYTES && totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                                "Bootstrap archive exceeds extraction limits for $abi"
                            }
                            out.write(buffer, 0, count)
                        }
                    }
                    if (path.startsWith("bin/") || path.startsWith("libexec/")) {
                        output.setExecutable(true, false)
                    }
                } else {
                    archiveDirectories.add(path)
                }
                zip.closeEntry()
            }
        }
        check(symlinkBytes.size() > 0) { "Bootstrap archive has no $SYMLINKS_NAME" }
        val links = parseSymlinks(symlinkBytes.toString(StandardCharsets.UTF_8.name()))
        check(links.isNotEmpty()) { "Bootstrap archive has no symlink records" }
        check(links.any { it.link == "bin/sh" }) { "Bootstrap archive has no bin/sh symlink" }
        val regular = regularFiles.filter { it != SYMLINKS_NAME }.toSet()
        // Symlinks may legally target directories (e.g. lib/terminfo ->
        // ../share/terminfo): directories are explicit archive entries plus
        // the ancestors of regular files.
        val directories = archiveDirectories + regularFiles.flatMap { ancestorDirectories(it) }
        RuntimePathPolicy.validateBootstrapLinks(links, regular, directories)
        links.forEach { link ->
            val linkFile = File(staging, link.link)
            linkFile.parentFile?.mkdirs()
            val relativeTarget = RuntimePathPolicy.relativeSymlinkTarget(link.link, link.target)
            Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get(relativeTarget))
        }
        val shell = File(staging, "bin/sh")
        check(Files.isRegularFile(shell.toPath()) && shell.canExecute()) {
            "Bootstrap archive shell is not executable"
        }
    }

    /** All ancestor directories of an archive path, e.g. a/b/c -> [a, a/b]. */
    private fun ancestorDirectories(path: String): List<String> {
        val parts = path.split('/')
        return (1 until parts.size).map { index -> parts.take(index).joinToString("/") }
    }

    private fun parseSymlinks(contents: String): List<RuntimePathPolicy.BootstrapSymlink> = contents.lineSequence()
        .map { it.trimEnd('\r') }
        .filter { it.isNotBlank() }
        .map { line ->
            val separator = line.indexOf('←')
            check(separator > 0 && separator == line.lastIndexOf('←')) { "Malformed symlink record" }
            val target = line.substring(0, separator)
            val link = line.substring(separator + 1)
            val normalizedLink = RuntimePathPolicy.normalizeArchivePath(link)
                ?: error("Unsafe symlink path: $link")
            val normalizedTarget = RuntimePathPolicy.resolveSymlinkTarget(target, normalizedLink)
                ?: error("Unsafe symlink target: $target")
            check(normalizedTarget.isNotEmpty()) { "Empty symlink target" }
            RuntimePathPolicy.BootstrapSymlink(link = normalizedLink, target = normalizedTarget)
        }
        .toList()

    private fun writeMarker(prefix: File, abi: String) {
        val properties = Properties()
        properties["version"] = VERSION
        properties["abi"] = abi
        properties["sha256"] = expectedSha256(abi)
        prefix.resolve(MARKER_NAME).outputStream().use { properties.store(it, null) }
    }

    private fun isValid(prefix: File, abi: String): Boolean {
        if (!prefix.isDirectory) return false
        val properties = Properties()
        return runCatching {
            prefix.resolve(MARKER_NAME).inputStream().use(properties::load)
            properties["version"] == VERSION &&
                properties["abi"] == abi &&
                properties["sha256"] == expectedSha256(abi) &&
                Files.isRegularFile(File(prefix, "bin/sh").toPath()) &&
                File(prefix, "bin/sh").canExecute()
        }.getOrDefault(false)
    }

    private fun startProcess(runtime: RuntimePaths, command: String, directory: File, workspace: File): Process {
        val builder = ProcessBuilder(runtime.shell.absolutePath, "-c", command)
            .directory(directory)
        val environment = builder.environment()
        environment.clear()
        environment["PREFIX"] = runtime.prefix.absolutePath
        environment["TERMUX_PREFIX"] = runtime.prefix.absolutePath
        environment["HOME"] = workspace.absolutePath
        environment["PWD"] = directory.absolutePath
        environment["TMPDIR"] = File(workspace, "tmp").apply { mkdirs() }.absolutePath
        environment["PATH"] = File(runtime.prefix, "bin").absolutePath
        environment["LD_LIBRARY_PATH"] = File(runtime.prefix, "lib").absolutePath
        environment["SHELL"] = runtime.shell.absolutePath
        environment["LANG"] = "C.UTF-8"
        environment["TERM"] = "xterm-256color"
        val preload = File(runtime.prefix, TERMUX_EXEC_LIBRARY)
        if (preload.isFile) environment["LD_PRELOAD"] = preload.absolutePath
        return builder.start()
    }

    private fun drain(input: InputStream, onOutput: ((String) -> Unit)?): String {
        val bytes = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                val chunk = String(buffer, 0, count, StandardCharsets.UTF_8)
                onOutput?.invoke(chunk)
                bytes.write(buffer, 0, count)
                if (bytes.size() > MAX_OUTPUT_BYTES) {
                    val current = bytes.toByteArray()
                    bytes.reset()
                    bytes.write(current, current.size - MAX_OUTPUT_BYTES, MAX_OUTPUT_BYTES)
                }
            }
        }
        return bytes.toString(StandardCharsets.UTF_8.name())
    }

    private fun terminate(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (!runCatching { process.waitFor(250L, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            process.destroyForcibly()
            runCatching { process.waitFor(2L, TimeUnit.SECONDS) }
        }
    }

    private fun supportedAbi(): String? = android.os.Build.SUPPORTED_ABIS.firstNotNullOfOrNull {
        when (it) {
            "arm64-v8a", "armeabi-v7a", "x86", "x86_64" -> it
            else -> null
        }
    }

    private fun expectedSha256(abi: String): String = when (abi) {
        "arm64-v8a" -> "9ddc32921187c85b04556bf56c6cce94e00b813ecd9299959a2d9b7c33386994"
        "armeabi-v7a" -> "3c856821189c658446ef2a527d264ac9848772d0cae1aa318ecab16315ef732d"
        "x86" -> "100ab4fa85cb90459cb83771602f88007c3296f25098de656979f063e162abd2"
        "x86_64" -> "d8abd8714f8aab19ce923202e647d09b137b0ea34edc25a57b9fb93db6b1c00e"
        else -> error("Unsupported Android ABI: $abi")
    }

    private fun InputStream.readBounded(maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            check(total <= maxBytes) { "Bootstrap symlink record is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
