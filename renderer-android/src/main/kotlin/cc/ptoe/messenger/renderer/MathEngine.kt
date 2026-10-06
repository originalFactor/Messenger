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

package cc.ptoe.messenger.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.style.ReplacementSpan
import kotlin.math.ceil

/**
 * Recursive TeX-subset layout engine (TARGET.md §13): parses a formula into a
 * node tree (fractions, radicals, sub/superscripts, big operators, greek and
 * operator symbols) and lays it out into draw commands. All layout is done in
 * BASELINE-RELATIVE coordinates (y = 0 is the formula's baseline, negative
 * above) so children compose by x-offset alone; [compile] bakes the final
 * top-left coordinate system into the returned display list. Color and fonts
 * are draw-time properties ([drawCommands]), keeping display lists cacheable.
 */
internal object MathEngine {

    // ---------- Tokenizer ----------

    private sealed interface Token
    private data class Cmd(val name: String) : Token
    private object LBrace : Token
    private object RBrace : Token
    private object Sup : Token
    private object Sub : Token
    private data class Chars(val text: String) : Token

    private fun tokenize(src: String): List<Token> {
        val out = mutableListOf<Token>()
        var run = StringBuilder()
        fun flush() {
            if (run.isNotEmpty()) {
                out += Chars(run.toString())
                run = StringBuilder()
            }
        }
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\\' -> {
                    flush()
                    i++
                    val start = i
                    while (i < src.length && src[i].isLetter()) i++
                    if (i > start) {
                        out += Cmd(src.substring(start, i))
                    } else if (i < src.length) {
                        // \, \; \{ \\ … — single non-letter command
                        out += Cmd(src[i].toString())
                        i++
                    }
                }
                c == '{' -> { flush(); out += LBrace; i++ }
                c == '}' -> { flush(); out += RBrace; i++ }
                c == '^' -> { flush(); out += Sup; i++ }
                c == '_' -> { flush(); out += Sub; i++ }
                c.isWhitespace() -> i++ // TeX math mode ignores whitespace
                else -> { run.append(c); i++ }
            }
        }
        flush()
        return out
    }

    // ---------- Symbol table ----------

    private val SYMBOLS: Map<String, String> = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ",
        "mu" to "μ", "nu" to "ν", "xi" to "ξ", "rho" to "ρ",
        "sigma" to "σ", "tau" to "τ", "phi" to "φ", "varphi" to "φ",
        "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
        "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
        "leftrightarrow" to "↔", "mapsto" to "↦",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆",
        "cup" to "∪", "cap" to "∩", "emptyset" to "∅", "varnothing" to "∅",
        "forall" to "∀", "exists" to "∃",
        "deg" to "°", "angle" to "∠", "perp" to "⊥", "parallel" to "∥",
        "ell" to "ℓ", "hbar" to "ℏ",
        "{" to "{", "}" to "}", "%" to "%", "&" to "&", "#" to "#", "$" to "$",
        "," to " ", ";" to "  ", ":" to " ", "!" to "", " " to " ",
        "quad" to "    ", "qquad" to "        "
    )

    private val BIG_OPS = setOf("sum", "prod", "int", "oint", "coprod")
    private val BIG_OP_GLYPHS = mapOf(
        "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮", "coprod" to "∐"
    )
    private val UPRIGHT_FUNCS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "log", "ln", "lg",
        "exp", "lim", "min", "max", "det", "arg", "sup", "inf"
    )

    // ---------- Node tree ----------

    private sealed interface Node
    private class Text(val text: String, val italic: Boolean) : Node
    private class BigOp(val symbol: String) : Node
    private class Frac(val num: Node, val den: Node) : Node
    private class Sqrt(val inner: Node) : Node
    private class Scripted(val base: Node?, val sub: Node?, val sup: Node?) : Node
    private class Group(val items: List<Node>) : Node

    private class Parser(private val tokens: List<Token>) {
        var pos = 0

        fun parseRow(stopAtBrace: Boolean): List<Node> {
            val out = mutableListOf<Node>()
            while (pos < tokens.size) {
                when (val token = tokens[pos]) {
                    is RBrace -> {
                        if (stopAtBrace) return out
                        pos++
                    }
                    is Sup -> { pos++; out += attachScript(out.removeLastOrNull(), null, parseArg()) }
                    is Sub -> { pos++; out += attachScript(out.removeLastOrNull(), parseArg(), null) }
                    is LBrace -> { pos++; out += Group(parseRow(stopAtBrace = true)); expectRBrace() }
                    is Cmd -> out += parseCommand()
                    is Chars -> { pos++; out += splitChars(token.text) }
                }
            }
            return out
        }

        private fun expectRBrace() {
            if (pos < tokens.size && tokens[pos] is RBrace) pos++
        }

        private fun attachScript(base: Node?, sub: Node?, sup: Node?): Node = when {
            base is Scripted && base.sub == null && sub != null -> Scripted(base.base, sub, base.sup)
            base is Scripted && base.sup == null && sup != null -> Scripted(base.base, base.sub, sup)
            else -> Scripted(base, sub, sup)
        }

        /** Argument of frac/sqrt/^/_ — a braced group or a single atom. */
        private fun parseArg(): Node {
            if (pos >= tokens.size) return Group(emptyList())
            return when (val token = tokens[pos]) {
                is LBrace -> { pos++; val inner = Group(parseRow(stopAtBrace = true)); expectRBrace(); inner }
                is Cmd -> parseCommand()
                is Chars -> { pos++; splitChars(token.text).firstOrNull() ?: Group(emptyList()) }
                else -> Group(emptyList())
            }
        }

        private fun parseCommand(): Node {
            val cmd = tokens[pos] as Cmd
            pos++
            when (cmd.name) {
                "frac", "dfrac", "tfrac" -> {
                    val num = parseArg()
                    val den = parseArg()
                    return Frac(num, den)
                }
                "sqrt" -> return Sqrt(parseArg())
                "left", "right", "big", "Big", "bigg", "Bigg" -> {
                    // Consume the following delimiter character
                    if (pos < tokens.size && tokens[pos] is Chars) {
                        val chars = (tokens[pos] as Chars).text
                        if (chars.length == 1) pos++
                    }
                    return Group(emptyList())
                }
            }
            if (cmd.name in BIG_OPS || cmd.name.startsWith("big")) {
                BIG_OP_GLYPHS[cmd.name]?.let { return BigOp(it) }
            }
            SYMBOLS[cmd.name]?.let { symbol ->
                return Text(symbol, italic = false)
            }
            if (cmd.name in UPRIGHT_FUNCS) return Text(cmd.name, italic = false)
            // Unknown command — render its name upright without the backslash
            return Text(cmd.name, italic = false)
        }

        /** Letters become single italic atoms (so ^/_ attach per-letter); digits and operators run upright. */
        private fun splitChars(text: String): List<Node> {
            val out = mutableListOf<Node>()
            val upright = StringBuilder()
            for (c in text) {
                if (c.isLetter()) {
                    if (upright.isNotEmpty()) {
                        out += Text(upright.toString(), italic = false)
                        upright.clear()
                    }
                    out += Text(c.toString(), italic = true)
                } else {
                    upright.append(c)
                }
            }
            if (upright.isNotEmpty()) out += Text(upright.toString(), italic = false)
            return out
        }
    }

    // ---------- Layout (baseline-relative) ----------

    private class MBox(
        val width: Float,
        var ascent: Float,
        var descent: Float
    ) {
        val commands = mutableListOf<MathCommand>()

        fun shift(dx: Float = 0f, dy: Float = 0f) {
            for (i in commands.indices) {
                commands[i] = when (val cmd = commands[i]) {
                    is MathCommand.DrawText -> cmd.copy(x = cmd.x + dx, y = cmd.y + dy)
                    is MathCommand.DrawLine -> cmd.copy(
                        startX = cmd.startX + dx, startY = cmd.startY + dy,
                        stopX = cmd.stopX + dx, stopY = cmd.stopY + dy
                    )
                    is MathCommand.DrawRoot -> cmd.copy(x = cmd.x + dx, y = cmd.y + dy)
                }
            }
        }
    }

    private val italicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    }
    private val uprightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    /** Parse and lay out a formula at [baseFontSizePx]; y = 0 baked to the top edge. */
    fun compile(formula: String, baseFontSizePx: Float): MathDisplayList {
        val cleaned = formula.trim()
            .removePrefix("$$").removeSuffix("$$")
            .removePrefix("$").removeSuffix("$")
            .trim()
        if (cleaned.isEmpty()) return MathDisplayList(0f, 0f, 0f, emptyList())
        val tokens = tokenize(cleaned)
        val box = layout(Group(Parser(tokens).parseRow(stopAtBrace = false)), baseFontSizePx)
        box.shift(dy = box.ascent) // baseline-relative → top-left origin
        return MathDisplayList(box.width, box.ascent + box.descent, box.ascent, box.commands.toList())
    }

    private fun layout(node: Node, size: Float): MBox = when (node) {
        is Group -> layoutRow(node.items, size)
        is Text -> layoutText(node, size)
        is BigOp -> layoutBigOp(node, size)
        is Frac -> layoutFrac(node, size)
        is Sqrt -> layoutSqrt(node, size)
        is Scripted -> layoutScripted(node, size)
    }

    private fun layoutText(node: Text, size: Float): MBox {
        val paint = if (node.italic) italicPaint else uprightPaint
        paint.textSize = size
        val box = MBox(paint.measureText(node.text), size * 0.74f, size * 0.24f)
        box.commands += MathCommand.DrawText(node.text, 0f, 0f, size, node.italic)
        return box
    }

    private fun layoutBigOp(node: BigOp, size: Float): MBox {
        uprightPaint.textSize = size * 1.2f
        val box = MBox(uprightPaint.measureText(node.symbol), size * 1.05f, size * 0.35f)
        box.commands += MathCommand.DrawText(node.symbol, 0f, 0f, size * 1.2f, false)
        return box
    }

    private fun layoutRow(items: List<Node>, size: Float): MBox {
        val boxes = items.filterNot { it is Group && it.items.isEmpty() }.map { layout(it, size) }
        if (boxes.isEmpty()) return MBox(0f, size * 0.74f, size * 0.24f)
        val ascent = boxes.maxOf { it.ascent }
        val descent = boxes.maxOf { it.descent }
        val spacing = size * 0.12f
        val box = MBox(
            boxes.sumOf { it.width.toDouble() }.toFloat() + spacing * (boxes.size - 1),
            ascent, descent
        )
        var x = 0f
        for (child in boxes) {
            child.shift(dx = x) // baseline alignment needs no dy
            box.commands += child.commands
            x += child.width + spacing
        }
        return box
    }

    private fun layoutFrac(node: Frac, size: Float): MBox {
        val childSize = size * 0.85f
        val num = layout(node.num, childSize)
        val den = layout(node.den, childSize)
        val gap = size * 0.22f
        val width = maxOf(num.width, den.width)
        val box = MBox(width, num.ascent + num.descent + gap / 2f, den.ascent + den.descent + gap / 2f)
        num.shift(dx = (width - num.width) / 2f, dy = -(gap / 2f + num.descent))
        box.commands += num.commands
        box.commands += MathCommand.DrawLine(0f, 0f, width, 0f, (size * 0.055f).coerceAtLeast(1.2f))
        den.shift(dx = (width - den.width) / 2f, dy = gap / 2f + den.ascent)
        box.commands += den.commands
        return box
    }

    private fun layoutSqrt(node: Sqrt, size: Float): MBox {
        val inner = layout(node.inner, size)
        val radicalW = size * 0.62f
        val stroke = (size * 0.06f).coerceAtLeast(1.2f)
        val topPad = size * 0.2f
        val top = -(inner.ascent + topPad)
        val box = MBox(radicalW + inner.width, inner.ascent + topPad, inner.descent)
        box.commands += MathCommand.DrawRoot(
            x = 0f,
            y = top,
            width = radicalW,
            height = inner.ascent + topPad + inner.descent,
            thickness = stroke
        )
        box.commands += MathCommand.DrawLine(radicalW, top, radicalW + inner.width, top, stroke)
        inner.shift(dx = radicalW)
        box.commands += inner.commands
        return box
    }

    private fun layoutScripted(node: Scripted, size: Float): MBox {
        val scriptSize = size * 0.7f
        val base = node.base?.let { layout(it, size) }
        val sub = node.sub?.let { layout(it, scriptSize) }
        val sup = node.sup?.let { layout(it, scriptSize) }
        val baseW = base?.width ?: 0f
        val scriptW = maxOf(sub?.width ?: 0f, sup?.width ?: 0f)
        val box = MBox(
            baseW + scriptW,
            base?.ascent ?: size * 0.74f,
            base?.descent ?: size * 0.24f
        )
        base?.let { box.commands += it.commands }
        sup?.let {
            val raise = size * 0.5f
            it.shift(dx = baseW, dy = -raise)
            box.commands += it.commands
            val grownAscent = raise + it.ascent
            if (grownAscent > box.ascent) {
                box.shift(dy = grownAscent - box.ascent) // y = 0 stays the baseline
                box.ascent = grownAscent
            }
        }
        sub?.let {
            val drop = size * 0.35f
            it.shift(dx = baseW, dy = drop)
            box.commands += it.commands
            val grownDescent = drop + it.descent
            if (grownDescent > box.descent) box.descent = grownDescent
        }
        return box
    }

    fun drawCommands(canvas: Canvas, dl: MathDisplayList, color: Int) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        }
        val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
        }
        for (cmd in dl.commands) {
            when (cmd) {
                is MathCommand.DrawText -> {
                    val p = if (cmd.isSymbol) textPaint else symbolPaint
                    p.textSize = cmd.fontSize
                    canvas.drawText(cmd.text, cmd.x, cmd.y, p)
                }
                is MathCommand.DrawLine -> {
                    linePaint.strokeWidth = cmd.thickness
                    canvas.drawLine(cmd.startX, cmd.startY, cmd.stopX, cmd.stopY, linePaint)
                }
                is MathCommand.DrawRoot -> {
                    linePaint.strokeWidth = cmd.thickness
                    val path = Path().apply {
                        moveTo(cmd.x, cmd.y + cmd.height * 0.15f)
                        lineTo(cmd.x + cmd.width * 0.22f, cmd.y + cmd.height * 0.72f)
                        lineTo(cmd.x + cmd.width * 0.4f, cmd.y + cmd.height)
                        lineTo(cmd.x + cmd.width, cmd.y)
                    }
                    canvas.drawPath(path, linePaint)
                }
            }
        }
    }
}

