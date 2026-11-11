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

package cc.ptoe.llmtypewriter

/**
 * Semantic AST for a TeX math fragment. Built from [TexToken]s by [buildMathAst]; rendered to
 * Compose by [MathRenderer]. The AST is **prefix-stable** in the same way the token stream is:
 * a longer input never rewrites earlier nodes — it only appends to the trailing sequence. That
 * lets the live renderer repaint the same early nodes as more text streams in.
 *
 * Design notes:
 *  - `^` and `_` applied to the same base fold into a single [SubSup] node so the renderer can
 *    paint the sub and the sup in the **same column** (one above the other) — this is the
 *    invariant the user explicitly called out: "上下标（同时上下标在同一列内）".
 *  - Large operators ([BigOperator]) carry optional lower/upper scripts. In display mode the
 *    renderer paints them above/below the operator glyph; in inline mode they fall back to
 *    side-positioned sub/sup.
 *  - Big operators in display mode also use a larger font for the operator glyph itself —
 *    handled by the renderer, not the AST.
 */
sealed class MathNode {

    /** Plain literal text (multi-character run). Rendered in the ambient text style. */
    data class Text(val text: String) : MathNode()

    /** A single variable letter — conventionally italic in math typography. */
    data class Identifier(val char: String) : MathNode()

    /** A numeric literal. Rendered upright (not italic). */
    data class Number(val text: String) : MathNode()

    /** A symbolic command resolved to its Unicode glyph, e.g. `\alpha` → `α`. */
    data class Symbol(val glyph: String) : MathNode()

    /** Generic command with [args] arguments (each a sub-AST). Kept for commands we don't specialize. */
    data class Command(val name: String, val args: List<MathNode>) : MathNode()

    /** `\frac{num}{den}` — vertical fraction with bar. */
    data class Fraction(val numerator: MathNode, val denominator: MathNode) : MathNode()

    /** `\sqrt[n]{x}` — root; [index] is null for plain `\sqrt{x}`. */
    data class Sqrt(val base: MathNode, val index: MathNode? = null) : MathNode()

    /** `x^{sup}` — superscript only, side-positioned. */
    data class Superscript(val base: MathNode, val target: MathNode) : MathNode()

    /** `x_{sub}` — subscript only, side-positioned. */
    data class Subscript(val base: MathNode, val target: MathNode) : MathNode()

    /**
     * `x_{sub}^{sup}` (or `x^{sup}_{sub}`) — both scripts applied to the same base. The renderer
     * paints them in the **same column** (sub below the baseline, sup above) so they line up
     * vertically — distinct from two separate side-by-side sub/sup.
     */
    data class SubSup(val base: MathNode, val sub: MathNode, val sup: MathNode) : MathNode()

    /** `\binom{n}{k}` — binomial coefficient, rendered like a fraction without the bar. */
    data class Binom(val n: MathNode, val k: MathNode) : MathNode()

    /**
     * Large operator with optional limits: `\sum_{i=0}^{n}`. [lower]/[upper] are null when the
     * operator has no scripts. In display mode, the renderer places [lower] below and [upper]
     * above the operator glyph; in inline mode they become side sub/sup.
     */
    data class BigOperator(val name: String, val glyph: String, val lower: MathNode? = null, val upper: MathNode? = null) : MathNode()

    /** `{ ... }` group — rendered as its inner [nodes] in sequence. */
    data class Group(val nodes: List<MathNode>) : MathNode()

    /** `\left( ... \right)` — sized delimiters around [content]. */
    data class Delimiter(val left: String, val content: MathNode, val right: String) : MathNode()

    /** A spacing command (`\,`, `\;`, `\quad`, …) — rendered as a horizontal gap. */
    data class Space(val width: SpaceWidth) : MathNode()

    /** Spacing widths supported by [MathNode.Space]. */
    enum class SpaceWidth { Thin, Medium, Thick, Quad }
}

/** Big operators that get oversized glyphs + above/below limits in display mode. */
private val BigOperators: Map<String, String> = mapOf(
    "sum" to "∑",
    "prod" to "∏",
    "coprod" to "∐",
    "int" to "∫",
    "oint" to "∮",
    "iint" to "∬",
    "iiint" to "∭",
    "bigcup" to "⋃",
    "bigcap" to "⋂",
    "bigvee" to "⋁",
    "bigwedge" to "⋀",
    "bigoplus" to "⨁",
    "bigotimes" to "⨂",
    "bigodot" to "⨀",
    "biguplus" to "⨄",
)

