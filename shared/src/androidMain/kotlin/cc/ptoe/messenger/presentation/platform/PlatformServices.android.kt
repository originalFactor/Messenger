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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import cc.ptoe.messenger.domain.tool.RUNTIME_PACKAGE
import cc.ptoe.messenger.domain.tool.RUNTIME_TERMINAL_ACTIVITY

/** Process-wide Android context, set by MessengerApplication.onCreate. */
object AndroidContextHolder {
    lateinit var appContext: Context
}

actual fun showPlatformToast(message: String) {
    Toast.makeText(AndroidContextHolder.appContext, message, Toast.LENGTH_LONG).show()
}

actual fun copyTextToClipboard(text: String) {
    val clipboard = AndroidContextHolder.appContext
        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
}

actual fun appVersionName(): String? = runCatching {
    val context = AndroidContextHolder.appContext
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.versionName
}.getOrNull()

// 虚拟 IME (软键盘) 不通过 KeyEvent 发送 Enter，因此关闭快捷发送逻辑，
// 避免外接蓝牙键盘等场景意外触发行为差异。Android 端按标准输入法
// 回车换行，发送按钮仍为右侧圆形 FilledIconButton。
actual val sendOnEnterShortcut: Boolean = false

// 终端由伴随的 Messenger Runtime 应用提供(Termux 风格的交互式 shell);
// 主应用只负责拉起它的 TerminalActivity。
actual val runtimeTerminalSupported: Boolean = true

actual fun openRuntimeTerminal(): Boolean {
    val context = AndroidContextHolder.appContext
    val intent = Intent()
        .setComponent(ComponentName(RUNTIME_PACKAGE, RUNTIME_TERMINAL_ACTIVITY))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // 包可见性:主应用清单里声明了 <queries> for the companion package。
    if (context.packageManager.resolveActivity(intent, 0) == null) return false
    return runCatching { context.startActivity(intent) }.isSuccess
}

/**
 * 伴随 runtime 应用自己的工作区，仅用于枚举内置工具（会话的实际工作目录
 * 来自其所属项目）。
 */
actual fun defaultWorkspaceRoot(): String? = null