/**
 * Inline math rendered as a bitmap replacement span with TRUE baseline
 * alignment: the formula's own baseline sits on the surrounding text baseline
 * and the span reports grown font metrics so the line opens around tall
 * fractions and superscripts.
 */
class MathSpan(
    private val bitmap: Bitmap,
    private val ascentPx: Float,
    private val descentPx: Float
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        if (fm != null) {
            val spanAscent = -ceil(ascentPx).toInt()
            val spanDescent = ceil(descentPx).toInt()
            fm.ascent = minOf(fm.ascent, spanAscent)
            fm.descent = maxOf(fm.descent, spanDescent)
            fm.top = minOf(fm.top, spanAscent)
            fm.bottom = maxOf(fm.bottom, spanDescent)
        }
        return bitmap.width
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        canvas.drawBitmap(bitmap, x, y - ascentPx, null)
    }
}

/** Renders and caches inline-formula bitmaps keyed by formula/size/color. */
internal object MathInlineRenderer {
    private const val CAPACITY = 128
    private val cache = object : LinkedHashMap<String, CachedMath>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedMath>): Boolean =
            size > CAPACITY
    }

    private class CachedMath(val bitmap: Bitmap, val ascent: Float, val descent: Float)

    fun span(context: Context, formula: String, textSizePx: Float, color: Int): MathSpan? {
        val cleaned = formula.trim()
        if (cleaned.isEmpty()) return null
        val key = "$cleaned|$textSizePx|$color"
        synchronized(cache) { cache[key] }?.let { return MathSpan(it.bitmap, it.ascent, it.descent) }

        // RaTeX (KaTeX-compatible) first — the built-in MathEngine only runs
        // when RaTeX cannot parse the fragment.
        RatexMath.bitmap(context, cleaned, displayMode = false, textSizePx, color)?.let { info ->
            val entry = CachedMath(info.bitmap, info.ascentPx, info.descentPx)
            synchronized(cache) { cache[key] = entry }
            return MathSpan(entry.bitmap, entry.ascent, entry.descent)
        }

        val dl = MathEngine.compile(cleaned, textSizePx)
        if (dl.width <= 0f || dl.height <= 0f) return null
        val w = ceil(dl.width).toInt() + 2
        val h = ceil(dl.height).toInt() + 2
        val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(1f, 1f)
        MathEngine.drawCommands(canvas, dl, color)
        val entry = CachedMath(bitmap, dl.ascent, dl.height - dl.ascent)
        synchronized(cache) { cache[key] = entry }
        return MathSpan(entry.bitmap, entry.ascent, entry.descent)
    }
}