/** Symbolic commands resolved to Unicode glyphs. */
private val SymbolMap: Map<String, String> = mapOf(
    // Greek lower
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "vartheta" to "ϑ",
    "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
    "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ",
    "sigma" to "σ", "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
    "varphi" to "ϕ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    // Greek upper
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    // Relations
    "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠",
    "approx" to "≈", "equiv" to "≡", "sim" to "∼", "simeq" to "≃", "cong" to "≅",
    "propto" to "∝", "doteq" to "≐",
    "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "supseteq" to "⊇",
    "sqsubset" to "⊏", "sqsubseteq" to "⊑", "sqsupset" to "⊐", "sqsupseteq" to "⊒",
    "in" to "∈", "notin" to "∉", "ni" to "∋", "owns" to "∋",
    "prec" to "≺", "preceq" to "≼", "succ" to "≻", "succeq" to "≽",
    "perp" to "⊥", "parallel" to "∥", "mid" to "∣", "nmid" to "∤",
    "vdash" to "⊢", "dashv" to "⊣", "models" to "⊨",
    // Arrows
    "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "gets" to "←",
    "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
    "Leftrightarrow" to "⇔", "iff" to "⟺", "mapsto" to "↦", "hookrightarrow" to "↪",
    "hookleftarrow" to "↩", "uparrow" to "↑", "downarrow" to "↓",
    "updownarrow" to "↕", "Uparrow" to "⇑", "Downarrow" to "⇓", "Updownarrow" to "⇕",
    "nearrow" to "↗", "searrow" to "↘", "nwarrow" to "↖", "swarrow" to "↙",
    "rightharpoonup" to "⇀", "rightharpoondown" to "⇁",
    "leftharpoonup" to "↼", "leftharpoondown" to "↽",
    // Binary operators
    "pm" to "±", "mp" to "∓", "times" to "×", "div" to "÷", "ast" to "∗",
    "star" to "★", "cdot" to "·", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
    "circ" to "∘", "bullet" to "•", "otimes" to "⊗", "oplus" to "⊕",
    "odot" to "⊙", "ominus" to "⊖", "cap" to "∩", "cup" to "∪", "sqcap" to "⊓",
    "sqcup" to "⊔", "wedge" to "∧", "vee" to "∨", "setminus" to "∖",
    "wr" to "≀", "diamond" to "⋄", "triangle" to "△", "triangleleft" to "◃",
    "triangleright" to "▹", "lhd" to "◁", "rhd" to "▷",
    // Misc symbols
    "infty" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exists" to "∃",
    "nexists" to "∄", "emptyset" to "∅", "varnothing" to "∅", "angle" to "∠",
    "measuredangle" to "∡", "sphericalangle" to "∢", "prime" to "′", "backprime" to "‵",
    "flat" to "♭", "natural" to "♮", "sharp" to "♯",
    "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ", "aleph" to "ℵ",
    "beth" to "ℶ", "gimel" to "ℷ", "daleth" to "ℸ",
    "complement" to "∁", "mho" to "℧", "Finv" to "Ⅎ", "Game" to "⅁",
    "Bbbk" to "𝕜", "eth" to "ð", "S" to "§", "P" to "¶",
    "dag" to "†", "dagger" to "†", "ddag" to "‡", "ddagger" to "‡",
    "dots" to "…", "ldots" to "…", "colon" to ":",
    "degree" to "°", "pounds" to "£", "copyright" to "©", "registered" to "®",
    "checkmark" to "✓", "neg" to "¬", "lnot" to "¬",
    "clubsuit" to "♣", "diamondsuit" to "♢", "heartsuit" to "♡", "spadesuit" to "♠",
    "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "oint" to "∮",
    "iint" to "∬", "iiint" to "∭", "bigcup" to "⋃", "bigcap" to "⋂",
    "bigvee" to "⋁", "bigwedge" to "⋀", "bigoplus" to "⨁", "bigotimes" to "⨂",
    "bigodot" to "⨀", "biguplus" to "⨄",
    // Blackboard bold (rendered via \mathbb{X}, but provide common shortcuts)
    "mathbbR" to "ℝ", "mathbbN" to "ℕ", "mathbbZ" to "ℤ", "mathbbQ" to "ℚ",
    "mathbbC" to "ℂ", "mathbbH" to "ℍ", "mathbbP" to "ℙ", "mathbbA" to "𝔸",
    // Common functions
    "log" to "log", "ln" to "ln", "lg" to "lg", "exp" to "exp",
    "sin" to "sin", "cos" to "cos", "tan" to "tan", "cot" to "cot",
    "sec" to "sec", "csc" to "csc", "arcsin" to "arcsin", "arccos" to "arccos",
    "arctan" to "arctan", "sinh" to "sinh", "cosh" to "cosh", "tanh" to "tanh",
    "lim" to "lim", "limsup" to "lim sup", "liminf" to "lim inf",
    "max" to "max", "min" to "min", "sup" to "sup", "inf" to "inf",
    "arg" to "arg", "dim" to "dim", "gcd" to "gcd", "hom" to "hom", "ker" to "ker",
    "deg" to "deg", "det" to "det", "Pr" to "Pr",
)

