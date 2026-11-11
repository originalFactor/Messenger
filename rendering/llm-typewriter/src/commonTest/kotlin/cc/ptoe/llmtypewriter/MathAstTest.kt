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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * Pure-logic tests for [buildMathAst] — verifies the AST folding for scripts, big operators,
 * fractions, sqrt, and the prefix-stability invariant.
 */
class MathAstTest {

    @Test
    fun emptyInput_yieldsEmptyText() {
        assertEquals(MathNode.Text(""), buildMathAst(emptyList()))
    }

    @Test
    fun plainText_yieldsText() {
        // A multi-letter run of letters becomes an Identifier (conventionally italic in math
        // typography). "hello" is all letters, so it's a single Identifier — not a Text node.
        assertEquals(MathNode.Identifier("hello"), buildMathAst(parseTex("hello")))
    }

    @Test
    fun singleLetter_yieldsIdentifier() {
        assertEquals(MathNode.Identifier("x"), buildMathAst(parseTex("x")))
    }

    @Test
    fun singleDigit_yieldsNumber() {
        assertEquals(MathNode.Number("3"), buildMathAst(parseTex("3")))
    }

    @Test
    fun mixedText_yieldsGroup() {
        val ast = buildMathAst(parseTex("x2"))
        assertIs<MathNode.Group>(ast)
        assertEquals(2, ast.nodes.size)
        assertEquals(MathNode.Identifier("x"), ast.nodes[0])
        assertEquals(MathNode.Number("2"), ast.nodes[1])
    }

    // --- Scripts ---

    @Test
    fun superscript_yieldsSuperscriptNode() {
        val ast = buildMathAst(parseTex("x^2"))
        assertEquals(MathNode.Superscript(MathNode.Identifier("x"), MathNode.Number("2")), ast)
    }

    @Test
    fun subscript_yieldsSubscriptNode() {
        val ast = buildMathAst(parseTex("x_2"))
        assertEquals(MathNode.Subscript(MathNode.Identifier("x"), MathNode.Number("2")), ast)
    }

    @Test
    fun bothScripts_yieldsSubSupNode() {
        // The killer feature: x_a^b folds into SubSup so the renderer can paint sub and sup in
        // the SAME COLUMN.
        val ast = buildMathAst(parseTex("x_a^b"))
        assertEquals(
            MathNode.SubSup(
                base = MathNode.Identifier("x"),
                sub = MathNode.Identifier("a"),
                sup = MathNode.Identifier("b"),
            ),
            ast,
        )
    }

    @Test
    fun bothScripts_reversedOrderAlsoYieldsSubSup() {
        // x^b_a should also fold into SubSup — order shouldn't matter.
        val ast = buildMathAst(parseTex("x^b_a"))
        assertEquals(
            MathNode.SubSup(
                base = MathNode.Identifier("x"),
                sub = MathNode.Identifier("a"),
                sup = MathNode.Identifier("b"),
            ),
            ast,
        )
    }

    @Test
    fun bracedScriptTarget_isParsedAsGroup() {
        val ast = buildMathAst(parseTex("x^{ab}"))
        assertIs<MathNode.Superscript>(ast)
        assertEquals(MathNode.Identifier("x"), ast.base)
        // `ab` is a multi-letter identifier.
        assertEquals(MathNode.Identifier("ab"), ast.target)
    }

    // --- Big operators ---

    @Test
    fun bigOperator_alone_yieldsBigOperatorNoLimits() {
        val ast = buildMathAst(parseTex("\\sum"))
        assertEquals(MathNode.BigOperator(name = "sum", glyph = "∑"), ast)
    }

    @Test
    fun bigOperator_withSubscript_foldsIntoLower() {
        val ast = buildMathAst(parseTex("\\sum_{i=0}"))
        assertIs<MathNode.BigOperator>(ast)
        assertEquals("sum", ast.name)
        assertEquals("∑", ast.glyph)
        assertNotNull(ast.lower)
        assertNull(ast.upper)
    }

