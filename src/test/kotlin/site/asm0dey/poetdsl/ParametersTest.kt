package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.CROSSINLINE
import com.squareup.kotlinpoet.KModifier.INLINE
import com.squareup.kotlinpoet.KModifier.NOINLINE
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.VARARG
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.UNIT
import site.asm0dey.poetdsl.ParamKind.VAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** E2b item 1: `ParameterSpec.defaultValue` and `ParameterSpec.addModifiers`. */
class ParametersTest {
    @Test
    fun `a parameter takes a default value`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.String

            public fun greet(name: String = "world", times: Int = 1) {
            }

            """.trimIndent(),
            file("com.example", "A") {
                `fun`(
                    "greet",
                    param("name", STRING, default = "world".lit),
                    param("times", INT, default = 1.lit),
                ) { _, _ -> }
            }.toString(),
        )
    }

    @Test
    fun `a parameter takes the vararg modifier, in any position`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.String

            public fun log(vararg parts: String, level: Int = 0) {
            }

            """.trimIndent(),
            file("com.example", "A") {
                `fun`(
                    "log",
                    param("parts", STRING, modifiers = VARARG),
                    param("level", INT, default = 0.lit),
                ) { _, _ -> }
            }.toString(),
        )
    }

    @Test
    fun `noinline and crossinline are allowed on an inline function`() {
        assertEquals(
            """
            package com.example

            import kotlin.Unit

            public inline fun run2(noinline a: () -> Unit, crossinline b: () -> Unit) {
            }

            """.trimIndent(),
            file("com.example", "A") {
                `fun`(
                    INLINE,
                    "run2",
                    param("a", functionType(returns = UNIT), modifiers = NOINLINE),
                    param("b", functionType(returns = UNIT), modifiers = CROSSINLINE),
                ) { _, _ -> }
            }.toString(),
        )
    }

    @Test
    fun `a primary constructor parameter takes a default value and a vararg`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.String

            public class Query(
              public val table: String = "t",
              public vararg val columns: Int,
            )

            """.trimIndent(),
            file("com.example", "A") {
                `class`(
                    "Query",
                    param(VAL, "table", STRING, default = "t".lit),
                    param(VAL, "columns", INT, modifiers = VARARG),
                ) { _, _ -> }
            }.toString(),
        )
    }

    @Test
    fun `two vararg parameters are rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `fun`("f", param("a", INT, modifiers = VARARG), param("b", INT, modifiers = VARARG)) { _, _ -> }
            }
        }
        assertEquals(
            "param: \"b\" is the second `vararg` parameter of 'f', and Kotlin allows one per " +
                "function (\"Multiple vararg parameters are prohibited.\"). Drop the modifier, or " +
                "fold the two into one.",
            e.message,
        )
    }

    @Test
    fun `two vararg primary constructor parameters are rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("C", param(VAL, "a", INT, modifiers = VARARG), param(VAL, "b", INT, modifiers = VARARG)) { _, _ -> }
            }
        }
        assertEquals(
            "param: \"b\" is the second `vararg` parameter of the primary constructor, and Kotlin " +
                "allows one per function (\"Multiple vararg parameters are prohibited.\"). Drop the " +
                "modifier, or fold the two into one.",
            e.message,
        )
    }

    @Test
    fun `noinline on a function that is not inline is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `fun`("f", param("g", functionType(returns = UNIT), modifiers = NOINLINE)) { } }
        }
        assertEquals(
            "param: \"g\" is `noinline`, which Kotlin allows only on a parameter of an `inline` " +
                "function (\"Modifier is only allowed for function parameters of an inline " +
                "function.\"). Declare 'f' with the INLINE modifier, or drop the parameter modifier.",
            e.message,
        )
    }

    @Test
    fun `crossinline on a constructor parameter is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("C", param(VAL, "g", functionType(returns = UNIT), modifiers = CROSSINLINE)) { _ -> }
            }
        }
        assertEquals(
            "param: \"g\" is `crossinline`, which Kotlin allows only on a parameter of an `inline` " +
                "function (\"Modifier is only allowed for function parameters of an inline " +
                "function.\"). A constructor is never `inline`, so drop the parameter modifier.",
            e.message,
        )
    }

    @Test
    fun `a modifier that is not a parameter modifier is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `fun`("f", param("x", INT, modifiers = PRIVATE)) { } }
        }
        assertEquals(
            "param: 'x' cannot be PRIVATE. Kotlin allows only VARARG, NOINLINE and CROSSINLINE on " +
                "a parameter, and never two at once. A `private`/`override` primary-constructor " +
                "property is not expressible here — that modifier belongs to the property, not to " +
                "the parameter.",
            e.message,
        )
    }

    @Test
    fun `a default value on an overriding function's parameter is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            // Inside a class: `override` at file level is *modifier 'override' is not applicable
            // to 'top level function'* and is refused one rule earlier (D42's container half), so
            // the shape this test is about has to be written where `override` is legal at all.
            file("com.example", "A") {
                `class`("C") { `fun`(OVERRIDE, "f", param("x", INT, default = 1.lit)) { } }
            }
        }
        assertEquals(
            "param: \"x\" carries a default value and 'f' is `override`, which Kotlin does not " +
                "allow (\"An overriding function is not allowed to specify default values for its " +
                "parameters.\"). Drop the default, or declare the value in the base function.",
            e.message,
        )
    }
}
