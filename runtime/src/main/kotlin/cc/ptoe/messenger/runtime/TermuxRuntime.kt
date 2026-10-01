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
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
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
 *
 * This app is also the user-facing terminal: [createTerminalSession] starts an
 * interactive bash on a pseudoterminal (`cpp/termux.c`, the vendored Termux
 * terminal stack) for the terminal screen.
 */
internal object TermuxRuntime {
    private const val ASSET_NAME = "agent-runtime/bootstrap.zip"
    private const val VERSION = "bootstrap-2026.09.27-r3+apt.android-7"

    /**
     * Bumped whenever the extraction/rewrite layout changes, so an already
     * extracted prefix is re-extracted instead of being reused: the marker
     * check below must cover the rewriting this code performs, not just the
     * bootstrap it rewrites. (v2: prefix rewriting of `etc/` rc files + the
     * removed second-stage fallback hook. v3: `--rcfile` argument order,
     * dropped `init-termux-properties.sh`.)
     */
    private const val LAYOUT_VERSION = "3"
    private const val MARKER_NAME = ".messenger-runtime.properties"
    private const val SYMLINKS_NAME = "SYMLINKS.txt"
    private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
    private const val MAX_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
    private const val MAX_OUTPUT_BYTES = 1_000_000
    private const val TERMUX_EXEC_LIBRARY = "lib/libtermux-exec.so"
    private const val OLD_TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val MAX_PREFIX_PATCH_BYTES = 4L * 1024 * 1024

    /** Generated rc file that replays Termux's login sequence; see [writeTerminalRc]. */
    private const val TERMINAL_RC_NAME = "etc/messenger.bashrc"

    /**
     * `etc/profile.d` hooks shipped for the Termux APP that must not run here:
     *
     * - `01-termux-bootstrap-second-stage-fallback.sh` runs the
     *   one-time-in-a-rootfs bootstrap second stage (package postinst scripts)
     *   when it has never run. This runtime deliberately never runs it, and
     *   rewriting the hook to this prefix would run package configuration
     *   against a live prefix at an arbitrary login.
     * - `init-termux-properties.sh` seeds the Termux app's own
     *   `~/.termux/termux.properties` from a HOME baked into the Termux
     *   package (`/data/data/com.termux/files/home`, which the prefix rewrite
     *   below does not cover), so under this application id it can only fail
     *   with "mkdir: Permission denied" on every login. This runtime reads no
     *   termux.properties.
     */
    private val DROPPED_PROFILE_HOOKS = listOf(
        "etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh",
        "etc/profile.d/init-termux-properties.sh"
    )

    private val installMutex = Mutex()

    private class RuntimePaths(val prefix: File, val workspace: File, val shell: File)

    private fun baseDir(context: Context): File = File(context.filesDir, "agent-runtime")

    /** Workspace root (created on demand; no bootstrap install required). */
    fun workspace(context: Context): File =
        File(baseDir(context), "workspace").apply { mkdirs() }

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
        runCatching { writePrefixOverrides(runtime.prefix) }

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
            extractBootstrap(context.assets.open(ASSET_NAME), staging, abi, prefix.absolutePath)
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
            writePrefixOverrides(prefix)
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

