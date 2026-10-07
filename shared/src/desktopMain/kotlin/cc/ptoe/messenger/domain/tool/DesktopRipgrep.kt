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
 */

package cc.ptoe.messenger.domain.tool

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Desktop ripgrep runtime: extracts the pinned rg binary bundled in the
 * shared desktop resources (`/ripgrep/rg-<triple>`) into
 * `~/.messenger/agent-runtime/bin/` on first use and returns the binary to
 * execute. Falls back to an `rg` found on PATH when the bundled asset is
 * missing for the current platform.
 */
internal object DesktopRipgrep {
    private const val RG_VERSION = "14.1.1"

    /** Host triple -> bundled resource name (null = no bundled binary). */
    private val bundledByTriple: Map<String, String> = mapOf(
        "x86_64-pc-windows-msvc" to "rg-x86_64-pc-windows-msvc.exe",
        "x86_64-unknown-linux-gnu" to "rg-x86_64-unknown-linux-gnu",
        "aarch64-unknown-linux-gnu" to "rg-aarch64-unknown-linux-gnu",
        "x86_64-apple-darwin" to "rg-x86_64-apple-darwin",
        "aarch64-apple-darwin" to "rg-aarch64-apple-darwin"
    )

    @Volatile
    private var cached: File? = null

    /** Absolute path of the rg binary to execute, or null when unavailable. */
    fun resolve(): File? {
        cached?.let { if (it.isFile) return it }
        synchronized(this) {
            cached?.let { if (it.isFile) return it }
            val resolved = extractBundled() ?: locateOnPath()
            cached = resolved
            return resolved
        }
    }

    private fun extractBundled(): File? {
        val hostTriple = hostTriple() ?: return null
        val resourceName = bundledByTriple[hostTriple] ?: return null
        val resource = javaClass.getResourceAsStream("/ripgrep/$resourceName") ?: return null
        val installDir = File(System.getProperty("user.home"), ".messenger/agent-runtime/bin")
        val target = File(installDir, "rg")
        if (target.isFile && target.canExecute() && isBundledVersion(target)) return target
        resource.use { input ->
            installDir.mkdirs()
            val staging = File(installDir, "rg.staging-${System.nanoTime()}")
            Files.copy(input, staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                staging.setExecutable(true, false)
                if (target.exists()) target.delete()
                Files.move(staging.toPath(), target.toPath())
            } catch (e: Exception) {
                staging.delete()
                throw e
            }
        }
        return target
    }

    private fun isBundledVersion(binary: File): Boolean = try {
        val process = ProcessBuilder(binary.absolutePath, "--version").start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        "ripgrep $RG_VERSION" in output
    } catch (_: Exception) {
        false
    }

    private fun locateOnPath(): File? {
        val pathEntries = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
        val names = if (isWindows) listOf("rg.exe", "rg.bat", "rg") else listOf("rg")
        for (entry in pathEntries) {
            for (name in names) {
                val candidate = Path.of(entry).resolve(name)
                if (Files.isRegularFile(candidate)) {
                    val file = candidate.toFile()
                    if (file.canExecute()) return file
                }
            }
        }
        return null
    }

    private fun hostTriple(): String? {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        val arch = System.getProperty("os.arch")?.lowercase().orEmpty()
        val archPart = when {
            arch.contains("aarch64") || arch == "arm64" -> "aarch64"
            arch.contains("x86_64") || arch == "amd64" -> "x86_64"
            else -> return null
        }
        return when {
            os.contains("windows") -> "${archPart}-pc-windows-msvc"
            os.contains("linux") -> "${archPart}-unknown-linux-gnu"
            os.contains("mac") || os.contains("darwin") -> "${archPart}-apple-darwin"
            else -> null
        }
    }

    private val isWindows: Boolean
        get() = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
}
