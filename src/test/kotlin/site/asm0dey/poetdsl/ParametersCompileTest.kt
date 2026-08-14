package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.CROSSINLINE
import com.squareup.kotlinpoet.KModifier.INLINE
import com.squareup.kotlinpoet.KModifier.NOINLINE
import com.squareup.kotlinpoet.KModifier.VARARG
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.UNIT
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import site.asm0dey.poetdsl.ParamKind.VAL
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `kotlinc`'s verdict on E2b's parameter slots.
 *
 * A default value and a parameter modifier are exactly the place a golden string proves least:
 * `fun f(vararg a: Int, vararg b: Int)`, `fun f(noinline g: () -> Unit)` and
 * `override fun f(x: Int = 1)` all render perfectly and none of them compiles.
 */
@OptIn(ExperimentalCompilerApi::class)
class ParametersCompileTest {
    @Test
    fun `the whole parameter vocabulary compiles`() {
        val spec = file("com.example", "Params") {
            // Defaults on a plain function.
            `fun`("greet", param("name", STRING, default = "world".lit), param("times", INT, default = 1.lit)) { _, _ -> }

            // A vararg that is *not* last, with a defaulted parameter after it. Kotlin allows this —
            // the trailing parameter is passed by name — which is why `checkParams` does not insist
            // a vararg comes last.
            `fun`("log", param("parts", STRING, modifiers = VARARG), param("level", INT, default = 0.lit)) { _, _ -> }

            // A vararg that is last.
            `fun`("sum", param("xs", INT, modifiers = VARARG)) { }

            // noinline and crossinline, on the one kind of function that may carry them.
            `fun`(
                INLINE,
                "run2",
                param("a", functionType(returns = UNIT), modifiers = NOINLINE),
                param("b", functionType(returns = UNIT), modifiers = CROSSINLINE),
            ) { _, _ -> }

            // A primary constructor carrying both, including a `vararg val` property parameter.
            `class`(
                "Query",
                param(VAL, "table", STRING, default = "t".lit),
                param(VAL, "columns", INT, modifiers = VARARG),
            ) { _, _ -> }

            // The in-body spelling of the same thing, plus a secondary constructor with a default.
            `class`("Report") {
                constructorParam(VAL, "title", STRING, default = "untitled".lit)
                `constructor`(param("n", INT, default = 3.lit)) { `this`("t".lit) }
            }
        }
        assertCompiles(spec.toString())
    }

    @Test
    fun `each rejected shape is one kotlinc rejects`() {
        listOf(
            "fun f(vararg a: Int, vararg b: Int) {}",
            "fun f(noinline g: () -> Unit) {}",
            "fun f(crossinline g: () -> Unit) {}",
            "class C(noinline g: () -> Unit)",
            "open class B { open fun f(x: Int) {} }\nclass D : B() { override fun f(x: Int = 1) {} }",
        ).forEach { source ->
            assertEquals(
                KotlinCompilation.ExitCode.COMPILATION_ERROR,
                compile("package com.example\n\n$source\n").exitCode,
                source,
            )
        }
        // The positive control: the shapes on the other side of each of those boundaries.
        listOf(
            "fun f(vararg a: Int, b: Int) {}",
            "inline fun f(noinline g: () -> Unit) { g() }",
            "inline fun f(crossinline g: () -> Unit) { g() }",
            "class C(g: () -> Unit)",
            "open class B { open fun f(x: Int) {} }\nclass D : B() { override fun f(x: Int) {} }",
        ).forEach { source -> assertCompiles("package com.example\n\n$source\n") }
    }
}
