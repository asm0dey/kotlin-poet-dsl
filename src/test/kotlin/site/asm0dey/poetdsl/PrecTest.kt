package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.CodeBlock
import kotlin.test.Test
import kotlin.test.assertEquals

class PrecTest {
    private fun atom(name: String) = Expr(CodeBlock.of("%L", name), prec = Prec.ATOM)

    private val a = atom("a")
    private val b = atom("b")
    private val c = atom("c")

    @Test
    fun `higher precedence on the right needs no parens`() {
        assertEquals(
            "a + b * c",
            binaryExpr(a, "+", binaryExpr(b, "*", c, Prec.MULTIPLICATIVE), Prec.ADDITIVE).code.toString(),
        )
    }

    @Test
    fun `lower precedence operand is parenthesized`() {
        assertEquals(
            "(a + b) * c",
            binaryExpr(binaryExpr(a, "+", b, Prec.ADDITIVE), "*", c, Prec.MULTIPLICATIVE).code.toString(),
        )
    }

    @Test
    fun `same precedence on the right is parenthesized for left associative operators`() {
        assertEquals(
            "a - (b - c)",
            binaryExpr(a, "-", binaryExpr(b, "-", c, Prec.ADDITIVE), Prec.ADDITIVE).code.toString(),
        )
    }

    @Test
    fun `right associative operators do not parenthesize the right operand`() {
        val inner = binaryExpr(b, "?:", c, Prec.ELVIS, rightAssoc = true)
        assertEquals(
            "a ?: b ?: c",
            binaryExpr(a, "?:", inner, Prec.ELVIS, rightAssoc = true).code.toString(),
        )
    }

    /**
     * The other half of `rightAssoc`: `left.paren(prec + 1)`. Right-chaining (above) exercises the
     * *right* operand's `paren(prec)`; only a left-chained equal-precedence operand reaches this
     * branch, and without the parentheses the expression would re-associate into the other tree.
     */
    @Test
    fun `right associative operators parenthesize an equal precedence left operand`() {
        val inner = binaryExpr(a, "?:", b, Prec.ELVIS, rightAssoc = true)
        assertEquals(
            "(a ?: b) ?: c",
            binaryExpr(inner, "?:", c, Prec.ELVIS, rightAssoc = true).code.toString(),
        )
    }

    @Test
    fun `elvis binds tighter than comparison`() {
        val elvis = binaryExpr(a, "?:", b, Prec.ELVIS, rightAssoc = true)
        assertEquals("a ?: b < c", binaryExpr(elvis, "<", c, Prec.COMPARISON).code.toString())
    }
}