    @Test
    fun bigOperator_withSuperscript_foldsIntoUpper() {
        val ast = buildMathAst(parseTex("\\sum^{n}"))
        assertIs<MathNode.BigOperator>(ast)
        assertNull(ast.lower)
        assertNotNull(ast.upper)
    }

    @Test
    fun bigOperator_withBothScripts_foldsIntoLowerAndUpper() {
        // The other killer feature: \sum_{i=0}^{n} folds scripts into lower/upper limits on the
        // operator itself, not as wrapping Subscript/Superscript nodes. The renderer can then
        // stack them above/below the glyph in display mode.
        val ast = buildMathAst(parseTex("\\sum_{i=0}^{n}"))
        assertIs<MathNode.BigOperator>(ast)
        assertEquals("sum", ast.name)
        assertEquals("∑", ast.glyph)
        assertNotNull(ast.lower, "lower limit (subscript) should be set")
        assertNotNull(ast.upper, "upper limit (superscript) should be set")
    }

    @Test
    fun bigOperator_withBothScripts_reversedOrder() {
        val ast = buildMathAst(parseTex("\\sum^{n}_{i=0}"))
        assertIs<MathNode.BigOperator>(ast)
        assertNotNull(ast.lower)
        assertNotNull(ast.upper)
    }

    @Test
    fun int_isRecognizedAsBigOperator() {
        val ast = buildMathAst(parseTex("\\int_0^1"))
        assertIs<MathNode.BigOperator>(ast)
        assertEquals("∫", ast.glyph)
    }

    @Test
    fun prod_isRecognizedAsBigOperator() {
        val ast = buildMathAst(parseTex("\\prod_{i=1}^n"))
        assertIs<MathNode.BigOperator>(ast)
        assertEquals("∏", ast.glyph)
    }

    // --- Fractions ---

    @Test
    fun fraction_yieldsFractionNode() {
        val ast = buildMathAst(parseTex("\\frac{a}{b}"))
        assertEquals(
            MathNode.Fraction(
                numerator = MathNode.Identifier("a"),
                denominator = MathNode.Identifier("b"),
            ),
            ast,
        )
    }

    @Test
    fun fraction_withComplexArgs() {
        val ast = buildMathAst(parseTex("\\frac{x+1}{y-2}"))
        assertIs<MathNode.Fraction>(ast)
        // Numerator and denominator are each Groups of [Identifier, Text(+/-), Number].
        assertIs<MathNode.Group>(ast.numerator)
        assertIs<MathNode.Group>(ast.denominator)
    }

    // --- Square root ---

    @Test
    fun sqrt_yieldsSqrtNode() {
        val ast = buildMathAst(parseTex("\\sqrt{x}"))
        assertEquals(MathNode.Sqrt(MathNode.Identifier("x"), index = null), ast)
    }

    // --- Symbols ---

    @Test
    fun alpha_yieldsSymbolNode() {
        val ast = buildMathAst(parseTex("\\alpha"))
        assertEquals(MathNode.Symbol("α"), ast)
    }

    @Test
    fun infty_yieldsSymbolNode() {
        val ast = buildMathAst(parseTex("\\infty"))
        assertEquals(MathNode.Symbol("∞"), ast)
    }

    @Test
    fun leq_yieldsSymbolNode() {
        val ast = buildMathAst(parseTex("\\leq"))
        assertEquals(MathNode.Symbol("≤"), ast)
    }

    @Test
    fun toArrow_yieldsSymbolNode() {
        val ast = buildMathAst(parseTex("\\to"))
        assertEquals(MathNode.Symbol("→"), ast)
    }

    // --- Function names ---

    @Test
    fun sin_yieldsTextNode() {
        // Function-name commands render as upright text, not as a single glyph symbol.
        val ast = buildMathAst(parseTex("\\sin"))
        assertEquals(MathNode.Text("sin"), ast)
    }

    @Test
    fun lim_yieldsTextNode() {
        val ast = buildMathAst(parseTex("\\lim"))
        assertEquals(MathNode.Text("lim"), ast)
    }

    // --- Delimiters ---

    @Test
    fun leftRight_yieldsDelimiterNode() {
        val ast = buildMathAst(parseTex("\\left( x \\right)"))
        assertIs<MathNode.Delimiter>(ast)
        assertEquals("(", ast.left)
        assertEquals(")", ast.right)
    }

