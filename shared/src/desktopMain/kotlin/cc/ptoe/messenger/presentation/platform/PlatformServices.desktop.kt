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

package cc.ptoe.messenger.presentation.platform

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import cc.ptoe.messenger.domain.tool.agentWorkspace

actual fun showPlatformToast(message: String) {
    // Desktop has no toast facility; errors also surface in the UI.
    println("Toast: $message")
}

actual fun copyTextToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}

// Desktop has no package registry to query, so the version comes from the
// generated constant (see ':shared:generateAppVersion') — the same VERSION file
// and commit count the Android package carries.
actual fun appVersionName(): String? = APP_VERSION_NAME

actual fun appVersionCode(): Int? = APP_VERSION_CODE

actual val sendOnEnterShortcut: Boolean = true

// 桌面端没有 Messenger Runtime 应用(终端 UI 由该伴随应用的
// TerminalActivity 提供),因此设置页隐藏终端入口。
actual val runtimeTerminalSupported: Boolean = false

actual fun openRuntimeTerminal(): Boolean = false


/**
 * 与 `ShellExecutor.desktop.kt` / `WorkspaceTools.desktop.kt` 共享的默认
 * 工作区，仅用于枚举内置工具（会话的实际工作目录来自其所属项目）。
 */
actual fun defaultWorkspaceRoot(): String? = agentWorkspace.absolutePath