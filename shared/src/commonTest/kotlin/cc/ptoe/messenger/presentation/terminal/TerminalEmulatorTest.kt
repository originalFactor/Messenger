/*
 * Copyright 2026 ECSDevs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 */

package cc.ptoe.messenger.presentation.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Behavioral tests for the VT100/xterm output emulator. */
class TerminalEmulatorTest {

    private fun cell(e: TerminalEmulator, row: Int, col: Int) = e.screen.grid[row][col]

    private fun str(e: TerminalEmulator, row: Int, count: Int): String {
        val sb = StringBuilder(count)
        for (i in 0 until count) sb.append(e.screen.grid[row][i].char)
        return sb.toString()
    }

    private fun strBack(e: TerminalEmulator, row: Int): String {
        val sb = StringBuilder(e.screen.grid[row].size)
        for (i in 0 until e.screen.grid[row].size) sb.append(e.screen.grid[row][i].char)
        return sb.toString()
    }

    @Test
    fun `prints text at the cursor and advances`() {
        val e = TerminalEmulator(cols = 10, rows = 4)
        e.append("abc")
        assertEquals('a', cell(e, 0, 0).char)
        assertEquals('b', cell(e, 0, 1).char)
        assertEquals('c', cell(e, 0, 2).char)
        assertEquals(0, e.screen.cursorRow)
        assertEquals(3, e.screen.cursorCol)
    }

    @Test
    fun `CSI H positions the cursor one based and overwrites`() {
        val e = TerminalEmulator(cols = 10, rows = 4)
        e.append("xxxx")
        e.append("\u001B[2;4HX")
        assertEquals(1, e.screen.cursorRow)
        assertEquals(4, e.screen.cursorCol)
        assertEquals('X', cell(e, 1, 3).char)
        assertEquals('x', cell(e, 0, 0).char)
        assertEquals(' ', cell(e, 1, 0).char)
    }

    @Test
    fun `LF at the last row scrolls and pushes into scrollback`() {
        val e = TerminalEmulator(cols = 4, rows = 2)
        e.append("ABCD")
        e.append("\r\nEFGH")
        e.append("\r\nIJKL")
        assertEquals("IJKL", str(e, 0, 4))
        assertEquals("EFGH", str(e, 1, 4))
        assertEquals(1, e.screen.scrollback.size)
        assertEquals("ABCD", e.screen.scrollback[0].joinToString("") { it.char.toString() })
    }

    @Test
    fun `deferred wrap fills a row before the next character moves down`() {
        val e = TerminalEmulator(cols = 80, rows = 3)
        e.append('A'.toString().repeat(80))
        assertEquals(0, e.screen.cursorRow)
        assertEquals(79, e.screen.cursorCol)
        e.append("B")
        assertEquals(1, e.screen.cursorRow)
        assertEquals(0, e.screen.cursorCol)
        assertEquals('B', cell(e, 1, 0).char)
        assertEquals('A', cell(e, 0, 79).char)
    }

    @Test
    fun `SGR indexed and truecolor attributes persist across cells`() {
        val e = TerminalEmulator(cols = 20, rows = 3)
        e.append("\u001B[38;5;208m\u001B[48;2;1;2;3mAB")
        val a = cell(e, 0, 0).attrs
        val b = cell(e, 0, 1).attrs
        assertEquals(TerminalColor.Index(208), a.fg)
        assertEquals(TerminalColor.Truecolor(1, 2, 3), a.bg)
        assertEquals(a, b)
    }

    @Test
    fun `SGR reset restores defaults`() {
        val e = TerminalEmulator(cols = 20, rows = 3)
        e.append("\u001B[1;31;4;9mAB\u001B[0mC")
        val bold = cell(e, 0, 0).attrs
        val plain = cell(e, 0, 2).attrs
        assertTrue(bold.bold)
        assertEquals(TerminalColor.Index(1), bold.fg)
        assertTrue(bold.underline)
        assertTrue(bold.strike)
        assertFalse(plain.bold)
        assertEquals(TerminalColor.Default, plain.fg)
        assertFalse(plain.underline)
        assertFalse(plain.strike)
    }

    @Test
    fun `SGR 256 color uses the trailing parameter after the semicolons`() {
        val e = TerminalEmulator(cols = 20, rows = 3)
        e.append("\u001B[38;5;208mR")
        val a = cell(e, 0, 0).attrs
        assertEquals(TerminalColor.Index(208), a.fg)
    }