    @Test
    fun leftRight_withBrackets() {
        val ast = buildMathAst(parseTex("\\left[ x \\right]"))
        assertIs<MathNode.Delimiter>(ast)
        assertEquals("[", ast.left)
        assertEquals("]", ast.right)
    }

    // --- mathbb ---

    @Test
    fun mathbbR_yieldsTextWithBlackboardBoldR() {
        val ast = buildMathAst(parseTex("\\mathbb{R}"))
        assertEquals(MathNode.Text("ℝ"), ast)
    }

    @Test
    fun mathbbN_yieldsTextWithBlackboardBoldN() {
        val ast = buildMathAst(parseTex("\\mathbb{N}"))
        assertEquals(MathNode.Text("ℕ"), ast)
    }

    // --- Spacing ---

    @Test
    fun thinSpace_yieldsSpaceNode() {
        val ast = buildMathAst(parseTex("a\\,b"))
        assertIs<MathNode.Group>(ast)
        assertEquals(3, ast.nodes.size)
        assertIs<MathNode.Space>(ast.nodes[1])
        assertEquals(MathNode.SpaceWidth.Thin, (ast.nodes[1] as MathNode.Space).width)
    }

    // --- Unknown commands ---

    @Test
    fun unknownCommand_yieldsCommandNode() {
        val ast = buildMathAst(parseTex("\\foobar"))
        assertEquals(MathNode.Command("foobar", emptyList()), ast)
    }

    // --- Prefix stability ---

    @Test
    fun prefixStability_growingSum() {
        val full = "\\sum_{i=0}^{n} x_i"
        val fullAst = buildMathAst(parseTex(full))

        // The full AST should be a Group containing [BigOperator, Text(" "), Subscript(x, i)].
        assertIs<MathNode.Group>(fullAst)

        // Each prefix should produce an AST whose leading nodes match. We can't easily assert
        // deep equality because the trailing node may be different (open token), but we can check
        // that the first node (when present) is a BigOperator once `\\sum` is fully formed.
        val atSum = buildMathAst(parseTex("\\sum"))
        assertIs<MathNode.BigOperator>(atSum)
        assertNull(atSum.lower)
        assertNull(atSum.upper)

        val atSumSub = buildMathAst(parseTex("\\sum_{i=0}"))
        assertIs<MathNode.BigOperator>(atSumSub)
        assertNotNull(atSumSub.lower)
        assertNull(atSumSub.upper)

        val atSumSubSup = buildMathAst(parseTex("\\sum_{i=0}^{n}"))
        assertIs<MathNode.BigOperator>(atSumSubSup)
        assertNotNull(atSumSubSup.lower)
        assertNotNull(atSumSubSup.upper)
    }

    // --- Combined expressions ---

    @Test
    fun quadraticFormula_parsesWithoutError() {
        val ast = buildMathAst(parseTex("x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}"))
        // Just verify it parses without throwing — the structure is a Group of [x, =, Fraction].
        assertIs<MathNode.Group>(ast)
    }

    @Test
    fun summation_parsesCorrectly() {
        val ast = buildMathAst(parseTex("\\sum_{i=1}^{n} i^2"))
        assertIs<MathNode.Group>(ast)
        val first = ast.nodes[0]
        assertIs<MathNode.BigOperator>(first)
        assertEquals("∑", first.glyph)
        assertNotNull(first.lower)
        assertNotNull(first.upper)
    }

    @Test
    fun integral_parsesCorrectly() {
        val ast = buildMathAst(parseTex("\\int_0^{\\infty} e^{-x} dx"))
        assertIs<MathNode.Group>(ast)
        val first = ast.nodes[0]
        assertIs<MathNode.BigOperator>(first)
        assertEquals("∫", first.glyph)
    }

    @Test
    fun eulerIdentity_parsesCorrectly() {
        val ast = buildMathAst(parseTex("e^{i\\pi} + 1 = 0"))
        assertIs<MathNode.Group>(ast)
    }
}
