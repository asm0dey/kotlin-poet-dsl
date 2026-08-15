package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.DATA
import com.squareup.kotlinpoet.KModifier.ENUM
import com.squareup.kotlinpoet.KModifier.FINAL
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.KModifier.OPEN
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.PUBLIC
import com.squareup.kotlinpoet.KModifier.SEALED
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **The pair axis's control rows** — the one measurement debt E2 left, closed here rather than
 * carried into the API lock.
 *
 * D42 recorded 42 genuinely pair-only invalid renders in four families and said each was left undone
 * because "none of the four has had its **control rows** measured". It also proposed what a guard for
 * each would be. Two of those proposals are **wrong**, and the only thing that could have said so is
 * the valid neighbour.
 *
 * The measurement: all **120 unordered pairs** of the sixteen `KModifier` values that reach a `class`
 * declaration — `public`, `protected`, `private`, `internal`, `final`, `open`, `abstract`, `sealed`,
 * `enum`, `annotation`, `data`, `value`, `inner`, `expect`, `actual`, `external` — each rendered as a
 * class declaration carrying the minimum that makes its kind valid, and each judged on `kotlinc`,
 * `kotlinc-js` and `kotlinc-wasm` 2.4.10 with `-Xmulti-platform`, one file per cell.
 *
 * **108 of the 120 are unanimous. The 12 that split do so along two families already recorded as
 * single-modifier facts** and add nothing new to the pair axis: a `value class` without `@JvmInline`
 * is *value classes without '@JvmInline' annotation are not yet supported* on the JVM whatever else
 * is on it, and `external class` is *modifier 'external' is not applicable to 'class'* on the JVM and
 * clean on the other two (D37).
 *
 * **D42's proposal for the inheritance family — "at most one of FINAL/OPEN/ABSTRACT/SEALED" — would
 * have been a false rejection**, and so would a "visibility and inheritance are separate groups"
 * reading of the first: `open abstract class M` and `abstract sealed class M` are both clean on all
 * three frontends, as are `final enum class M` and `final data class M(val a: Int)`. Every row below
 * is one this DSL renders today and no guard refuses; the point of pinning them is that the pair rule
 * D42 sketched cannot be added later without this test failing first.
 */
@OptIn(ExperimentalCompilerApi::class)
class ModifierPairTest {
    private fun renderClass(vararg modifiers: KModifier, params: Boolean = false): String =
        file("com.example", "A") {
            if (params) {
                `class`(Modifiers(modifiers.toSet()), "M", param(ParamKind.VAL, "a", INT)) { }
            } else {
                `class`(Modifiers(modifiers.toSet()), "M") { }
            }
        }.toString()

    /**
     * The four rows that falsify D42's sketched inheritance rule. Each is rendered by this DSL and
     * compiled, so "the guard was never written" and "the guard would have been wrong" are separate
     * claims and both are checked.
     */
    @Test
    fun `the inheritance pairs D42 would have refused are valid Kotlin`() {
        val rows = listOf(
            renderClass(OPEN, ABSTRACT),
            renderClass(ABSTRACT, SEALED),
            renderClass(FINAL, ENUM),
            renderClass(FINAL, DATA, params = true),
        )
        rows.forEach {
            assertCompiles(it)
            assertCompilesEverywhereButJvm(it)
        }
        assertTrue("public abstract sealed class M" in rows[1], rows[1])
    }

    /** A visibility beside any inheritance or kind modifier is clean — 24 of the 120 cells. */
    @Test
    fun `a visibility pairs with every inheritance and kind modifier`() {
        listOf(PUBLIC, PRIVATE, INTERNAL).forEach { visibility ->
            listOf(FINAL, OPEN, ABSTRACT, SEALED, ENUM).forEach { other ->
                assertCompiles(renderClass(visibility, other))
            }
            assertCompiles(renderClass(visibility, DATA, params = true))
        }
    }

    /**
     * The family that is **closed**, and the live invalid render that closing it removed.
     *
     * `` `class`(Modifiers(setOf(PUBLIC, PRIVATE)), "M") `` rendered `public private class M`, which
     * no frontend accepts — the pair axis's first concrete Global Constraint 26 violation, found by
     * measuring the neighbours rather than by reading the table. All six visibility pairs are refused
     * now, on every form: a class, a member function and a member property were each measured with
     * one sentence throughout.
     */
    @Test
    fun `two visibilities on one declaration are refused, on every form`() {
        val pairs = listOf(
            PUBLIC to PRIVATE,
            PUBLIC to INTERNAL,
            PUBLIC to KModifier.PROTECTED,
            PRIVATE to INTERNAL,
            KModifier.PROTECTED to PRIVATE,
            KModifier.PROTECTED to INTERNAL,
        )
        pairs.forEach { (a, b) ->
            val e = assertFailsWith<IllegalStateException>("$a + $b was rendered") {
                renderClass(a, b)
            }
            assertTrue("one visibility" in e.message!!, e.message!!)
            assertFailsWith<IllegalStateException>("fun $a + $b") {
                file("com.example", "A") {
                    `class`("C") { `fun`(Modifiers(setOf(a, b)), "f", returns = INT) { ret(1.lit) } }
                }
            }
            assertFailsWith<IllegalStateException>("val $a + $b") {
                file("com.example", "A") {
                    `class`("C") { `val`(Modifiers(setOf(a, b)), "p", INT, init = 1.lit) }
                }
            }
        }
        // The control rows: one visibility on each of the three forms still renders and compiles.
        listOf(PUBLIC, PRIVATE, INTERNAL).forEach { v ->
            assertCompiles(renderClass(v))
            assertCompiles(
                file("com.example", "A") {
                    `class`("C") {
                        `fun`(v, "f", returns = INT) { ret(1.lit) }
                        `val`(v, "p", INT, init = 1.lit)
                    }
                }.toString(),
            )
        }
    }

    /**
     * …and the families that are **not** closed, stated so the zero is not mistaken for coverage. The
     * full 120-cell table is in D43; these are the rows this DSL still renders and no frontend
     * accepts, each one a *kind* or *inheritance* pair whose guard needs a per-form measurement this
     * round did not run — on a function `final abstract` draws the container's sentence rather than
     * the pair's, so the class rows do not transfer.
     */
    @Test
    fun `the inheritance and kind pairs are still open, and this test says which`() {
        val open = listOf(
            listOf(FINAL, OPEN),
            listOf(FINAL, SEALED),
            listOf(OPEN, SEALED),
            listOf(OPEN, ENUM),
            listOf(SEALED, ENUM),
        )
        open.forEach { pair ->
            val rendered = renderClass(*pair.toTypedArray())
            assertTrue(
                compile(rendered).exitCode !=
                    com.tschuchort.compiletesting.KotlinCompilation.ExitCode.OK,
                "this row is no longer invalid; D43's open list needs updating:\n$rendered",
            )
        }
    }
}