    @Test
    fun `SGR truecolor uses the trailing parameters after the kind`() {
        val e = TerminalEmulator(cols = 20, rows = 3)
        e.append("\u001B[48;2;10;20;30mR")
        val a = cell(e, 0, 0).attrs
        assertEquals(TerminalColor.Truecolor(10, 20, 30), a.bg)
    }

    @Test
    fun `erase line modes clear the correct range`() {
        val e = TerminalEmulator(cols = 8, rows = 2)
        e.append("ABCDEFGH")
        e.append("\u001B[3;1H")
        e.append("\u001B[2K")
        assertEquals("      ", str(e, 0, 8))

        val e2 = TerminalEmulator(cols = 8, rows = 2)
        e2.append("ABCDEFGH")
        e2.append("\u001B[3;1H")
        e2.append("\u001B[1K")
        assertEquals("AB    ", str(e2, 0, 8))

        val e3 = TerminalEmulator(cols = 8, rows = 2)
        e3.append("ABCDEFGH")
        e3.append("\u001B[3;1H")
        e3.append("\u001B[0K")
        assertEquals("ABCDEF", str(e3, 0, 8))
    }

    @Test
    fun `erase display modes clear the correct ranges`() {
        val e = TerminalEmulator(cols = 4, rows = 3)
        e.append("AB\r\nCD\r\nEF")
        e.append("\u001B[2;2H")
        e.append("\u001B[0K")
        assertEquals("AB  ", str(e, 0, 4))
        assertEquals("CD  ", str(e, 1, 4))
        assertEquals("EF", str(e, 2, 4))

        val e2 = TerminalEmulator(cols = 4, rows = 3)
        e2.append("AB\r\nCD\r\nEF")
        e2.append("\u001B[2;2H")
        e2.append("\u001B[1K")
        assertEquals("AB  ", str(e2, 0, 4))
        assertEquals("C   ", str(e2, 1, 4))
        assertEquals("EF", str(e2, 2, 4))

        val e3 = TerminalEmulator(cols = 4, rows = 3)
        e3.append("AB\r\nCD\r\nEF")
        e3.append("\u001B[2;2H")
        e3.append("\u001B[2J")
        assertEquals("    ", str(e3, 0, 4))
        assertEquals("    ", str(e3, 1, 4))
        assertEquals("    ", str(e3, 2, 4))
        assertEquals(0, e3.screen.cursorRow)
        assertEquals(0, e3.screen.cursorCol)
    }

    @Test
    fun `erase to end of line then home overwrites a line in place`() {
        val e = TerminalEmulator(cols = 4, rows = 2)
        e.append("ABCD")
        e.append("\u001B[2K\u001B[1GXY")
        assertEquals("XY  ", str(e, 0, 4))
    }

    @Test
    fun `DECSC and DECRS restore position and attributes`() {
        val e = TerminalEmulator(cols = 20, rows = 5)
        e.append("abc\u001B7\u001B[2;5Hdef\u001B8")
        assertEquals(0, e.screen.cursorRow)
        assertEquals(3, e.screen.cursorCol)
        assertEquals('c', cell(e, 0, 2).char)
        assertEquals(' ', cell(e, 1, 4).char)
    }

    @Test
    fun `CSI s and CSI u restore attributes along with the cursor`() {
        val e = TerminalEmulator(cols = 20, rows = 5)
        e.append("\u001B[31mabc\u001B[s\u001B[0m\u001B[2;5Hdef\u001B[u")
        assertEquals(0, e.screen.cursorRow)
        assertEquals(3, e.screen.cursorCol)
        assertEquals(TerminalColor.Index(1), cell(e, 0, 2).attrs.fg)
        assertEquals(TerminalColor.Default, cell(e, 1, 5).attrs.fg)
    }

    @Test
    fun `alternate screen is cleared and the main screen restored`() {
        val e = TerminalEmulator(cols = 8, rows = 4)
        e.append("MAIN\r\nLINE2")
        assertEquals('M', cell(e, 0, 0).char)
        assertEquals('2', cell(e, 1, 4).char)

        e.append("\u001B[?1049h")
        assertEquals(' ', cell(e, 0, 0).char)
        e.append("ALT")
        assertEquals('A', cell(e, 0, 0).char)

        e.append("\u001B[?1049l")
        assertEquals('M', cell(e, 0, 0).char)
        assertEquals('2', cell(e, 1, 4).char)
        assertEquals(' ', cell(e, 0, 4).char)
    }