/** Spacing commands and their widths. */
private val SpaceMap: Map<String, MathNode.SpaceWidth> = mapOf(
    "," to MathNode.SpaceWidth.Thin,
    ":" to MathNode.SpaceWidth.Medium,
    ";" to MathNode.SpaceWidth.Thick,
    "!" to MathNode.SpaceWidth.Thin, // negative — renderer treats same width but we keep simple
    "quad" to MathNode.SpaceWidth.Quad,
    "qquad" to MathNode.SpaceWidth.Quad,
    "thinspace" to MathNode.SpaceWidth.Thin,
    "medspace" to MathNode.SpaceWidth.Medium,
    "thickspace" to MathNode.SpaceWidth.Thick,
)

/** Commands that take a fixed number of arguments and produce a specialized node. */
private object SpecialCommands {
    const val FRAC = "frac"
    const val SQRT = "sqrt"
    const val BINOM = "binom"
    const val MATHBB = "mathbb"
    const val MATHIT = "mathit"
    const val MATHRM = "mathrm"
    const val MATHBF = "mathbf"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val TEXT = "text"
    const val OPERATORNAME = "operatorname"
}

/** Singleton delimiters recognized after `\left` / `\right`. */
private val DelimMap: Map<String, String> = mapOf(
    "(" to "(", ")" to ")", "[" to "[", "]" to "]",
    "\\{" to "{", "\\}" to "}", "|" to "|", "\\|" to "‖",
    "." to "", // \left. / \right. is an empty delimiter
    "\\langle" to "⟨", "\\rangle" to "⟩",
    "\\lceil" to "⌈", "\\rceil" to "⌉",
    "\\lfloor" to "⌊", "\\rfloor" to "⌋",
    "\\uparrow" to "↑", "\\downarrow" to "↓",
    "\\updownarrow" to "↕", "\\Uparrow" to "⇑",
    "\\Downarrow" to "⇓", "\\Updownarrow" to "⇕",
)

/**
 * Builds a [MathNode] tree from a [TexToken] list. Pure-Kotlin, prefix-stable. Unknown commands
 * degrade to [MathNode.Command] with their raw arguments so the renderer can still paint
 * something sensible (typically the command name verbatim) instead of dropping content.
 */
internal fun buildMathAst(tokens: List<TexToken>): MathNode {
    val (node, _) = buildSeq(tokens, 0)
    return node
}

/**
 * Builds a sequence of nodes from [tokens] starting at [i]. Returns the resulting [MathNode]
 * (a [MathNode.Sequence] if there's more than one node, otherwise the single node directly)
 * and the index just past the consumed tokens.
 *
 * Stops at: end of input, or a token that the caller must handle (e.g. a `}` group end is
 * handled by the group-consumer; a `\right` command is handled by the delimiter-consumer).
 */
