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
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

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
 * **Task 22 re-ran that measurement in this suite and widened it**, because the hand-kept list it
 * produced had drifted from it in three different directions at once. The last test in this file is
 * the census: 153 pairs of eighteen values — `inline` and `fun` join the sixteen — in a **nested**
 * class rather than at file level, so that `protected` and `inner` are legal and a container fact
 * cannot be counted as a pair fact. It renders every pair through this DSL and compiles every render
 * on all three frontends on every run. The file-level rows below are kept because their prose is the
 * argument for two guards that were never written; the census is what the open list is now read off.
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
     * measuring the neighbours rather than by reading the table.
     *
     * **"On every form" was a sampled claim and it was false.** E3 measured a class, a member
     * function and a member property, and `` `typealias` `` — the one construct E3 itself added —
     * escaped, because it checks its modifiers against `TYPE_ALIAS_MODIFIERS` directly and never
     * calls `checkModifiers`, which is where the pair rule hangs. So the enumeration is now
     * **derived**: every construct in this DSL with a `Modifiers` slot, read off the generated
     * overloads and the hand-written surface, is on the list below and every one of them is
     * exercised. The list is `` `class` ``/`klass`, `` `object` ``, `` `interface` ``,
     * `` `constructor` ``/`ctor`, `` `fun` ``/`func`, `property`/`` `val` ``/`` `var` ``,
     * `` `typealias` ``, and the three detached builders `typeSpec`, `funSpec` and `propertySpec`.
     * Nothing else takes one: a `param`'s modifier slot is a **single** `KModifier` restricted to
     * `{VARARG, NOINLINE, CROSSINLINE}`, so `class C(public private val x: Int)` — *modifier 'public'
     * is incompatible with 'private'* on all three frontends — has no spelling here at all, and an
     * `enumEntry` and a `contextParameter` take no modifiers.
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
            // …and the seven forms E3's sample left out, each with the same pair.
            val pair = Modifiers(setOf(a, b))
            assertFailsWith<IllegalStateException>("object $a + $b") {
                file("com.example", "A") { `object`(pair, "O") { } }
            }
            assertFailsWith<IllegalStateException>("interface $a + $b") {
                file("com.example", "A") { `interface`(pair, "I") { } }
            }
            assertFailsWith<IllegalStateException>("constructor $a + $b") {
                file("com.example", "A") {
                    `class`("C") { `constructor`(pair, param("q", INT)) { } }
                }
            }
            assertFailsWith<IllegalStateException>("var $a + $b") {
                file("com.example", "A") {
                    `class`("C") { `var`(pair, "p", INT, init = 1.lit) }
                }
            }
            assertFailsWith<IllegalStateException>("typeSpec $a + $b") { typeSpec(pair, name = "C") { } }
            assertFailsWith<IllegalStateException>("funSpec $a + $b") {
                funSpec(pair, name = "f", returns = INT) { ret(1.lit) }
            }
            assertFailsWith<IllegalStateException>("propertySpec $a + $b") {
                propertySpec(pair, "p", INT, init = 1.lit)
            }
            // `typealias` is the one E3 shipped open, and PROTECTED is subtracted from its rows for
            // a reason of its own rather than as an exemption: KotlinPoet's `ALLOWABLE_MODIFIERS`
            // has no PROTECTED at all, so a pair containing it draws the render-gap refusal first.
            // The four pairs that do not contain it reach the pair rule and are asserted here; the
            // full set, with the language rows and the controls, is in `TypeAliasesTest`.
            if (a != KModifier.PROTECTED && b != KModifier.PROTECTED) {
                val e2 = assertFailsWith<IllegalStateException>("typealias $a + $b") {
                    file("com.example", "A") { `typealias`(pair, "S", com.squareup.kotlinpoet.STRING) }
                }
                assertTrue("one visibility" in e2.message!!, e2.message!!)
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
     * **The census, run rather than quoted** — and it is the reason the test above it is the only
     * hand-written pair list left in this file.
     *
     * Three records of "which modifier pairs does this DSL still render invalid" existed before
     * Task 22 and no two of them agreed. D43's *Still open* said ten and enumerated nine, dropping
     * `sealed`×`data` in transcription. The test that stood here asserted five rows, and two of them
     * — `open`×`enum` and `sealed`×`enum` — were on no list, while two of D43's — `data`×`expect`
     * and `expect`×`actual` — are not live pair rows at all: this DSL already refuses the first (a
     * `val` constructor parameter on an `expect` class, D36) and the second only fails alongside the
     * `expect` family's own container and file-layout errors. Every one of the three was a list
     * maintained by hand beside a measurement nobody re-ran.
     *
     * So this test **is** the measurement. All 153 unordered pairs of the eighteen `KModifier`
     * values that reach a `class` declaration, each rendered through this DSL as a **nested** class
     * — the container in which `protected` and `inner` are legal, so that a container fact cannot
     * masquerade as a pair fact — and each render compiled on the JVM, on Kotlin/JS and on
     * Kotlin/Wasm with `-Xmulti-platform` on all three. The partition it asserts:
     *
     *     153 pairs
     *      32 the DSL refuses           6 visibility, 17 `fun`, 6 `inner`-kind, 3 expect-property
     *     121 render
     *      44   compile everywhere
     *       5   fail on the JVM only    `value` without `@JvmInline` — D37, a platform rule
     *      72   fail on all three
     *        42     contain `expect`, `actual` or `external` — D40's and D41's families, where the
     *               row is invalid for a reason that is not the pair
     *        30     are pair facts, and are what this DSL still renders and no frontend accepts
     *
     * **Twenty of those thirty were recorded nowhere** before Task 22 — every one of them in the
     * *not applicable to* family rather than the *incompatible with* one, which is the same blind
     * spot that hid `inner`×`annotation`: D42's list was built by grepping for one sentence.
     *
     * Nothing here is guarded. The point of asserting it is that the open list can no longer drift
     * from the language: a row that stops being invalid fails this test, a row that starts being
     * invalid fails it, and a guard added later fails it too — which is the notice a guard should
     * get, since after the API lock a refusal can still be added but a claim cannot be unmade.
     */
    @Test
    fun `the 153-pair census says exactly which pairs this DSL still renders invalid`() {
        // The eighteen values a `class` declaration accepts. `inline` is `value`'s old spelling and
        // is a distinct render, so it is a distinct row; `fun` is here because `fun interface` makes
        // it look like a classifier modifier and every one of its 17 pairs is refused.
        val axis = listOf(
            PUBLIC, KModifier.PROTECTED, PRIVATE, INTERNAL,
            FINAL, OPEN, ABSTRACT, SEALED,
            ENUM, KModifier.ANNOTATION, DATA, KModifier.VALUE, KModifier.INLINE, KModifier.FUN,
            KModifier.INNER, KModifier.EXPECT, KModifier.ACTUAL, KModifier.EXTERNAL,
        )
        fun name(a: KModifier, b: KModifier) = listOf(a.name, b.name).sorted().joinToString("+")

        val refused = mutableListOf<String>()
        val clean = mutableListOf<String>()
        val jvmOnly = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        for (i in axis.indices) for (j in i + 1 until axis.size) {
            val a = axis[i]
            val b = axis[j]
            val mods = Modifiers(linkedSetOf(a, b))
            val needsParam = DATA in mods.set || KModifier.VALUE in mods.set || KModifier.INLINE in mods.set
            val rendered = try {
                file("com.example", "A") {
                    `class`("Outer") {
                        if (needsParam) {
                            `class`(mods, "M", param(ParamKind.VAL, "a", INT)) { }
                        } else {
                            `class`(mods, "M") { }
                        }
                    }
                }.toString()
            } catch (e: IllegalStateException) {
                refused += name(a, b)
                continue
            }
            val ok = KotlinCompilation.ExitCode.OK
            val onJvm = compileMultiplatform(rendered).exitCode == ok
            val onJs = compileJsMultiplatform(rendered).exitCode == ok
            val onWasm = compileWasmMultiplatform(rendered).exitCode == ok
            when {
                onJvm && onJs && onWasm -> clean += name(a, b)
                !onJvm && onJs && onWasm -> jvmOnly += name(a, b)
                !onJvm && !onJs && !onWasm -> invalid += name(a, b)
                else -> fail("a new frontend split, which the pair axis has never had:\n$rendered")
            }
        }
        assertEquals(153, refused.size + clean.size + jvmOnly.size + invalid.size)

        // 6 visibility pairs (guarded), 17 `fun` pairs, 6 `inner`-kind pairs (guarded by
        // `checkInnerKindPair`), and the three `expect` rows whose `val` parameter D36 refuses.
        assertEquals(32, refused.size, refused.sorted().joinToString("\n"))
        assertEquals(44, clean.size, clean.sorted().joinToString("\n"))
        // D37's platform rule, reached through five spellings: a `value class` needs `@JvmInline`
        // on the JVM and nowhere else, and that is the caller's annotation to add.
        assertEquals(
            listOf("FINAL+VALUE", "INTERNAL+VALUE", "PRIVATE+VALUE", "PROTECTED+VALUE", "PUBLIC+VALUE"),
            jvmOnly.sorted(),
        )
        assertEquals(72, invalid.size, invalid.sorted().joinToString("\n"))

        // The thirty that are pair facts — the open list, in full, for the first time.
        val family = setOf("EXPECT", "ACTUAL", "EXTERNAL")
        val pairFacts = invalid.filter { row -> row.split("+").none { it in family } }
        assertEquals(
            listOf(
                // …the ten that were on a list somewhere: eight in D42's *incompatible with*
                // enumeration, two more only in the test that used to stand here.
                "ABSTRACT+DATA", "FINAL+OPEN", "FINAL+SEALED", "ABSTRACT+FINAL",
                "OPEN+SEALED", "DATA+OPEN", "DATA+SEALED", "DATA+VALUE",
                "ENUM+OPEN", "ENUM+SEALED",
                // …and the twenty that were on none, all of them *not applicable to* rows.
                "ABSTRACT+ANNOTATION", "ABSTRACT+ENUM", "ABSTRACT+VALUE", "ABSTRACT+INLINE",
                "ANNOTATION+FINAL",
                "ANNOTATION+OPEN", "OPEN+VALUE", "INLINE+OPEN",
                "ANNOTATION+SEALED", "SEALED+VALUE", "INLINE+SEALED",
                "ANNOTATION+ENUM", "DATA+ENUM", "ENUM+VALUE", "ENUM+INLINE",
                "ANNOTATION+DATA", "ANNOTATION+VALUE", "ANNOTATION+INLINE",
                "DATA+INLINE", "INLINE+VALUE",
            ).sorted(),
            pairFacts.sorted(),
        )
    }
}