    @Test
    fun `scrollback is not populated while on the alternate screen`() {
        val e = TerminalEmulator(cols = 4, rows = 2)
        e.append("MAIN\r\nLINE2")
        val before = e.screen.scrollback.size

        e.append("\u001B[?1049h")
        e.append("AA\r\nBB\r\nCC")
        assertEquals(before, e.screen.scrollback.size)
        e.append("\u001B[?1049l")
        assertEquals(before, e.screen.scrollback.size)
    }

    @Test
    fun `DECSTBM confines scrolling to the region`() {
        val e = TerminalEmulator(cols = 4, rows = 5)
        e.append("R1\r\nR2\r\nR3\r\nR4\r\nR5")
        e.append("\u001B[2;4r")
        assertEquals(1, e.screen.cursorRow)
        assertEquals(0, e.screen.cursorCol)

        e.append("AAA\r\nBBB\r\nCCC")
        // Row 0 (outside region) is unchanged; region rows 1..3 scroll forward.
        assertEquals("R1  ", str(e, 0, 4))
        assertEquals("BBB ", str(e, 1, 4))
        assertEquals("CCC ", str(e, 2, 4))
        assertEquals("AAA ", str(e, 3, 4))
        assertEquals("R5  ", str(e, 4, 4))
    }

    @Test
    fun `DECSTBM confined scroll does not leak into scrollback`() {
        val e = TerminalEmulator(cols = 4, rows = 4)
        e.append("R1\r\nR2\r\nR3\r\nR4")
        e.append("\u001B[2;3r")
        e.append("AAA\r\nBBB\r\nCCC")
        assertTrue(e.screen.scrollback.isEmpty())
    }

    @Test
    fun `DSR cursor position reports the one based cursor`() {
        val e = TerminalEmulator(cols = 20, rows = 5)
        var reply: String? = null
        e.onTerminalResponse = { reply = it }

        e.append("\u001B[6n")
        assertEquals("\u001B[1;1R", reply)

        e.append("\u001B[3;5H\u001B[6n")
        assertEquals("\u001B[3;5R", reply)
    }

    @Test
    fun `DSR device status and unknown requests are answered distinctly`() {
        val e = TerminalEmulator(cols = 20, rows = 5)
        val replies = ArrayList<String>()
        e.onTerminalResponse = { replies += it }

        e.append("\u001B[5n")
        assertEquals(1, replies.size)
        assertEquals("\u001B[0n", replies[0])

        e.append("\u001B[99n")
        assertEquals(1, replies.size)
    }

    @Test
    fun `DA1 returns a response framed with CSI question mark and c`() {
        val e = TerminalEmulator(cols = 20, rows = 5)
        var reply: String? = null
        e.onTerminalResponse = { reply = it }

        e.append("\u001B[c")
        val text = reply ?: error("no DA1 response")
        assertTrue(text.startsWith("\u001B[?"))
        assertTrue(text.endsWith("c"))
    }

    @Test
    fun `fullwidth character occupies two columns`() {
        val e = TerminalEmulator(cols = 20, rows = 4)
        e.append("A中B")
        assertEquals('A', cell(e, 0, 0).char)
        assertEquals('中', cell(e, 0, 1).char)
        assertEquals('\u0000', cell(e, 0, 2).char)
        assertEquals('B', cell(e, 0, 3).char)
        assertEquals(4, e.screen.cursorCol)
    }

    @Test
    fun `a fullwidth character at the last column wraps before printing`() {
        val e = TerminalEmulator(cols = 4, rows = 2)
        e.append("A")
        e.append("B")
        e.append("C")
        e.append("中")
        // Cursor was at 3; fullwidth char doesn't fit (needs 2 cells).
        // Auto-wrap triggers; the char is dropped and cursor moves to row 1, col 0.
        assertEquals(1, e.screen.cursorRow)
        assertEquals(0, e.screen.cursorCol)
    }

