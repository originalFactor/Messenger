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

/**
 * Conservative policy for the shell-backed terminal tool.
 *
 * File mutation belongs to the explicit workspace `edit` and `create` tools.
 * The terminal therefore accepts only one command made from a small set of
 * inspection commands, with shell composition, redirection, interpreters,
 * and path escapes rejected before a process is started.
 */
internal object ShellCommandPolicy {
    private val readOnlyCommands = setOf(
        "cat",
        "cut",
        "date",
        "df",
        "dir",
        "du",
        "echo",
        "file",
        "find",
        "findstr",
        "get-childitem",
        "get-content",
        "get-item",
        "get-location",
        "grep",
        "head",
        "id",
        "ls",
        "measure-object",
        "printenv",
        "printf",
        "pwd",
        "rg",
        "select-string",
        "sort",
        "stat",
        "tail",
        "test",
        "tr",
        "type",
        "uname",
        "uniq",
        "wc",
        "where",
        "where-object",
        "whoami"
    )

    private val forbiddenOptionsByCommand = mapOf(
        "sort" to setOf("-o", "--output"),
        "uniq" to setOf("-o", "--output")
    )

    private val forbiddenFindPredicates = setOf(
        "-delete",
        "-exec",
        "-execdir",
        "-fls",
        "-fprint",
        "-fprint0",
        "-fprintf",
        "-ok",
        "-okdir"
    )

    /** Returns a user/model-visible reason when [command] is not read-only. */
    fun rejectionReason(command: String): String? {
        if (command.isBlank()) return "command is empty"
        if (command.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            return "multiline commands are not allowed"
        }
        if (command.any { it in SHELL_CONTROL_CHARACTERS }) {
            return "shell operators, substitutions, and escapes are not allowed"
        }

        val tokens = tokenize(command) ?: return "unterminated or malformed quoting"
        val executable = tokens.firstOrNull()?.lowercase()
            ?: return "command is empty"
        if (executable !in readOnlyCommands) {
            return "'$executable' is not an approved read-only command"
        }
        if (tokens.any(::containsPathEscape)) {
            return "absolute paths and paths outside the workspace are not allowed"
        }
        if (executable == "find" && tokens.drop(1).any { token ->
                val option = token.lowercase().substringBefore('=')
                option in forbiddenFindPredicates
            }) {
            return "find write actions are not allowed"
        }
        if (tokens.drop(1).any { token ->
                val option = token.lowercase().substringBefore('=')
                option in forbiddenOptionsByCommand[executable].orEmpty()
            }) {
            return "output-file options are not allowed"
        }
        return null
    }

    private fun tokenize(command: String): List<String>? {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var started = false

        fun flush() {
            if (started) {
                tokens += token.toString()
                token.clear()
                started = false
            }
        }

        for (character in command) {
            if (quote != null) {
                if (character == quote) {
                    quote = null
                } else {
                    token.append(character)
                }
                started = true
            } else {
                when {
                    character == '\'' || character == '"' -> {
                        quote = character
                        started = true
                    }
                    character.isWhitespace() -> flush()
                    else -> {
                        token.append(character)
                        started = true
                    }
                }
            }
        }
        if (quote != null) return null
        flush()
        return tokens
    }

    private fun containsPathEscape(token: String): Boolean {
        val normalized = token.replace('\\', '/')
        return normalized.startsWith('/') ||
            normalized.startsWith("~/") ||
            normalized.matches(Regex("^[A-Za-z]:/.*")) ||
            normalized.split('/').any { it == ".." }
    }

    private val SHELL_CONTROL_CHARACTERS = setOf(';', '|', '&', '<', '>', '$', '`', '\\', '(', ')')
}
