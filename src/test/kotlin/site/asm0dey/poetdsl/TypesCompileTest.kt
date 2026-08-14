package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.COMPARABLE
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.INLINE
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.MUTABLE_LIST
import com.squareup.kotlinpoet.NUMBER
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.UNIT
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import site.asm0dey.poetdsl.ParamKind.VAL

/**
 * `kotlinc`'s verdict on E1's output.
 *
 * A generic signature is the one place where "the golden string looks right" proves least: variance
 * in the wrong position, a bound that does not resolve, a `reified` on a function that is not
 * `inline` and a star projection all render perfectly and none of them compile. Every shape the D31
 * audit named is in the single file below, so the whole vocabulary costs one compiler invocation.
 */
@OptIn(ExperimentalCompilerApi::class)
class TypesCompileTest {
    @Test
    fun `the whole type vocabulary compiles`() {
        val t = typeVariable("T")
        val comparableT = typeVariable("T", COMPARABLE.of(typeVariable("T")))
        val reifiedT = typeVariable("T", reified = true)
        val k = typeVariable("K", variance = com.squareup.kotlinpoet.KModifier.IN)
        val v = typeVariable("V", variance = com.squareup.kotlinpoet.KModifier.OUT)
        val user = className("com.example", "User")
        val repo = className("com.example", "Repo")

        val spec = file("com.example", "Generics") {
            // `class User(val name: String)` — the type the composed names below refer to, which
            // the generator is writing rather than importing. `className` is the only route to it.
            `class`("User", param(VAL, "name", STRING)) { }

            // class Box<T>(val item: T)
            `class`("Box", param(VAL, "item", t), typeVariables = listOf(t)) {
                // The class's own type parameter is in scope for its members.
                `fun`("orElse", param("fallback", t), returns = t) { fallback -> ret(fallback) }
            }

            // fun <T : Comparable<T>> max(a: T, b: T): T
            `fun`(
                "max",
                param("a", comparableT),
                param("b", comparableT),
                typeVariables = listOf(comparableT),
                returns = comparableT,
            ) { a, b -> ret(expression("if (%L > %L) %L else %L", a, b, a, b)) }

            // class Cache<in K, out V> — K only in `in` position, V only in `out` position, which
            // is exactly what declaration-site variance is checked against.
            `class`("Cache", typeVariables = listOf(k, v)) {
                `fun`("get", param("key", k), returns = v) { +expression("TODO()") }
            }

            // interface Repo<T> { fun find(id: Int): T }
            `interface`("Repo", typeVariables = listOf(t)) {
                `fun`(ABSTRACT, "find", param("id", INT), returns = t) { }
            }

            // List<Map<String, User?>>, a star projection, and a function type, all in one
            // signature — and a supertype that is itself parameterized.
            `class`("Index") {
                superinterface(repo.of(user))
                `fun`(
                    com.squareup.kotlinpoet.KModifier.OVERRIDE,
                    "find",
                    param("id", INT),
                    returns = user,
                ) { +expression("TODO()") }
                `fun`(
                    "scan",
                    param("rows", LIST.of(MAP.of(STRING, user.nullable))),
                    param("raw", LIST.of(STAR)),
                    param("keep", functionType(STRING, returns = BOOLEAN)),
                    param("sink", functionType(receiver = STRING, returns = UNIT)),
                    param("numbers", MUTABLE_LIST.of(out(NUMBER))),
                    param("sorter", MUTABLE_LIST.of(`in`(user))),
                    returns = LIST.of(MAP.of(STRING, user.nullable)),
                ) { rows, _, _, _, _, _ -> ret(rows) }
            }

            // annotation class Ann<T> — valid Kotlin, which is why `class` guards `enum class E<T>`
            // and not this one. Only kotlinc can settle that, so it is asserted here rather than
            // assumed from the shape.
            `class`(com.squareup.kotlinpoet.KModifier.ANNOTATION, "Ann", typeVariables = listOf(t)) { }

            // inline fun <reified T> filterOnly(xs: List<Any>): List<T>
            `fun`(
                INLINE,
                "filterOnly",
                param("xs", LIST.of(ANY)),
                typeVariables = listOf(reifiedT),
                returns = LIST.of(reifiedT),
            ) { xs -> ret(expression("%L.filterIsInstance<%T>()", xs, reifiedT)) }
        }
        assertCompiles(spec.toString())
    }

    /**
     * `object O<T>` is not Kotlin, so the generated `` `object` `` overloads carry no `typeVariables`
     * slot at all — the guard is the absence of the parameter, which fails at the DSL author's own
     * compile rather than at generation time. The positive half is the same call without it.
     */
    @Test
    fun `an object has no type parameter slot`() {
        val result = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `object`("Registry", typeVariables = listOf(typeVariable("T"))) { }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        // The compiler lists every `object` overload it knows; not one of them mentions the slot.
        assertTrue("None of the following candidates is applicable" in result.messages, result.messages)
        assertTrue("typeVariables" !in result.messages, result.messages)

        val control = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `object`("Registry") { }
            }
            """.trimIndent(),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, control.exitCode, control.messages)
    }

    /**
     * Why `` `val` ``/`` `var` `` got no `typeVariables` slot in E1, asserted about *this DSL* — the
     * same shape as the `` `object` `` test above, and for the same reason: a test that compiles
     * `val <T> stray: T get() = TODO()` and asserts `kotlinc` rejects it pins a fact about the Kotlin
     * language, not about the surface here, so it would go on passing after E2 adds the slot —
     * exactly when a canary is supposed to fire.
     *
     * A property's type parameter has to be used in its receiver type **or its context parameters**
     * (`Type parameter of a property must be used in its receiver type or context parameters`, the
     * compiler's own wording): extension receivers are E2's and context parameters are E3's, so
     * either batch can unlock this slot. Whichever gets there first makes the call below resolve, and
     * this test fails.
     */
    @Test
    fun `a property has no type parameter slot`() {
        for (construct in listOf("`val`", "`var`")) {
            val result = compileDsl(
                """
                fun build() = file("com.example", "A") {
                    $construct("stray", INT, typeVariables = listOf(typeVariable("T")))
                }
                """.trimIndent(),
                extraImports = listOf("com.squareup.kotlinpoet.INT"),
            )
            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue("None of the following candidates is applicable" in result.messages, result.messages)
            // The `object` test asserts `"typeVariables" !in messages`; that shape does not transfer
            // here. `val`'s first parameter is the name, so the compiler gets far enough to also
            // report `No parameter with name 'typeVariables' found` — which says the same thing more
            // directly, and is asserted rather than merely tolerated. When the slot lands, the
            // snippet compiles and both this and the exit-code assertion above fail.
            assertTrue("No parameter with name 'typeVariables' found" in result.messages, result.messages)
        }

        val control = compileDsl(
            """
            fun build() = file("com.example", "A") {
                `val`("stray", INT, init = 1.lit)
            }
            """.trimIndent(),
            extraImports = listOf("com.squareup.kotlinpoet.INT"),
        )
        assertEquals(KotlinCompilation.ExitCode.OK, control.exitCode, control.messages)
    }
}