    @Test
    fun `zero width combining mark does not advance the cursor`() {
        val e = TerminalEmulator(cols = 20, rows = 4)
        e.append("A\u0300") // A + combining grave accent
        assertEquals(1, e.screen.cursorCol)
        assertEquals('A', cell(e, 0, 0).char)
    }

    @Test
    fun `cursor movement is clamped to the screen`() {
        val e = TerminalEmulator(cols = 10, rows = 4)
        e.append("\u001B[99;99H")
        assertEquals(3, e.screen.cursorRow)
        assertEquals(9, e.screen.cursorCol)
        e.append("\u001B[1;1H")
        assertEquals(0, e.screen.cursorRow)
        assertEquals(0, e.screen.cursorCol)
    }

    @Test
    fun `CSI J mode two clears the screen and homes the cursor`() {
        val e = TerminalEmulator(cols = 4, rows = 3)
        e.append("AB\r\nCD")
        e.append("\u001B[2;2H\u001B[2J")
        assertEquals("    ", str(e, 0, 4))
        assertEquals("    ", str(e, 1, 4))
        assertEquals("    ", str(e, 2, 4))
        assertEquals(0, e.screen.cursorRow)
        assertEquals(0, e.screen.cursorCol)
    }

    @Test
    fun `tab expands to the next tab stop and wraps at the last column`() {
        val e = TerminalEmulator(cols = 20, rows = 3)
        e.append("\ta")
        assertEquals(8, e.screen.cursorCol)
        assertEquals('a', cell(e, 0, 8).char)

        e.append("\u001B[20;1H\t")
        // Cursor at col 0 of row 1; tab moves to col 8 of row 1.
        assertEquals(1, e.screen.cursorRow)
        assertEquals(8, e.screen.cursorCol)
    }

    @Test
    fun `a sequence split across appends decodes identically to one call`() {
        val whole = TerminalEmulator(cols = 20, rows = 4)
        whole.append("\u001B[38;5;208m\u001B[2;4HHI\r\nMORE")

        val split = TerminalEmulator(cols = 20, rows = 4)
        split.append("\u001B[3")
        split.append("8;5;2")
        split.append("08m\u001B[2;4H")
        split.append("HI\r\nMORE")

        assertEquals(whole.screen.grid.toList().map { it.toList() }, split.screen.grid.toList().map { it.toList() })
        assertEquals(whole.screen.cursorRow, split.screen.cursorRow)
        assertEquals(whole.screen.cursorCol, split.screen.cursorCol)
    }

    @Test
    fun `a long line wraps into multiple rows`() {
        val e = TerminalEmulator(cols = 10, rows = 5)
        e.append("a".repeat(25))
        assertEquals("aaaaaaaaaa", str(e, 0, 10))
        assertEquals("aaaaaaaaaa", str(e, 1, 10))
        assertEquals("aaaaa     ", str(e, 2, 10))
        assertEquals(2, e.screen.cursorRow)
        assertEquals(5, e.screen.cursorCol)
    }

    @Test
    fun `reverse index scrolls the region instead of moving above it`() {
        val e = TerminalEmulator(cols = 4, rows = 3)
        e.append("AAA\r\nBBB\r\nCCC")
        e.append("\u001B[1;1H\u001BM")
        // Reverse index at scrollTop: scrolls the region up, pushing row 1 up.
        assertEquals("BBB ", str(e, 0, 4))
        assertEquals("CCC ", str(e, 1, 4))
        assertEquals("    ", str(e, 2, 4))
    }

    @Test
    fun `hidden cursor mode keeps the parser functional`() {
        val e = TerminalEmulator(cols = 20, rows = 4)
        e.append("\u001B[?2004h\u001B[?25l")
        assertEquals(false, e.screen.cursorVisible)

        e.append("STILL VISIBLE")
        assertEquals('S', cell(e, 0, 0).char)
        assertEquals('E', cell(e, 0, 12).char)

        e.append("\u001B[?25h")
        assertEquals(true, e.screen.cursorVisible)
    }

    @Test
    fun `clear resets grid cursor attributes and scrollback`() {
        val e = TerminalEmulator(cols = 8, rows = 3)
        e.append("\u001B[31mhello\r\nworld")
        e.clear()
        assertEquals(' ', cell(e, 0, 0).char)
        assertEquals(' ', cell(e, 1, 0).char)
        assertEquals(0, e.screen.cursorRow)
        assertEquals(0, e.screen.cursorCol)
        assertEquals(TerminalColor.Default, cell(e, 0, 0).attrs.fg)
        assertTrue(e.screen.scrollback.isEmpty())
        assertEquals(true, e.screen.cursorVisible)
    }

