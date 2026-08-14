package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.LATEINIT
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals

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
     * `EXPECT` is the one exempt case missing here, and it cannot be added: a single-platform
     * compilation answers `'expect' and 'actual' declarations can be used only in multiplatform
     * projects` before it ever reaches the initializer question, so the exemption is unmeasurable
     * with this harness in either direction. See the note at the check in `Bindings.kt` for why it
     * is exempted anyway.
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
        ).forEach { source ->
            assertEquals(
                KotlinCompilation.ExitCode.COMPILATION_ERROR,
                compile("package com.example\n\nimport kotlin.Int\n\n$source\n").exitCode,
                source,
            )
        }
    }
}
