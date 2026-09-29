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

/** Pure path rules shared by bootstrap extraction and its tests. */
internal object RuntimePathPolicy {
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    fun normalizeArchivePath(raw: String): String? {
        val value = raw.replace('\\', '/')
        if (value.startsWith('/') || value.substringBefore('/').contains(':')) return null
        return normalizeRelative(value, rejectParent = true)
    }

    /** Resolves a Termux symlink record to a path relative to Messenger's prefix. */
    fun resolveSymlinkTarget(targetRaw: String, linkRaw: String): String? {
        val link = normalizeArchivePath(linkRaw) ?: return null
        val target = targetRaw.replace('\\', '/')
        val isAbsoluteTermuxTarget = target == TERMUX_PREFIX || target.startsWith("$TERMUX_PREFIX/")
        val normalizedTarget = when {
            target == TERMUX_PREFIX -> ""
            target.startsWith("$TERMUX_PREFIX/") -> target.removePrefix("$TERMUX_PREFIX/")
            target.startsWith('/') -> return null
            else -> target
        }
        val parent = link.substringBeforeLast('/', "")
        return normalizeRelative(
            if (isAbsoluteTermuxTarget) normalizedTarget
            else listOf(parent, normalizedTarget).filter { it.isNotEmpty() }.joinToString("/"),
            rejectParent = true
        )
    }

    fun relativeSymlinkTarget(link: String, target: String): String {
        val linkParts = link.split('/').dropLast(1)
        val targetParts = target.split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < linkParts.size && common < targetParts.size && linkParts[common] == targetParts[common]) {
            common++
        }
        return buildList {
            repeat(linkParts.size - common) { add("..") }
            addAll(targetParts.drop(common))
        }.joinToString("/").ifEmpty { "." }
    }

    /** A normalized SYMLINKS.txt record: link path and its normalized target. */
    data class BootstrapSymlink(val link: String, val target: String)

    /**
     * Validates the bootstrap archive's symlink table. Link paths must not
     * overwrite regular files, and every target must resolve to a regular
     * file, another symlink, or a directory — [directories] holds explicit
     * archive directory entries plus the ancestors of regular files, because
     * a link like lib/terminfo legally points at the share/terminfo
     * directory. Symlink chains must terminate without cycles or dangling
     * ends. Throws IllegalStateException describing the first violation.
     */
    fun validateBootstrapLinks(
        links: List<BootstrapSymlink>,
        regularFiles: Set<String>,
        directories: Set<String>
    ) {
        val byLink = links.associateBy { it.link }
        check(byLink.size == links.size) { "Bootstrap archive contains duplicate symlink records" }
        links.forEach { link ->
            check(link.link !in regularFiles) { "Symlink overwrites archive file: ${link.link}" }
            check(link.link !in directories) { "Symlink overwrites archive directory: ${link.link}" }
            check(
                link.target in regularFiles || link.target in byLink || link.target in directories
            ) { "Dangling bootstrap symlink: ${link.link}" }
        }
        fun visit(path: String, seen: MutableSet<String>) {
            if (path in regularFiles || path in directories) return
            check(seen.add(path)) { "Cyclic bootstrap symlink: $path" }
            val next = byLink[path] ?: error("Dangling bootstrap symlink: $path")
            visit(next.target, seen)
            seen.remove(path)
        }
        links.forEach { link -> visit(link.link, linkedSetOf()) }
    }

    fun isWithinWorkspace(path: String, workspace: String): Boolean {
        val normalizedPath = normalizeRelative(path.removePrefix("/"), rejectParent = false) ?: return false
        val normalizedWorkspace = normalizeRelative(workspace.removePrefix("/"), rejectParent = false) ?: return false
        return normalizedPath == normalizedWorkspace || normalizedPath.startsWith("$normalizedWorkspace/")
    }

    private fun normalizeRelative(raw: String, rejectParent: Boolean): String? {
        val stack = ArrayDeque<String>()
        for (part in raw.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> {
                    if (stack.isEmpty()) {
                        return null
                    } else {
                        stack.removeLast()
                    }
                }
                else -> {
                    if (part.contains(':')) return null
                    stack.addLast(part)
                }
            }
        }
        return stack.joinToString("/")
    }
}
