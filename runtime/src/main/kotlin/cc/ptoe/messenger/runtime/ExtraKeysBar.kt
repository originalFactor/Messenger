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

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.view.TerminalView

/**
 * The terminal shortcut bar: Termux's default extra-keys layout
 * (`extra-keys` property), two rows of buttons above the keyboard —
 *
 * ```
 * ESC   /   -   HOME   ↑   END   ⇑
 * TAB  CTRL ALT   ←    ↓    →    ⇓
 * ```
 *
 * — where a long press on `-` types its popup alternative `|`
 * (`{key: '-', popup: '|'}` upstream) and CTRL / ALT arm a one-shot modifier
 * for the next key the terminal consumes, exactly like Termux's special
 * buttons (the armed state is highlighted and cleared when read).
 *
 * Keys are dispatched through [TerminalView] itself, so escape sequences,
 * cursor-key application mode and the armed modifiers behave like hardware
 * key presses.
 */
internal class ExtraKeysBar(
    private val container: LinearLayout,
    private val terminalView: TerminalView
) {

    private enum class Modifier { CTRL, ALT }

    private class Key(
        val label: String,
        val keyCode: Int = 0,
        val popupLabel: String = "",
        val popupText: Char = '\u0000',
        val modifier: Modifier? = null
    )

    private val modifierButtons = LinkedHashMap<Modifier, TextView>()
    private var ctrlDown = false
    private var altDown = false

    init {
        ROWS.forEach { row ->
            val rowView = LinearLayout(container.context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { key -> rowView.addView(createButton(rowView, key)) }
            container.addView(rowView)
        }
    }

    /** Reads and clears the one-shot CTRL modifier (called by the view client). */
    fun readControlKey(): Boolean = consume(Modifier.CTRL)

    /** Reads and clears the one-shot ALT modifier (called by the view client). */
    fun readAltKey(): Boolean = consume(Modifier.ALT)

    private fun createButton(rowView: LinearLayout, key: Key): TextView {
        val button = LayoutInflater.from(container.context)
            .inflate(R.layout.runtime_terminal_extra_key, rowView, false) as TextView
        button.text = key.label
        if (key.popupLabel.isNotEmpty()) {
            button.setOnLongClickListener {
                terminalView.inputCodePoint(key.popupText.code, false, false)
                true
            }
        }
        val modifier = key.modifier
        button.setOnClickListener {
            if (modifier != null) {
                setModifier(modifier, !isActive(modifier))
            } else {
                sendKey(key.keyCode)
            }
        }
        if (modifier != null) {
            modifierButtons[modifier] = button
        }
        return button
    }

    /**
     * Dispatches one synthesized key press. [TerminalView.onKeyDown] resolves
     * it (control sequences via the key handler, text via the virtual key
     * character map) using the currently armed modifiers, which it consumes.
     */
    private fun sendKey(keyCode: Int) {
        val downTime = SystemClock.uptimeMillis()
        val event = KeyEvent(
            downTime,
            downTime,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0
        )
        terminalView.onKeyDown(keyCode, event)
    }

    private fun isActive(modifier: Modifier): Boolean = when (modifier) {
        Modifier.CTRL -> ctrlDown
        Modifier.ALT -> altDown
    }

    private fun setModifier(modifier: Modifier, active: Boolean) {
        when (modifier) {
            Modifier.CTRL -> ctrlDown = active
            Modifier.ALT -> altDown = active
        }
        modifierButtons[modifier]?.isActivated = active
    }

    private fun consume(modifier: Modifier): Boolean {
        val active = isActive(modifier)
        if (active) setModifier(modifier, false)
        return active
    }

    private companion object {
        val ROWS = listOf(
            listOf(
                Key("ESC", keyCode = KeyEvent.KEYCODE_ESCAPE),
                Key("/", keyCode = KeyEvent.KEYCODE_SLASH),
                Key("-", keyCode = KeyEvent.KEYCODE_MINUS, popupLabel = "|", popupText = '|'),
                Key("HOME", keyCode = KeyEvent.KEYCODE_MOVE_HOME),
                Key("↑", keyCode = KeyEvent.KEYCODE_DPAD_UP),
                Key("END", keyCode = KeyEvent.KEYCODE_MOVE_END),
                Key("⇑", keyCode = KeyEvent.KEYCODE_PAGE_UP)
            ),
            listOf(
                Key("TAB", keyCode = KeyEvent.KEYCODE_TAB),
                Key("CTRL", modifier = Modifier.CTRL),
                Key("ALT", modifier = Modifier.ALT),
                Key("←", keyCode = KeyEvent.KEYCODE_DPAD_LEFT),
                Key("↓", keyCode = KeyEvent.KEYCODE_DPAD_DOWN),
                Key("→", keyCode = KeyEvent.KEYCODE_DPAD_RIGHT),
                Key("⇓", keyCode = KeyEvent.KEYCODE_PAGE_DOWN)
            )
        )
    }
}
