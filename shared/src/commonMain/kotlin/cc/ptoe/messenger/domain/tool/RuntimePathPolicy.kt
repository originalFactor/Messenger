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
