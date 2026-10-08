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
 * Android tools require the companion runtime app: without it there is no
 * way to execute the terminal tool (W^X) and the workspace tools would run
 * against a workspace no shell can reach. Empty list disables the whole
 * agent-tools feature until the companion is installed.
 */
actual fun createBuiltinChatTools(workspaceRoot: String?): List<ChatTool> =
    if (workspaceRoot == null || ShellRuntimeRegistry.bridge?.isInstalled() != true) {
        emptyList()
    } else {
        listOf(TerminalTool(workspaceRoot = workspaceRoot)) + WorkspaceTool.allFor(workspaceRoot)
    }