private fun buildSeq(tokens: List<TexToken>, i: Int): Pair<MathNode, Int> {
    val nodes = mutableListOf<MathNode>()
    var j = i
    val n = tokens.size

    while (j < n) {
        val tok = tokens[j]

        // Stop tokens — these signal the end of the current sequence for the caller to handle.
        if (tok is TexToken.Command && (tok.name == SpecialCommands.RIGHT)) {
            break
        }

        val (node, next) = buildOne(tokens, j)
        if (node == null) {
            j = next
            continue
        }

        // Post-fix: scripts (`^`/`_`) attach to the just-built node. We accept any run of them.
        // Two scripts (`_` + `^` or `^` + `_`) on the same base fold into a single [SubSup] so
        // the renderer can paint them in the same column. When the base is a [MathNode.BigOperator],
        // the scripts become `lower`/`upper` limits on the operator itself rather than wrapping
        // nodes — this lets the renderer stack them above/below the glyph in display mode.
        // Explicit type annotation is needed because [buildOne] returns MathNode? — the null check
        // above smart-casts `node` to MathNode, but `var attached = node` would otherwise infer
        // MathNode? from the declared return type.
        var attached: MathNode = node
        var k = next
        while (k < n) {
            val scriptTok = tokens[k]
            if (scriptTok !is TexToken.Superscript && scriptTok !is TexToken.Subscript) break
            val isFirstSub = scriptTok is TexToken.Subscript
            val firstTarget = scriptTarget(scriptTok)

            // Look at the next token — if it's the opposite script, fold both scripts together.
            if (k + 1 < n) {
                val nextTok = tokens[k + 1]
                val isOppositeSup = nextTok is TexToken.Superscript && isFirstSub
                val isOppositeSub = nextTok is TexToken.Subscript && !isFirstSub
                if (isOppositeSup || isOppositeSub) {
                    val sub = if (isFirstSub) firstTarget else scriptTarget(nextTok)
                    val sup = if (isFirstSub) scriptTarget(nextTok) else firstTarget
                    attached = attachBothScripts(attached, sub, sup)
                    k += 2
                    continue
                }
            }

            // Single script.
            attached = if (isFirstSub) {
                attachSingleScript(attached, isSub = true, firstTarget)
            } else {
                attachSingleScript(attached, isSub = false, firstTarget)
            }
            k++
        }
        nodes += attached
        j = k
    }

    return when (nodes.size) {
        0 -> MathNode.Text("")
        1 -> nodes[0]
        else -> MathNode.Group(nodes)
    } to j
}

/**
 * Attaches a single `^` or `_` script to [base]. For a [MathNode.BigOperator] base, the script
 * becomes the operator's `upper` (sup) or `lower` (sub) limit instead of wrapping the node —
 * the renderer can then stack the limits above/below the glyph in display mode. For any other
 * base, this produces a [MathNode.Superscript] / [MathNode.Subscript].
 */
private fun attachSingleScript(base: MathNode, isSub: Boolean, target: MathNode): MathNode {
    if (base is MathNode.BigOperator) {
        return if (isSub) base.copy(lower = target) else base.copy(upper = target)
    }
    return if (isSub) MathNode.Subscript(base, target) else MathNode.Superscript(base, target)
}

/**
 * Attaches both `^` and `_` to [base] at once. For a [MathNode.BigOperator] base, both scripts
 * fold into the operator's `lower`/`upper`. For any other base, this produces a [MathNode.SubSup]
 * so the renderer can paint the sub and the sup in the **same column** (one above the other).
 */
private fun attachBothScripts(base: MathNode, sub: MathNode, sup: MathNode): MathNode {
    if (base is MathNode.BigOperator) {
        return base.copy(lower = sub, upper = sup)
    }
    return MathNode.SubSup(base, sub, sup)
}

/** Builds a single node (no script attachment) starting at [i]. Returns null for skip-tokens. */
private fun buildOne(tokens: List<TexToken>, i: Int): Pair<MathNode?, Int> {
    val tok = tokens[i]
    return when (tok) {
        is TexToken.Text -> textNode(tok.text) to i + 1
        is TexToken.Comment -> MathNode.Text("") to i + 1 // comments render as nothing
        is TexToken.Group -> {
            val (inner, _) = buildSeq(tok.tokens, 0)
            MathNode.Group(listOf(inner)) to i + 1
        }
        is TexToken.Superscript, is TexToken.Subscript -> {
            // Leading script with no base — degrade to literal text.
            val literal = if (tok is TexToken.Superscript) "^" else "_"
            MathNode.Text(literal) to i + 1
        }
        is TexToken.Command -> buildCommand(tokens, i, tok.name)
    }
}

/**
 * Splits a [TexToken.Text] run into typed sub-nodes: numbers → [MathNode.Number], single
 * letters → [MathNode.Identifier] (italic), everything else → [MathNode.Text]. Returns a
 * [MathNode.Group] if the run splits into multiple kinds, otherwise a single node.
 */