    @Test
    fun `global reverse video mode is reflected in the screen`() {
        val e = TerminalEmulator(cols = 20, rows = 4)
        assertEquals(false, e.screen.reverseVideo)
        e.append("\u001B[?5h")
        assertEquals(true, e.screen.reverseVideo)
        e.append("\u001B[?5l")
        assertEquals(false, e.screen.reverseVideo)
    }

    @Test
    fun `OSC 0 and 2 set the title and the parser stays in sync`() {
        val e = TerminalEmulator(cols = 20, rows = 4)
        e.append("\u001B]0;Hello\u0007")
        assertEquals("Hello", e.screen.title)
        e.append("MORE")
        e.append("\u001B]2;World\u001B\\")
        assertEquals("World", e.screen.title)
        // OSC text is not rendered.
        assertEquals("MORE", str(e, 0, 4))
    }

    @Test
    fun `OSC long text is truncated without blocking the parser`() {
        val e = TerminalEmulator(cols = 20, rows = 4)
        val long = "x".repeat(6000)
        e.append("\u001B]0;$long\u0007")
        // Parser must not hang; OSC is dropped past the buffer cap.
        e.append("done")
        assertEquals("done", str(e, 0, 4))
    }

    @Test
    fun `private mode 7 toggles auto wrap`() {
        val e = TerminalEmulator(cols = 4, rows = 2)
        e.append("abcd")
        assertEquals(3, e.screen.cursorCol)
        e.append("\u001B[?7lX")
        // Auto-wrap off: writing past the right margin drops the char.
        assertEquals('X', cell(e, 0, 3).char)
        assertEquals(3, e.screen.cursorCol)
        e.append("\u001B[?7hY")
        // Re-enable; deferred wrap pushes the new char down.
        assertEquals('X', cell(e, 0, 3).char)
        assertEquals('Y', cell(e, 1, 0).char)
    }

    @Test
    fun `cursor position request without a reply sink does not throw`() {
        val e = TerminalEmulator(cols = 20, rows = 4)
        e.append("\u001B[6n")
        // onTerminalResponse is null; nothing should throw.
        assertEquals(0, e.screen.cursorRow)
        assertEquals(0, e.screen.cursorCol)
    }

    @Test
    fun `DA1 with private mode prefix uses the greater than form`() {
        val e = TerminalEmulator(cols = 20, rows = 4)
        var reply: String? = null
        e.onTerminalResponse = { reply = it }
        e.append("\u001B[c")
        assertTrue(reply?.startsWith("\u001B[?") == true)
        e.append("\u001B[>c")
        assertTrue(reply?.startsWith("\u001B[>0;0;6;6;c") == true)
    }

    @Test
    fun `CSI 5n reports the terminal is ready`() {
        val e = TerminalEmulator(cols = 20, rows = 4)
        var reply: String? = null
        e.onTerminalResponse = { reply = it }
        e.append("\u001B[5n")
        assertEquals("\u001B[0n", reply)
    }

    @Test
    fun `erase display preserves content outside the erased range`() {
        val e = TerminalEmulator(cols = 4, rows = 3)
        e.append("AB\r\nCD\r\nEF")
        e.append("\u001B[2;2H\u001B[0K")
        // Erase below cursor on row 1: keeps row 0, erases rest of row 1 and rows 2+.
        assertEquals("AB  ", str(e, 0, 4))
        assertEquals("CD  ", str(e, 1, 4))
        assertEquals("    ", str(e, 2, 4))
    }

    @Test
    fun `scrollback cap is enforced`() {
        val e = TerminalEmulator(cols = 4, rows = 2, maxScrollbackLines = 3)
        for (i in 0..10) {
            e.append("${'$'}{i}")
            e.append("\r\n")
        }
        assertEquals(3, e.screen.scrollback.size)
    }

    @Test
    fun `grid row arrays have exactly the declared column count`() {
        val e = TerminalEmulator(cols = 13, rows = 5)
        e.append("x")
        for (r in 0 until 5) {
            assertEquals(13, e.screen.grid[r].size)
        }
    }
}
