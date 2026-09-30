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

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * The Messenger Runtime app itself: a Termux-style terminal — the vendored
 * Termux terminal emulator/view on a real pseudoterminal ([TermuxRuntime]
 * creates the bootstrap's interactive bash), a toolbar with the session title
 * plus keyboard/paste actions, and the shortcut bar ([ExtraKeysBar]).
 *
 * The terminal is the launcher activity, so the companion app can be used
 * standalone; the main Messenger app opens this screen from
 * 设置 → 高级 → 终端.
 *
 * The session lives as long as this activity (backgrounding keeps it, the
 * process being killed takes it with it) and is killed in [onDestroy]. When
 * the shell exits, tapping the terminal starts a fresh one.
 */
class TerminalActivity : Activity(), TerminalViewClient, TerminalSessionClient {

    private lateinit var terminalView: TerminalView
    private lateinit var titleView: TextView
    private lateinit var statusView: View
    private lateinit var statusText: TextView
    private lateinit var extraKeys: ExtraKeysBar

    private var session: TerminalSession? = null
    private var sessionFinished = false
    private var preparing = false
    private var keyboardRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        terminalView = findViewById(R.id.terminal_view)
        titleView = findViewById(R.id.terminal_title)
        statusView = findViewById(R.id.terminal_status)
        statusText = findViewById(R.id.terminal_status_text)
        extraKeys = ExtraKeysBar(findViewById<LinearLayout>(R.id.terminal_extra_keys_rows), terminalView)

        terminalView.setTerminalViewClient(this)
        terminalView.setTextSize(fontSizePx())
        terminalView.requestFocus()

        findViewById<View>(R.id.terminal_keyboard_button).setOnClickListener { showSoftKeyboard() }
        findViewById<View>(R.id.terminal_paste_button).setOnClickListener {
            onPasteTextFromClipboard(session ?: return@setOnClickListener)
        }
        statusView.setOnClickListener { startSessionAsync() }

        startSessionAsync()
    }

    override fun onResume() {
        super.onResume()
        if (keyboardRequested) showSoftKeyboard()
    }

    override fun onDestroy() {
        session?.finishIfRunning()
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Session lifecycle
    // ------------------------------------------------------------------

    /** Installs the runtime if needed and starts a session; safe to retry. */
    private fun startSessionAsync() {
        if (preparing) return
        preparing = true
        statusView.visibility = View.VISIBLE
        statusText.setText(R.string.runtime_terminal_preparing)
        titleView.setText(R.string.runtime_terminal_title)
        session?.finishIfRunning()
        session = null
        sessionFinished = false

        // The first launch extracts the bootstrap, which must stay off the
        // main thread — while the session itself must be created ON it
        // (TerminalSession builds a main-thread Handler in its constructor).
        Thread {
            val failure = runCatching { TermuxRuntime.ensureTerminalRuntime(applicationContext) }.exceptionOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (failure == null) attachNewSession() else showFailure(failure)
            }
        }.start()
    }

    private fun attachNewSession() {
        if (isDestroyed) {
            preparing = false
            return
        }
        runCatching { TermuxRuntime.createTerminalSession(applicationContext, this) }
            .onSuccess { attachSession(it) }
            .onFailure { showFailure(it) }
    }

    private fun showFailure(failure: Throwable) {
        preparing = false
        Log.e(LOG_TAG, "Terminal session failed", failure)
        statusView.visibility = View.VISIBLE
        statusText.text = getString(
            R.string.runtime_terminal_install_failed,
            failure.message ?: failure.javaClass.simpleName
        )
    }

    private fun attachSession(newSession: TerminalSession) {
        preparing = false
        if (isDestroyed) {
            newSession.finishIfRunning()
            return
        }
        session = newSession
        terminalView.attachSession(newSession)
        statusView.visibility = View.GONE
        terminalView.requestFocus()
        showSoftKeyboard()
    }

    private fun fontSizePx(): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, FONT_SIZE_SP, resources.displayMetrics).toInt()

    private fun showSoftKeyboard() {
        keyboardRequested = true
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, 0)
    }

    // ------------------------------------------------------------------
    // TerminalViewClient
    // ------------------------------------------------------------------

    /** Pinch gestures must not resize the font: there is no font-size setting. */
    override fun onScale(scale: Float): Float = 1f

    override fun onSingleTapUp(e: MotionEvent) {
        // Tapping a finished session (its message is printed by the session)
        // starts a new one, like Termux's "press Enter" behavior.
        if (sessionFinished) startSessionAsync() else showSoftKeyboard()
    }

    /** The back button is the terminal's ESC, as in Termux. */
    override fun shouldBackButtonBeMappedToEscape(): Boolean = true

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = extraKeys.readControlKey()

    override fun readAltKey(): Boolean = extraKeys.readAltKey()

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    // ------------------------------------------------------------------
    // TerminalSessionClient
    // ------------------------------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        titleView.text = changedSession.title?.takeIf { it.isNotBlank() }
            ?: getString(R.string.runtime_terminal_title)
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        sessionFinished = true
        titleView.setText(R.string.runtime_terminal_finished_hint)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrEmpty()) session.write(text)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) = Unit

    override fun onTerminalCursorStateChange(state: Boolean) = Unit

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Exception", e)
    }

    private companion object {
        const val LOG_TAG = "TerminalActivity"
        const val FONT_SIZE_SP = 14f
    }
}