private fun textNode(text: String): MathNode {
    if (text.isEmpty()) return MathNode.Text("")
    if (text.length == 1) {
        val c = text[0]
        return when {
            c.isDigit() -> MathNode.Number(text)
            c.isLetter() -> MathNode.Identifier(text)
            else -> MathNode.Text(text)
        }
    }
    val nodes = mutableListOf<MathNode>()
    val sb = StringBuilder()
    var mode = 0 // 0=none, 1=letter, 2=digit, 3=other
    for (c in text) {
        val m = when {
            c.isLetter() -> 1
            c.isDigit() -> 2
            else -> 3
        }
        if (m != mode && sb.isNotEmpty()) {
            flushText(sb, mode, nodes)
        }
        sb.append(c)
        mode = m
    }
    if (sb.isNotEmpty()) flushText(sb, mode, nodes)
    return if (nodes.size == 1) nodes[0] else MathNode.Group(nodes)
}

private fun flushText(sb: StringBuilder, mode: Int, out: MutableList<MathNode>) {
    val s = sb.toString()
    when (mode) {
        1 -> out += MathNode.Identifier(s) // multi-letter identifier — kept as one node (italic)
        2 -> out += MathNode.Number(s)
        else -> out += MathNode.Text(s)
    }
    sb.clear()
}

/** Extracts the [MathNode] target of a `^`/`_` script token. */
private fun scriptTarget(tok: TexToken): MathNode {
    val target = when (tok) {
        is TexToken.Superscript -> tok.target
        is TexToken.Subscript -> tok.target
        else -> error("not a script token")
    }
    val (node, _) = buildSeq(target, 0)
    return node
}

/** Builds a node for a single command token, possibly consuming following tokens as arguments. */
private fun buildCommand(tokens: List<TexToken>, i: Int, name: String): Pair<MathNode?, Int> {
    val n = tokens.size

    // Big operators: \sum, \int, \prod, … Scripts attach via the normal post-fix machinery in
    // [buildSeq]; here we just produce the BigOperator node. The renderer decides whether to
    // stack the scripts above/below (display mode) or side-position them (inline mode).
    BigOperators[name]?.let { glyph ->
        return MathNode.BigOperator(name = name, glyph = glyph) to i + 1
    }

    // Symbolic commands — \alpha, \infty, \times, …
    SymbolMap[name]?.let { glyph ->
        // Function-name commands (sin, cos, lim, log, …) render as upright text, not as a single
        // glyph — but we keep them as a Text node so the renderer applies upright style.
        if (name in FunctionNames) {
            return MathNode.Text(glyph) to i + 1
        }
        return MathNode.Symbol(glyph) to i + 1
    }

    // Spacing commands.
    SpaceMap[name]?.let { width ->
        return MathNode.Space(width) to i + 1
    }

    // Specialised forms.
    when (name) {
        SpecialCommands.FRAC -> {
            val (num, a) = nextArg(tokens, i + 1)
            val (den, b) = nextArg(tokens, a)
            return MathNode.Fraction(num, den) to b
        }
        SpecialCommands.SQRT -> {
            // Optional `[index]` — only if the next token is a Group whose source was `[...]`.
            // We don't track source, so we look for a Group that's all-text starting with `[`.
            // Simpler: support `\sqrt[n]{x}` by sniffing a Group token whose first inner token is
            // a Text starting with `[` and last ending with `]`. The TeX parser didn't special-case
            // `[]`, so the index would have been parsed as Text. We honour it here.
            val peek = tokens.getOrNull(i + 1)
            if (peek is TexToken.Group && peek.tokens.isNotEmpty()) {
                val first = peek.tokens.first()
                val last = peek.tokens.last()
                if (first is TexToken.Text && first.text.startsWith("[") &&
                    last is TexToken.Text && last.text.endsWith("]")
                ) {
                    val indexText = buildString {
                        append(first.text.substring(1))
                        for (k in 1 until peek.tokens.lastIndex) append(tokenText(peek.tokens[k]))
                        append(last.text.dropLast(1))
                    }
                    val (base, next) = nextArg(tokens, i + 2)
                    return MathNode.Sqrt(base, MathNode.Text(indexText.trim())) to next
                }
            }
            val (base, next) = nextArg(tokens, i + 1)
            return MathNode.Sqrt(base) to next
        }
        SpecialCommands.BINOM -> {
            val (a, p) = nextArg(tokens, i + 1)
            val (b, q) = nextArg(tokens, p)
            return MathNode.Binom(a, b) to q
        }
        SpecialCommands.MATHBB, SpecialCommands.MATHIT, SpecialCommands.MATHRM, SpecialCommands.MATHBF,
        SpecialCommands.TEXT, SpecialCommands.OPERATORNAME -> {
            val (arg, next) = nextArg(tokens, i + 1)
            // For \mathbb{X} we map single letters to blackboard-bold Unicode.
            if (name == SpecialCommands.MATHBB) {
                val s = nodeText(arg)
                val mapped = s.toCharArray().joinToString("") { bbChar(it) ?: it.toString() }
                return MathNode.Text(mapped) to next
            }
            // \mathrm, \mathit, \mathbf, \text, \operatorname — just keep the content; the
            // renderer styles it via the ambient style. We wrap in a Group so styling could be
            // applied per-node later if needed.
            return arg to next
        }
        SpecialCommands.LEFT -> {
            // \left<delim> ... \right<delim>. The delimiter following `\left` is typically a
            // single char, but the lexer may have fused it into a longer Text run with following
            // content (e.g. "( x " from `\left( x`). consumeDelimiter extracts just the first
            // char as the glyph and returns the leftover text so we can prepend it to the content.
            val (leftGlyph, afterLeft, leftLeftover) = consumeDelimiter(tokens, i + 1)
            val (content, afterContent) = buildSeq(tokens, afterLeft)
            val fullContent = if (leftLeftover.isNotEmpty()) {
                MathNode.Group(listOf(MathNode.Text(leftLeftover), content))
            } else {
                content
            }
            val rightTok = tokens.getOrNull(afterContent)
            if (rightTok is TexToken.Command && rightTok.name == SpecialCommands.RIGHT) {
                val (rightGlyph, afterRight, rightLeftover) = consumeDelimiter(tokens, afterContent + 1)
                val finalContent = if (rightLeftover.isNotEmpty()) {
                    MathNode.Group(listOf(fullContent, MathNode.Text(rightLeftover)))
                } else {
                    fullContent
                }
                return MathNode.Delimiter(leftGlyph, finalContent, rightGlyph) to afterRight
            }
            // No matching \right — degrade to just the content with the left glyph as text.
            return MathNode.Group(listOf(MathNode.Text(leftGlyph), fullContent)) to afterContent
        }
    }

    // Unknown command — keep as a generic Command node with no args, so the renderer can paint
    // the name verbatim. No characters are lost.
    return MathNode.Command(name, emptyList()) to i + 1
}

