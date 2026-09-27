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

import android.os.Build
import cc.ptoe.messenger.presentation.platform.AndroidContextHolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Installed, app-private Termux bootstrap and workspace paths. */
private data class AndroidRuntimePaths(
    val prefix: File,
    val workspace: File,
    val shell: File
)

/**
 * Installs and executes the pinned Termux bootstrap without relying on a
 * separate Termux application. Installation is lazy and serialized because
 * tool calls can arrive concurrently during the first chat turn.
 */
private object AndroidShellRuntime {
    private const val ASSET_NAME = "agent-runtime/bootstrap.zip"
    private const val VERSION = "bootstrap-2026.09.27-r1+apt.android-7"
    private const val MARKER_NAME = ".messenger-runtime.properties"
    private const val SYMLINKS_NAME = "SYMLINKS.txt"
    private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    private const val MAX_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
    private const val MAX_OUTPUT_BYTES = 1_000_000
    private const val TERMUX_EXEC_LIBRARY = "lib/libtermux-exec.so"

    private val installMutex = Mutex()

    suspend fun execute(command: String, timeoutMs: Long): ShellResult {
        val runtime = try {
            installMutex.withLock { ensureInstalled() }
        } catch (e: Exception) {
            return ShellResult(
                output = "Android shell runtime is unavailable: ${e.message ?: "installation failed"}",
                exitCode = -1
            )
        }

        val processRef = arrayOfNulls<Process>(1)
        val result = try {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    val process = startProcess(runtime, command)
                    processRef[0] = process
                    coroutineScope {
                        val stdout = async { drain(process.inputStream) }
                        val stderr = async { drain(process.errorStream) }
                        val exitCode = process.waitFor()
                        val output = buildString {
                            append(stdout.await())
                            val error = stderr.await()
                            if (error.isNotEmpty()) {
                                if (isNotEmpty()) append('\n')
                                append(error)
                            }
                        }
                        ShellResult(output = output, exitCode = exitCode)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ShellResult(output = "Failed to execute Android shell command: ${e.message ?: "exec failed"}", exitCode = -1)
        } finally {
            processRef[0]?.let(::terminate)
        }

        return result ?: ShellResult(
            output = "Command timed out after ${timeoutMs / 1000} seconds and was terminated.",
            exitCode = -1
        )
    }

    private fun ensureInstalled(): AndroidRuntimePaths {
        val abi = supportedAbi()
            ?: error("This device ABI is not supported by the packaged runtime.")
        val context = AndroidContextHolder.appContext
        val base = File(context.filesDir, "agent-runtime")
        val prefix = File(base, "runtime-$abi")
        val workspace = File(base, "workspace-$abi")
        if (isValid(prefix, abi)) {
            workspace.mkdirs()
            return AndroidRuntimePaths(prefix, workspace, File(prefix, "bin/sh"))
        }

        base.mkdirs()
        val staging = File(base, ".runtime-$abi-${UUID.randomUUID()}")
        val backup = File(base, ".runtime-$abi-backup")
        try {
            extractBootstrap(context.assets.open(ASSET_NAME), staging, abi)
            writeMarker(staging, abi)
            if (backup.exists()) deleteRecursively(backup)
            if (prefix.exists()) Files.move(prefix.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try {
                Files.move(staging.toPath(), prefix.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                if (!prefix.exists() && backup.exists()) {
                    Files.move(backup.toPath(), prefix.toPath(), StandardCopyOption.ATOMIC_MOVE)
                }
                throw e
            }
            if (backup.exists()) deleteRecursively(backup)
            workspace.mkdirs()
            return AndroidRuntimePaths(prefix, workspace, File(prefix, "bin/sh"))
        } catch (e: Exception) {
            if (staging.exists()) deleteRecursively(staging)
            if (!prefix.exists() && backup.exists()) {
                runCatching { Files.move(backup.toPath(), prefix.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            }
            throw e
        }
    }

    private fun extractBootstrap(input: InputStream, staging: File, abi: String) {
        staging.mkdirs()
        val regularFiles = linkedSetOf<String>()
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
                }
                zip.closeEntry()
            }
        }
        check(symlinkBytes.size() > 0) { "Bootstrap archive has no $SYMLINKS_NAME" }
        val links = parseSymlinks(symlinkBytes.toString(StandardCharsets.UTF_8.name()))
        check(links.isNotEmpty()) { "Bootstrap archive has no symlink records" }
        check(links.any { it.link == "bin/sh" }) { "Bootstrap archive has no bin/sh symlink" }
        val regular = regularFiles.filter { it != SYMLINKS_NAME }.toSet()
        val linkPaths = links.map { it.link }.toSet()
        check(linkPaths.size == links.size) { "Bootstrap archive contains duplicate symlink records" }
        links.forEach { link ->
            check(link.link !in regular) { "Symlink overwrites archive file: ${link.link}" }
            check(link.target in regular || link.target in linkPaths) {
                "Dangling bootstrap symlink: ${link.link}"
            }
        }
        validateSymlinkGraph(links.associateBy { it.link }, regular)
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

    private data class BootstrapLink(val target: String, val link: String)

    private fun parseSymlinks(contents: String): List<BootstrapLink> = contents.lineSequence()
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
            BootstrapLink(normalizedTarget, normalizedLink)
        }
        .toList()

    private fun validateSymlinkGraph(links: Map<String, BootstrapLink>, regular: Set<String>) {
        fun visit(path: String, seen: MutableSet<String>) {
            if (path in regular) return
            check(seen.add(path)) { "Cyclic bootstrap symlink: $path" }
            val next = links[path] ?: error("Dangling bootstrap symlink: $path")
            visit(next.target, seen)
            seen.remove(path)
        }
        links.keys.forEach { visit(it, linkedSetOf()) }
    }

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

    private fun startProcess(runtime: AndroidRuntimePaths, command: String): Process {
        val builder = ProcessBuilder(runtime.shell.absolutePath, "-c", command)
            .directory(runtime.workspace)
        val environment = builder.environment()
        environment.clear()
        environment["PREFIX"] = runtime.prefix.absolutePath
        environment["TERMUX_PREFIX"] = runtime.prefix.absolutePath
        environment["HOME"] = runtime.workspace.absolutePath
        environment["PWD"] = runtime.workspace.absolutePath
        environment["TMPDIR"] = File(runtime.workspace, "tmp").apply { mkdirs() }.absolutePath
        environment["PATH"] = File(runtime.prefix, "bin").absolutePath
        environment["LD_LIBRARY_PATH"] = File(runtime.prefix, "lib").absolutePath
        environment["SHELL"] = runtime.shell.absolutePath
        environment["LANG"] = "C.UTF-8"
        environment["TERM"] = "xterm-256color"
        val preload = File(runtime.prefix, TERMUX_EXEC_LIBRARY)
        if (preload.isFile) environment["LD_PRELOAD"] = preload.absolutePath
        return builder.start()
    }

    private fun drain(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        input.use { stream ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
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
        runCatching { process.waitFor() }
        if (process.isAlive) process.destroyForcibly()
    }

    private fun supportedAbi(): String? = Build.SUPPORTED_ABIS.firstNotNullOfOrNull {
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

    private fun deleteRecursively(file: File) {
        if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
            file.listFiles()?.forEach(::deleteRecursively)
        }
        check(!file.delete() || !file.exists()) { "Cannot remove runtime path: ${file.name}" }
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

actual suspend fun executeShellCommand(command: String, timeoutMs: Long): ShellResult =
    AndroidShellRuntime.execute(command, timeoutMs)