    private fun extractBootstrap(input: InputStream, staging: File, abi: String, prefixPath: String) {
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
                    // Executables live in bin/, libexec/, and apt's method
                    // helpers (lib/apt/methods/https etc.) — apt update dies
                    // with "Failed to exec method" without the exec bit.
                    if (path.startsWith("bin/") || path.startsWith("libexec/") ||
                        path.startsWith("lib/apt/methods/")
                    ) {
                        output.setExecutable(true, false)
                    }
                    patchTermuxPrefix(output, path, prefixPath)
                } else {
                    archiveDirectories.add(path)
                }
                zip.closeEntry()
            }
        }
        // Materialize archive directories (empty ones like etc/apt/apt.conf.d
        // would otherwise vanish — apt warns ENOENT reading its config dir).
        archiveDirectories.forEach { dir -> File(staging, dir).mkdirs() }
        // Termux-app profile hooks do not apply to this runtime and their
        // baked-in paths must not survive (see DROPPED_PROFILE_HOOKS).
        DROPPED_PROFILE_HOOKS.forEach { File(staging, it).delete() }
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

    /**
     * Scripts and rc files shipped in the bootstrap (apt-key, pkg, dpkg
     * helpers, etc/profile, etc/bash.bashrc, ...) carry the baked-in Termux
     * prefix, which does not exist under our application ID — and the bundled
     * libtermux-exec preload does not rewrite paths under our own UID. Rewrite
     * the prefix to ours at extraction time so every script and rc file
     * resolves inside the extracted copy.
     */
    private fun patchTermuxPrefix(file: File, path: String, prefixPath: String) {
        if (!shouldPatchTermuxPrefix(path)) return
        if (file.length() > MAX_PREFIX_PATCH_BYTES) return
        // Executables under bin/ and libexec/ are recognized by their shebang;
        // etc/ holds rc files and config scripts without one.
        if (!path.startsWith("etc/")) {
            val head = ByteArray(2)
            if (runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1) < 2) return
            if (head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) return
        }
        val content = runCatching { file.readText() }.getOrNull() ?: return
        if (!content.contains(OLD_TERMUX_PREFIX)) return
        runCatching { file.writeText(content.replace(OLD_TERMUX_PREFIX, prefixPath)) }
    }

    /** Text files of the bootstrap that bake in the Termux prefix. */
    private fun shouldPatchTermuxPrefix(path: String): Boolean =
        path.startsWith("etc/") || path.startsWith("bin/") || path.startsWith("libexec/") ||
            path.startsWith("lib/apt/methods/")

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
        properties["layout"] = LAYOUT_VERSION
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
                properties["layout"] == LAYOUT_VERSION &&
                properties["abi"] == abi &&
                properties["sha256"] == expectedSha256(abi) &&
                Files.isRegularFile(File(prefix, "bin/sh").toPath()) &&
                File(prefix, "bin/sh").canExecute()
        }.getOrDefault(false)
    }

    private fun startProcess(runtime: RuntimePaths, command: String, directory: File, workspace: File): Process {
        val builder = ProcessBuilder(runtime.shell.absolutePath, "-c", command)
            .directory(directory)
        builder.environment().clear()
        builder.environment().putAll(shellEnvironment(runtime, directory, workspace))
        return builder.start()
    }

    /**
     * Blocking preparation of the terminal runtime — extracting the bootstrap
     * on first launch and refreshing the generated prefix overrides. Must run
     * OFF the main thread.
     */
    fun ensureTerminalRuntime(context: Context) {
        val runtime = kotlinx.coroutines.runBlocking { installMutex.withLock { ensureInstalled(context) } }
        writePrefixOverrides(runtime.prefix)
    }

    /**
     * Starts the terminal screen's session: the bootstrap's bash on a
     * pseudoterminal (see `cpp/termux.c`), i.e. a real interactive shell where
     * job control, curses UIs, signals and window resizing behave like on a
     * desktop — unlike [execute], whose pipe-based command has no tty.
     *
     * MUST be called on a thread with a Looper: [TerminalSession] creates its
     * main-thread `Handler` in the constructor (call [ensureTerminalRuntime]
     * on a background thread first — the extraction is the slow part, this
     * call only re-validates the install marker).
     *
     * `bin/bash` is preferred over `bin/sh` (a dash symlink): the terminal's
     * startup files, completion and prompt are bash's. [writeTerminalRc]
     * explains the `--rcfile` argument; the login sequence is replayed from a
     * generated file because bash resolves its sysconfdir at compile time to
     * the canonical Termux prefix.
     */
    fun createTerminalSession(context: Context, client: TerminalSessionClient): TerminalSession {
        val runtime = kotlinx.coroutines.runBlocking { installMutex.withLock { ensureInstalled(context) } }
        val workspace = runtime.workspace
        val bash = File(runtime.prefix, "bin/bash")
        val shell = if (bash.isFile && bash.canExecute()) bash else runtime.shell
        val args = if (shell == bash) {
            // argv[0] must be the program name: `cpp/termux.c` hands this array
            // straight to execvp as argv (the `cmd` argument is only the path
            // to execute), and a leading '-' in argv[0] makes bash treat itself
            // as a login shell and shift every following argument — the rc file
            // then gets executed as a script and the session dies instantly.
            // Long options must also precede the short ones (`bash -i
            // --rcfile X` is rejected with "bash: --: invalid option").
            arrayOf(
                shell.absolutePath,
                "--rcfile",
                File(runtime.prefix, TERMINAL_RC_NAME).absolutePath,
                "-i"
            )
        } else {
            arrayOf(shell.absolutePath, "-i")
        }
        val env = shellEnvironment(runtime, workspace, workspace).toMutableMap()
        env["SHELL"] = shell.absolutePath
        return TerminalSession(
            shell.absolutePath,
            workspace.absolutePath,
            args,
            env.map { (key, value) -> "$key=$value" }.toTypedArray(),
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
            client
        )
    }

    /**
     * Environment handed to every shell this runtime starts (agent tool
     * commands and the terminal session). Built from scratch: the PTY JNI
     * clears the environment before exec, and the Termux bootstrap expects
     * exactly this set — `libtermux-exec` rewrites baked-in paths from
     * TERMUX__PREFIX, apt/dpkg read APT_CONFIG, ncurses reads TERMINFO.
     */
    private fun shellEnvironment(runtime: RuntimePaths, directory: File, workspace: File): Map<String, String> {
        val prefixPath = runtime.prefix.absolutePath
        val environment = linkedMapOf(
            "PREFIX" to prefixPath,
            "TERMUX_PREFIX" to prefixPath,
            // The bootstrap's libtermux-exec (2024+ rewrite) rewrites baked-in
            // Termux paths using the double-underscore TERMUX__PREFIX variable.
            "TERMUX__PREFIX" to prefixPath,
            "HOME" to workspace.absolutePath,
            "PWD" to directory.absolutePath,
            "TMPDIR" to File(workspace, "tmp").apply { mkdirs() }.absolutePath,
            "PATH" to File(runtime.prefix, "bin").absolutePath,
            "LD_LIBRARY_PATH" to File(runtime.prefix, "lib").absolutePath,
            "SHELL" to runtime.shell.absolutePath,
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            // ncurses tools (clear, less, ...) look up terminfo in the
            // compiled-in Termux prefix; point them at the extracted copy.
            "TERMINFO" to File(runtime.prefix, "share/terminfo").absolutePath,
            // apt/dpkg bake the Termux prefix into their binaries; APT_CONFIG
            // redirects every directory they touch to the extracted prefix
            // (see writeAptConfig). Maintainer-script shebangs are handled by
            // the LD_PRELOAD'ed termux-exec.
            "APT_CONFIG" to File(runtime.prefix, "etc/apt/apt.conf").absolutePath
        )
        val preload = File(runtime.prefix, TERMUX_EXEC_LIBRARY)
        if (preload.isFile) environment["LD_PRELOAD"] = preload.absolutePath
        return environment
    }

    /**
     * apt/dpkg read their configuration, package database, and helper
     * binaries from the Termux prefix baked in at build time
     * (/data/data/com.termux/files/usr), which does not exist under our
     * application ID. APT_CONFIG (set in [shellEnvironment]) points apt at
     * this generated override so every directory resolves inside the
     * extracted prefix; dpkg's admin dir rides in via DPkg::Options.
     */
    private fun writeAptConfig(prefix: File) {
        val p = prefix.absolutePath
        val etcApt = File(prefix, "etc/apt").apply {
            mkdirs()
            // The bootstrap archive carries no etc/apt/apt.conf.d entry (not
            // even a directory record) and apt warns ENOENT without it.
            File(this, "apt.conf.d").mkdirs()
            File(this, "sources.list.d").mkdirs()
        }
        File(etcApt, "apt.conf").writeText(
            """
            Dir::Bin::dpkg "$p/bin/dpkg";
            Dir::Bin::methods "$p/lib/apt/methods";
            Dir::State "$p/var/lib/apt";
            Dir::State::status "$p/var/lib/dpkg/status";
            Dir::Cache "$p/var/cache/apt";
            Dir::Etc "$p/etc/apt";
            Dir::Log "$p/var/log";
            DPkg::Options:: "--admindir=$p/var/lib/dpkg";
            Dir::Bin::apt-key "$p/bin/apt-key";
            Acquire::https::CaInfo "$p/etc/tls/cert.pem";
            """.trimIndent() + "\n"
        )
        writeCaBundle(File(prefix, "etc/tls/cert.pem"))
    }

    /**
     * Every generated prefix-side override, rewritten on each ensure (they may
     * have been produced by an older build).
     */
    private fun writePrefixOverrides(prefix: File) {
        writeAptConfig(prefix)
        writeTerminalRc(prefix)
    }

    /**
     * The terminal session's bash cannot find this prefix's own startup files:
     * bash resolves `etc/profile` and `etc/bash.bashrc` at COMPILE time to the
     * canonical Termux prefix (verified in the shipped `bin/bash`), which does
     * not exist under this application id. The session therefore runs
     * `bash --rcfile <prefix>/etc/messenger.bashrc`, and this file replays
     * Termux's login sequence against the extracted prefix: etc/profile
     * (which sources the profile.d snippets and, because PS1 is set,
     * etc/bash.bashrc — Termux's coloured prompt, history tweaks, completion)
     * and then the user's ~/.bashrc, which a login shell would otherwise have
     * read.
     *
     * Those startup files only reach our prefix because `patchTermuxPrefix`
     * rewrites the baked-in Termux prefix in them at extraction time — which
     * is why [LAYOUT_VERSION] invalidates an older extracted prefix.
     */
    private fun writeTerminalRc(prefix: File) {
        File(prefix, TERMINAL_RC_NAME).writeText(
            """
            # Generated by Messenger Runtime.
            [ -r "${'$'}PREFIX/etc/profile" ] && . "${'$'}PREFIX/etc/profile"
            [ -n "${'$'}BASH" ] && [ -r "${'$'}HOME/.bashrc" ] && . "${'$'}HOME/.bashrc"
            """.trimIndent() + "\n"
        )
    }

    /**
     * The bootstrap ships no CA store and its gnutls/curl would look for one
     * under the baked-in Termux prefix. Android keeps the system roots as
     * PEM files under /system/etc/security/cacerts — concatenate them into
     * the bundle that apt's https method (Acquire::https::CaInfo) uses.
     */
    private fun writeCaBundle(target: File) {
        val certs = File("/system/etc/security/cacerts")
            .listFiles()?.filter { it.isFile }?.sortedBy { it.name }
            ?.mapNotNull { file -> runCatching { file.readText() }.getOrNull() }
        if (certs.isNullOrEmpty()) return
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(certs.joinToString("\n"))
        }
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

    // ---------------------------------------------------------------------
    // Interactive MCP Process Execution
    // ---------------------------------------------------------------------

    class McpProcessSession(
        val process: Process,
        private val writer: java.io.BufferedWriter
    ) {
        fun sendLine(line: String): Boolean {
            return try {
                writer.write(line)
                writer.newLine()
                writer.flush()
                true
            } catch (e: Exception) {
                false
            }
        }

        fun close() {
            runCatching { writer.close() }
            runCatching { process.destroyForcibly() }
        }
    }

    suspend fun startMcpProcess(
        context: Context,
        command: String,
        envJson: String?,
        onOutput: (String) -> Unit,
        onError: (String) -> Unit,
        onClosed: (Int) -> Unit
    ): McpProcessSession? {
        val runtime = try {
            withContext(Dispatchers.IO) { installMutex.withLock { ensureInstalled(context) } }
        } catch (e: Exception) {
            return null
        }
        return withContext(Dispatchers.IO) {
            try {
                val workspace = runtime.workspace
                val builder = ProcessBuilder(runtime.shell.absolutePath, "-c", command)
                    .directory(workspace)
                builder.environment().clear()
                val env = shellEnvironment(runtime, workspace, workspace).toMutableMap()
                if (!envJson.isNullOrBlank()) {
                    runCatching {
                        val json = org.json.JSONObject(envJson)
                        for (key in json.keys()) {
                            env[key] = json.optString(key, "")
                        }
                    }
                }
                builder.environment().putAll(env)
                val process = builder.start()
                val writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
                val session = McpProcessSession(process, writer)

                // Background thread reading stdout lines
                Thread {
                    try {
                        process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                            for (line in lines) {
                                onOutput(line)
                            }
                        }
                    } catch (_: Exception) {}
                    val code = runCatching { process.waitFor() }.getOrDefault(-1)
                    onClosed(code)
                }.start()

                // Background thread reading stderr lines
                Thread {
                    try {
                        process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                            for (line in lines) {
                                onError(line)
                            }
                        }
                    } catch (_: Exception) {}
                }.start()

                session
            } catch (e: Exception) {
                null
            }
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