/** Function-name commands rendered as upright text. */
private val FunctionNames: Set<String> = setOf(
    "sin", "cos", "tan", "cot", "sec", "csc",
    "arcsin", "arccos", "arctan",
    "sinh", "cosh", "tanh",
    "log", "ln", "lg", "exp",
    "lim", "limsup", "liminf",
    "max", "min", "sup", "inf",
    "arg", "dim", "gcd", "hom", "ker",
    "deg", "det", "Pr",
)

/** Reads the next argument: a `{...}` group, or a single token (command/char). */
private fun nextArg(tokens: List<TexToken>, i: Int): Pair<MathNode, Int> {
    val tok = tokens.getOrNull(i) ?: return MathNode.Text("") to i
    return when (tok) {
        is TexToken.Group -> {
            val (inner, _) = buildSeq(tok.tokens, 0)
            inner to i + 1
        }
        is TexToken.Command -> buildCommand(tokens, i, tok.name).let { (node, next) ->
            // A bare command arg — make sure scripts aren't accidentally consumed by buildCommand.
            (node ?: MathNode.Text("")) to next
        }
        else -> {
            val (node, _) = buildOne(tokens, i)
            (node ?: MathNode.Text("")) to i + 1
        }
    }
}

/**
 * Reads a delimiter following `\left` / `\right`. Returns (glyph, nextIndex, leftover) where
 * [leftover] is the remaining text after the delimiter char when the delimiter was fused into a
 * longer Text run (e.g. "( x " → glyph "(", leftover " x "). Empty when the delimiter was a
 * standalone token.
 */
