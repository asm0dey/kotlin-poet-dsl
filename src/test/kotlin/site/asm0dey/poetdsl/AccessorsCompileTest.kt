package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MUTABLE_LIST
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import site.asm0dey.poetdsl.ParamKind.VAL
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `kotlinc`'s verdict on E2a's output, and on the four rules the DSL enforces to keep that output
 * compilable.
 *
 * An accessor is exactly the place a golden string proves least. `val <T> stray: Int get() = 1`,
 * `val String.x: Int = 1` and `var String.x: Int get() = 1` all render perfectly and none of them
 * compiles — the same class of defect as E1's `enum class E<T>` and `fun f(xs: List)`.
 */
@OptIn(ExperimentalCompilerApi::class)
class AccessorsCompileTest {
    @Test
    fun `the whole accessor and receiver vocabulary compiles`() {
        val t = typeVariable("T")

        val spec = file("com.example", "Accessors") {
            // fun String.shout(): String
            `fun`("shout", returns = STRING, receiver = STRING) { ret(expression("this").call("uppercase")) }

            // val answer: Int get() = 42 — a getter is the only initializer this property has.
            val answer = `val`("answer", INT) { ret(42.lit) }

            // A getter that does more than one thing, which is the whole reason `ret(x)` has to work
            // inside an accessor: KotlinPoet would throw on `returns(…)` here.
            `val`("doubled", INT) {
                val scaled = `val`("scaled", init = answer * 2.lit)
                ret(scaled)
            }

            // var count: Int = 0 set(`value`) { field = `value` } — the backing field, spelled with
            // the escape hatch rather than a construct of its own.
            `var`("count", INT, init = 0.lit, setter = { fresh -> expression("field") assign fresh })

            // The DSL names the setter's parameter (ADR 0005), and the valueless `ret()` is the one
            // `return` a setter may carry.
            `var`(
                "total",
                INT,
                init = 0.lit,
                setterParam = "next",
                setter = { next ->
                    `if`(next lt 0.lit) { ret() }
                    expression("field") assign next
                },
            )

            // var proxied: Int with *both* accessors and no initializer — the shape one accessor
            // short of the pair the DSL now rejects, and the reason that rejection cannot simply be
            // "a var with a custom accessor needs an initializer".
            `var`("proxied", INT, setter = { fresh -> +call("check", fresh gt 0.lit) }) { ret(42.lit) }

            // val String.initial: Int
            `val`("initial", INT, receiver = STRING) { ret(expression("this").prop("length")) }

            // Both spellings the extension property's ownership label offers instead of its
            // (refused) bare handle, compiled rather than argued: `.prop(…)` through a receiver
            // from anywhere, and the bare name inside another extension on the same receiver —
            // which is the one position where Kotlin does resolve it.
            `fun`("initialOf", param("s", STRING), returns = INT) { s -> ret(s.prop("initial")) }
            `fun`("initialTwice", returns = INT, receiver = STRING) {
                ret(expression("initial") + expression("initial"))
            }

            // val <T> List<T>.second: T — the slot E1 deferred until a receiver existed to use it.
            `val`("second", t, receiver = LIST.of(t), typeVariables = listOf(t)) {
                ret(expression("this").call("get", 1.lit))
            }

            // A mutable extension property needs both accessors: it has no backing field to fall
            // back on.
            `var`(
                "head",
                STRING,
                receiver = MUTABLE_LIST.of(STRING),
                setter = { fresh -> +expression("this").call("set", 0.lit, fresh) },
            ) {
                ret(expression("this").call("first"))
            }

            // A delegated extension property: no backing field either, and the delegate stands in
            // for both accessors, so the getter requirement does not apply.
            `val`("cached", INT, receiver = STRING, by = call("lazy", lambda { +42.lit }))

            `class`("Box", param(VAL, "raw", STRING)) { raw ->
                // A member property whose getter reads the type's own state.
                `val`("size", INT) { ret(raw.prop("length")) }

                // A member `var` with both a backing field and a custom setter.
                `var`("label", STRING, init = "".lit, setter = { fresh -> expression("field") assign fresh.call("trim") })

                // A member extension function: an extension and a member at once, which is legal
                // Kotlin and reaches both slots at the same time.
                `fun`("repeated", param("n", INT), returns = STRING, receiver = STRING) { n ->
                    ret(expression("this").call("repeat", n))
                }
            }
        }
        assertCompiles(spec.toString())
    }

    /**
     * The other half of "never render Kotlin that does not compile": every shape the DSL *rejects*
     * is a shape `kotlinc` rejects too. Without this, a guard could be wrong in the expensive
     * direction — refusing valid generator code — and no test would notice.
     */
    @Test
    fun `each rejected shape is one kotlinc rejects`() {
        val invalid = mapOf(
            "an extension property with an initializer" to "val String.x: Int = 1",
            "an extension property with no accessor" to "val String.x: Int",
            "a mutable extension property with only a getter" to "var String.x: Int\n  get() = 1",
            "a property type parameter with no receiver" to "val <T> x: Int\n  get() = 1",
            "a property type parameter the receiver does not use" to "val <T> String.x: Int\n  get() = 1",
            "a delegated property with a getter" to
                "val x: Int by lazy { 1 }\n  get() = 2",
            "a var with only a getter" to "var x: Int\n  get() = 1",
            "a var with only a setter" to "var x: Int\n  set(value) {}",
            // The other direction of the extension pair, which the one-directional form of that
            // check rendered in every container. `kotlinc-js` and `kotlinc-wasm` answer identically
            // on all three rows; the diagnostics are in the KDoc of the accessor pair check.
            "a mutable extension property with only a setter" to "var String.x: Int\n  set(value) {}",
            "an abstract mutable extension property with only a setter" to
                "abstract class C {\n  abstract var String.x: Int\n    set(value) {}\n}",
            "an interface mutable extension property with only a setter" to
                "interface I {\n  var String.x: Int\n    set(value) {}\n}",
        )
        for ((what, source) in invalid) {
            val result = compile("package com.example\n\n$source\n")
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, "$what: $source")
        }

        // And the positive control for the pair the DSL *does* allow, which is the same shape as two
        // of the rejections above minus the one thing that made them invalid.
        assertCompiles(
            """
            package com.example

            val String.x: Int
              get() = length

            var StringBuilder.head: Char
              get() = this[0]
              set(`value`) { this.setCharAt(0, `value`) }

            val y: Int = 1
              get() = field + 1

            var z: Int
              get() = 1
              set(`value`) {}

            var w: Int = 0
              set(`value`) { field = `value` }
            """.trimIndent() + "\n",
        )
    }
}
