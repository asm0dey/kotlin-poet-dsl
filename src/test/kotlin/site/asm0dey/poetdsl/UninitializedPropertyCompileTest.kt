package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.EXPECT
import com.squareup.kotlinpoet.KModifier.LATEINIT
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `kotlinc`'s verdict on **the DSL's own output** at both sides of E2b item 5's boundary.
 *
 * Written against DSL output rather than hand-written Kotlin on purpose. A raw-string snippet proves
 * the *language* rule is real, which is worth something, but it cannot catch a guard whose boundary
 * is in the wrong place — the guard could exempt one modifier too many or one container too few and
 * every raw snippet would still pass. Only compiling what the DSL renders for each exempt case
 * closes that.
 */
@OptIn(ExperimentalCompilerApi::class)
class UninitializedPropertyCompileTest {
    /**
     * Everything the guard **accepts** with no initializer, delegate or getter, rendered by the DSL
     * and handed to kotlinc.
     *
     * `EXPECT` is missing from *this* test and has one of its own, because it cannot be compiled to
     * an OK exit code: a single compilation unit has no platform source set to hold the `actual`.
     * It is not, as E2b recorded, unmeasurable — `-Xmulti-platform` gets the frontend past
     * *'expect' and 'actual' declarations can be used only in multiplatform projects*, and the
     * diagnostics that do and do not appear after that answer the question. See
     * `an expect class exempts every classifier inside it`.
     */
    @Test
    fun `every exempt shape the guard lets through compiles`() {
        val spec = file("com.example", "Exempt") {
            `interface`("I") {
                `val`("a", INT)
                `var`("b", INT)
            }
            `class`(ABSTRACT, "C") {
                `val`(ABSTRACT, "c", INT)
                `var`(ABSTRACT, "d", INT)
                `var`(LATEINIT, "e", STRING)
            }
            // And the three ways to give a property a value, which the guard never sees.
            `val`("f", INT, init = 1.lit)
            `val`("g", INT, by = expression("lazy·{·2·}"))
            `val`("h", INT) { ret(3.lit) }
        }
        assertCompiles(spec.toString())
    }

    /**
     * The `expect` exemption, **measured** — which the E2b report and this round's brief both said
     * was impossible. It is, with `-Xmulti-platform`; see [compileMultiplatform] for why the exit
     * code is not the thing to read.
     *
     * Two compilations of DSL output, differing only in whether the innermost property carries an
     * initializer, settle the whole question:
     *
     * - without one, the compiler says nothing about initialization anywhere in the tree — so a
     *   property with no value inside an `expect class`, inside a class nested in one, and inside
     *   the companion object of one, is all accepted;
     * - with one, it answers *expected property cannot have an initializer* — which is the
     *   `expect`-specific rule, and its firing on a **nested** class's property is the direct
     *   evidence that a classifier inside an `expect class` is itself implicitly `expect`.
     *
     * The control at the bottom is the same shape with the `expect` taken off the outer class, where
     * the ordinary rule fires instead.
     */
    @Test
    fun `an expect class exempts every classifier inside it`() {
        fun render(inner: Expr?) = file("com.example", "Expected") {
            `class`(EXPECT, "E") {
                `class`("N") { `val`("x", INT, init = inner) }
                companionObject { `val`("y", INT, init = inner) }
            }
        }.toString()

        val bare = compileMultiplatform(render(null)).messages
        assertFalse("must be initialized" in bare, bare)
        assertFalse("cannot have an initializer" in bare, bare)

        val initialized = compileMultiplatform(render(1.lit)).messages
        // kctfork capitalises the compiler's first word, so the assertion starts one word in.
        assertTrue("property cannot have an initializer" in initialized, initialized)

        val plain = compileMultiplatform(
            file("com.example", "Plain") {
                `class`("E") { `class`("N") { `val`("x", INT, init = 1.lit) } }
            }.toString().replace("public val x: Int = 1", "public val x: Int"),
        ).messages
        assertTrue("must be initialized" in plain, plain)
    }

    /**
     * The other direction, and the reason the guard exists: the exact text the DSL used to render
     * for each rejected container, compiled to show kotlinc refuses it. These are the renders
     * captured from the `assertFailsWith` failures in `UninitializedPropertyTest` before the guard
     * landed — file level, class, object, companion object, nested class.
     */
    @Test
    fun `every shape the guard rejects is one kotlinc rejects`() {
        listOf(
            "public val x: Int",
            "public class C {\n  public var x: Int\n}",
            "public object O {\n  public val x: Int\n}",
            "public class C {\n  public companion object {\n    public val x: Int\n  }\n}",
            "public class E {\n  public class N {\n    public val x: Int\n  }\n}",
            // The nearest miss: an interface's *companion* object is not the interface body, and
            // does not inherit its exemption.
            "public interface I {\n  public companion object {\n    public val x: Int\n  }\n}",
            // The two renders the old remedy sentence recommended, both of which the DSL produced
            // without complaint until this round: `lateinit` on a `val`, and `abstract` in a
            // container that cannot hold it (file level, a plain class, an object, a companion).
            "public lateinit val x: String",
            "public abstract val x: Int",
            "public class C {\n  public abstract val x: Int\n}",
            "public object O {\n  public abstract val x: Int\n}",
            "public class C {\n  public companion object {\n    public abstract val x: Int\n  }\n}",
            // And the one the primitive-type rule still lets through — named in the remedy, not
            // guarded. Recorded here so the gap is a measurement rather than an oversight.
            "public lateinit var x: Int",
        ).forEach { source ->
            assertEquals(
                KotlinCompilation.ExitCode.COMPILATION_ERROR,
                compile("package com.example\n\nimport kotlin.Int\n\n$source\n").exitCode,
                source,
            )
        }
    }
}