private fun consumeDelimiter(tokens: List<TexToken>, i: Int): Triple<String, Int, String> {
    val tok = tokens.getOrNull(i) ?: return Triple("", i, "")
    return when (tok) {
        is TexToken.Text -> {
            val key = tok.text
            // Single-char text — look up by literal char first, then fall back to the char itself.
            if (key.length == 1) {
                val glyph = DelimMap[key] ?: key
                return Triple(glyph, i + 1, "")
            }
            // Multi-char text — the first char is the delimiter, the rest is leftover content
            // that the caller must prepend to the delimited body so no characters are lost.
            val firstChar = key.substring(0, 1)
            val glyph = DelimMap[firstChar] ?: firstChar
            Triple(glyph, i + 1, key.substring(1))
        }
        is TexToken.Command -> {
            val cmd = "\\${tok.name}"
            DelimMap[cmd]?.let { return Triple(it, i + 1, "") }
            // Unknown — fall back to the command glyph if it's a symbol, else the raw name.
            val glyph = SymbolMap[tok.name] ?: tok.name
            Triple(glyph, i + 1, "")
        }
        else -> Triple("", i + 1, "")
    }
}

/** Approximate blackboard-bold mapping for ASCII letters. */
private val BbMap: Map<Char, String> = mapOf(
    'A' to "𝔸", 'B' to "𝔹", 'C' to "ℂ", 'D' to "𝔻", 'E' to "𝔼", 'F' to "𝔽",
    'G' to "𝔾", 'H' to "ℍ", 'I' to "𝕀", 'J' to "𝕁", 'K' to "𝕂", 'L' to "𝕃",
    'M' to "𝕄", 'N' to "ℕ", 'O' to "𝕆", 'P' to "ℙ", 'Q' to "ℚ", 'R' to "ℝ",
    'S' to "𝕊", 'T' to "𝕋", 'U' to "𝕌", 'V' to "𝕍", 'W' to "𝕎", 'X' to "𝕏",
    'Y' to "𝕐", 'Z' to "ℤ",
    'a' to "𝕒", 'b' to "𝕓", 'c' to "𝕔", 'd' to "𝕕", 'e' to "𝕖", 'f' to "𝕗",
    'g' to "𝕘", 'h' to "𝕙", 'i' to "𝕚", 'j' to "𝕛", 'k' to "𝕜", 'l' to "𝕝",
    'm' to "𝕞", 'n' to "𝕟", 'o' to "𝕠", 'p' to "𝕡", 'q' to "𝕢", 'r' to "𝕣",
    's' to "𝕤", 't' to "𝕥", 'u' to "𝕦", 'v' to "𝕧", 'w' to "𝕨", 'x' to "𝕩",
    'y' to "𝕪", 'z' to "𝕫",
)

private fun bbChar(c: Char): String? = BbMap[c]

private fun tokenText(tok: TexToken): String = when (tok) {
    is TexToken.Text -> tok.text
    is TexToken.Command -> "\\${tok.name}"
    is TexToken.Comment -> ""
    is TexToken.Group -> tok.tokens.joinToString("") { tokenText(it) }
    is TexToken.Superscript -> "^${tok.target.joinToString("") { tokenText(it) }}"
    is TexToken.Subscript -> "_${tok.target.joinToString("") { tokenText(it) }}"
}

/** Flattens a [MathNode] back to its text representation (for `\mathbb{X}` mapping, etc.). */
internal fun nodeText(node: MathNode): String = when (node) {
    is MathNode.Text -> node.text
    is MathNode.Identifier -> node.char
    is MathNode.Number -> node.text
    is MathNode.Symbol -> node.glyph
    is MathNode.Command -> "\\${node.name}"
    is MathNode.Fraction -> "${nodeText(node.numerator)}/${nodeText(node.denominator)}"
    is MathNode.Sqrt -> "√(${nodeText(node.base)})"
    is MathNode.Superscript -> "${nodeText(node.base)}^(${nodeText(node.target)})"
    is MathNode.Subscript -> "${nodeText(node.base)}_(${nodeText(node.target)})"
    is MathNode.SubSup -> "${nodeText(node.base)}_(${nodeText(node.sub)})^(${nodeText(node.sup)})"
    is MathNode.Binom -> "C(${nodeText(node.n)},${nodeText(node.k)})"
    is MathNode.BigOperator -> node.glyph
    is MathNode.Group -> node.nodes.joinToString("") { nodeText(it) }
    is MathNode.Delimiter -> node.left + nodeText(node.content) + node.right
    is MathNode.Space -> " "
}
