package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.INT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OperatorsTest {
    private val a = expression("a")
    private val b = expression("b")
    private val c = expression("c")

    @Test
    fun `arithmetic`() {
        assertEquals("a + b", (a + b).toString())
        assertEquals("a - b", (a - b).toString())
        assertEquals("a * b", (a * b).toString())
        assertEquals("a / b", (a / b).toString())
        assertEquals("a % b", (a % b).toString())
        assertEquals("-a", (-a).toString())
        // `unaryMinus`'s parenthesizing branch, the twin of `not()`'s `!(a && b)` below.
        assertEquals("-(a + b)", (-(a + b)).toString())
    }

    @Test
    fun `comparison and equality use infix names`() {
        assertEquals("a == b", (a eq b).toString())
        assertEquals("a != b", (a neq b).toString())
        assertEquals("a < b", (a lt b).toString())
        assertEquals("a <= b", (a le b).toString())
        assertEquals("a > b", (a gt b).toString())
        assertEquals("a >= b", (a ge b).toString())
        assertEquals(BOOLEAN, (a lt b).type)
    }

    @Test
    fun `logical operators and negation`() {
        assertEquals("a && b", (a and b).toString())
        assertEquals("a || b", (a or b).toString())
        assertEquals("!a", a.not().toString())
        assertEquals("!(a && b)", (a and b).not().toString())
    }

    @Test
    fun `elvis is right associative and binds tighter than comparison`() {
        assertEquals("a ?: b", (a elvis b).toString())
        assertEquals("a ?: b ?: c", (a elvis (b elvis c)).toString())
        // Left-chained: right-associativity means *this* side needs the parentheses.
        assertEquals("(a ?: b) ?: c", ((a elvis b) elvis c).toString())
        assertEquals("a ?: b < c", ((a elvis b) lt c).toString())
    }

    @Test
    fun `conjunction binds tighter than disjunction`() {
        assertEquals("a && b || c", ((a and b) or c).toString())
        assertEquals("a && (b || c)", (a and (b or c)).toString())
    }

    @Test
    fun `arithmetic keeps the operand type when both agree`() {
        assertEquals(INT, (1.lit + 2.lit).type)
        assertNull((1.lit + expression("x")).type)
    }
}
