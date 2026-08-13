package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The precedence matrix: one place where every level of [Prec] is exercised against its
 * neighbours, in both nesting directions, through the *public* operators rather than
 * [binaryExpr] directly.
 *
 * Any failure here is a real precedence bug in `Prec.kt`/`Operators.kt` — fix the implementation,
 * not the expectation, unless the expectation contradicts the Kotlin grammar.
 */
class PrecedenceMatrixTest {
    private val a = expression("a")
    private val b = expression("b")
    private val c = expression("c")
    private val d = expression("d")

    @Test
    fun `arithmetic nesting`() {
        assertEquals("a + b * c", (a + b * c).toString())
        assertEquals("(a + b) * c", ((a + b) * c).toString())
        assertEquals("a * b + c", (a * b + c).toString())
        assertEquals("a - (b - c)", (a - (b - c)).toString())
        assertEquals("a / b % c", (a / b % c).toString())
    }

    /**
     * The parenthesizing branch of [unaryMinus] — `paren(Prec.PREFIX)` with an operand that binds
     * looser. Only `-a` was pinned before; `not()`'s twin (`!(a && b)`) had been covered all along.
     */
    @Test
    fun `prefix operators parenthesize a looser operand`() {
        assertEquals("-a", (-a).toString())
        assertEquals("-(a + b)", (-(a + b)).toString())
        assertEquals("-a.f()", (-a.call("f")).toString())
        assertEquals("!a", a.not().toString())
        assertEquals("!(a && b)", (a and b).not().toString())
        // PREFIX ↔ MULTIPLICATIVE: the immediate neighbour below prefix, not previously sampled —
        // the earlier cases jump straight from prefix to additive and to postfix.
        assertEquals("-(a * b)", (-(a * b)).toString())
        // DISJUNCTION under PREFIX: `not()`'s parenthesizing branch was only pinned over
        // conjunction (`!(a && b)` above); disjunction is a separate, looser neighbour.
        assertEquals("!(a || b)", (a or b).not().toString())
    }

    @Test
    fun `comparison over arithmetic`() {
        assertEquals("a + b < c", (a + b lt c).toString())
        assertEquals("a < b == c", ((a lt b) eq c).toString())
        assertEquals("a == (b == c)", (a eq (b eq c)).toString())
        // EQUALITY as the *left* operand of COMPARISON — the reverse of the case above. EQUALITY
        // binds looser than COMPARISON, so nesting it on the left needs parentheses it does not
        // need on the right.
        assertEquals("(a == b) < c", ((a eq b) lt c).toString())
    }

    @Test
    fun `logical over comparison`() {
        assertEquals("a < b && c > d", ((a lt b) and (c gt d)).toString())
        assertEquals("!(a && b) || c", ((a and b).not() or c).toString())
        assertEquals("a || b && c", (a or (b and c)).toString())
        assertEquals("(a || b) && c", ((a or b) and c).toString())
    }

    /**
     * `?:` sits between `+` and the comparisons: looser than every arithmetic operator, tighter
     * than `<`, `==`, `&&` and `||`.
     *
     * The equality and comparison cases render *without* parentheses, against the reading that
     * `(a ?: b) == c` needs them. Kotlin's grammar puts `elvisExpression` below `infixOperation`,
     * which is below `comparison`, which is below `equality` — so `a ?: b == c` already groups as
     * `(a ?: b) == c` and the parentheses would be noise. `CompileTest.precedence output is valid
     * kotlin` proves the grouping rather than asserting it: it returns the emitted `a ?: b == c`
     * from a `Boolean` function, which only type-checks under that reading.
     */
    @Test
    fun `elvis combinations`() {
        assertEquals("a ?: b + c", (a elvis (b + c)).toString())
        assertEquals("(a ?: b) + c", ((a elvis b) + c).toString())
        assertEquals("a ?: b == c", ((a elvis b) eq c).toString())
        assertEquals("a ?: b < c", ((a elvis b) lt c).toString())
        assertEquals("a ?: b && c", ((a elvis b) and c).toString())
        assertEquals("a ?: (b && c)", (a elvis (b and c)).toString())
    }

    /**
     * Elvis is right-associative, so the natural chain needs no parentheses and the *left*-chained
     * one does. The right-chained half was pinned from Task 4 on; the left-chained half — the
     * `left.paren(prec + 1)` branch of [binaryExpr] — is the case that was only ever hand-verified.
     *
     * `(a ?: b) ?: c` and `a ?: (b ?: c)` mean the same thing — elvis is semantically associative,
     * so dropping the parentheses here would not actually change what either chain evaluates to.
     * They are still the correct parenthesization, and worth pinning on their own terms: this is the
     * `left.paren(prec + 1)` branch of the *generic* [binaryExpr], reachable only from a
     * right-associative operator chained on the left, and the assertion is about that mechanism
     * (and about [binaryExpr] rendering the minimum parentheses Kotlin's grammar requires) rather
     * than about `?:` specifically — a future right-associative operator with a non-associative
     * meaning would have no other test covering this shape.
     */
    @Test
    fun `elvis associativity in both directions`() {
        assertEquals("a ?: b ?: c", (a elvis (b elvis c)).toString())
        assertEquals("(a ?: b) ?: c", ((a elvis b) elvis c).toString())
        assertEquals("(a ?: b) ?: c ?: d", ((a elvis b) elvis (c elvis d)).toString())
    }

    @Test
    fun `calls and value invocation bind tightest`() {
        assertEquals("a.f() + b.g()", (a.call("f") + b.call("g")).toString())
        assertEquals("(a + b).f()", (a + b).call("f").toString())
        assertEquals("(a ?: b).f()", (a elvis b).call("f").toString())
        assertEquals("(a ?: b)(1)", (a elvis b)(1.lit).toString())
        assertEquals("a?.f().g()", a.safeCall("f").call("g").toString())
        assertEquals("(a && b).f", (a and b).prop("f").toString())
    }

    @Test
    fun `escape hatch preserves placeholders`() {
        val out = file("com.example", "Esc") {
            `fun`("f", param("xs", LIST.parameterizedBy(ANY))) { xs ->
                +expression("%L.filterIsInstance<%T>()", xs, reference<CharSequence>())
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Any
            import kotlin.CharSequence
            import kotlin.collections.List

            public fun f(xs: List<Any>) {
              xs.filterIsInstance<CharSequence>()
            }

            """.trimIndent(),
            out,
        )
    }

    /**
     * The escape hatch's `prec` parameter is what lets a raw fragment take part in the matrix at
     * all: left at [Prec.ATOM] the DSL believes the string binds tightest and emits `a + b * c`
     * for what the author wrote as `(a + b)`.
     */
    @Test
    fun `escape hatch precedence is declared by the caller`() {
        val raw = expression("a + b")
        assertEquals("a + b * c", (raw * c).toString())
        assertEquals("(a + b) * c", (expression("a + b", prec = Prec.ADDITIVE) * c).toString())
    }
}
