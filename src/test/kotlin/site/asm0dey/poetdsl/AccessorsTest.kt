package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * E2a: extension receivers, property accessors, and the `val`/`var` type-parameter slot the
 * receiver unlocks.
 */
class AccessorsTest {
    @Test
    fun `a fun takes an extension receiver`() {
        assertEquals(
            """
            package com.example

            import kotlin.String

            public fun String.shout(): String = this.uppercase()

            """.trimIndent(),
            file("com.example", "A") {
                `fun`("shout", returns = STRING, receiver = STRING) {
                    ret(expression("this").call("uppercase"))
                }
            }.toString(),
        )
    }

    @Test
    fun `a val takes a getter`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public val answer: Int
              get() = 42

            """.trimIndent(),
            file("com.example", "A") {
                `val`("answer", INT) { ret(42.lit) }
            }.toString(),
        )
    }

    /**
     * The reason accessors could not be a plain expression slot: a getter can have side effects, so
     * its body is a block and needs `ret(x)` to end. `FunSpec.Builder.returns` throws on an
     * accessor, so the DSL has to allow the `ret` while suppressing the `returns(…)` call.
     */
    @Test
    fun `a getter body may be more than one statement`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public val answer: Int
              get() {
                val computed = compute()
                return computed
              }

            """.trimIndent(),
            file("com.example", "A") {
                `val`("answer", INT) {
                    val computed = `val`("computed", init = call("compute"))
                    ret(computed)
                }
            }.toString(),
        )
    }

    @Test
    fun `a var takes a setter whose parameter the DSL names`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public var count: Int = 0
              set(`value`) {
                field = `value`
              }

            """.trimIndent(),
            file("com.example", "A") {
                `var`("count", INT, init = 0.lit, setter = { v -> expression("field") assign v })
            }.toString(),
        )
    }

    /** ADR 0005: the rendered parameter name comes from the DSL, never from the Kotlin binding. */
    @Test
    fun `the setter parameter can be renamed`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public var count: Int = 0
              set(fresh) {
                field = fresh
              }

            """.trimIndent(),
            file("com.example", "A") {
                `var`(
                    "count",
                    INT,
                    init = 0.lit,
                    setterParam = "fresh",
                    setter = { v -> expression("field") assign v },
                )
            }.toString(),
        )
    }

    /** A setter returns `Unit`. The valueless `ret()` is the legal early exit; `ret(x)` is not. */
    @Test
    fun `a setter rejects a returned value and accepts a valueless return`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `var`("count", INT, init = 0.lit, setter = { ret(1.lit) })
            }
        }
        assertEquals(
            "set() of 'count': a setter cannot return a value; it returns Unit. Use the valueless " +
                "ret() to leave early, or assign to `field` — expression(\"field\") — instead.",
            failure.message,
        )
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public var count: Int = 0
              set(`value`) {
                return
              }

            """.trimIndent(),
            file("com.example", "A") {
                `var`("count", INT, init = 0.lit, setter = { ret() })
            }.toString(),
        )
    }

    @Test
    fun `a setter on a val is rejected`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `val`("count", INT, init = 0.lit, setter = { })
            }
        }
        assertEquals(
            "`val`: 'count' is a `val` and has no setter. Declare it with `var`, or drop the setter.",
            failure.message,
        )
    }

    @Test
    fun `an extension property renders its receiver`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.String

            public val String.initial: Int
              get() = this.length

            """.trimIndent(),
            file("com.example", "A") {
                `val`("initial", INT, receiver = STRING) { ret(expression("this").prop("length")) }
            }.toString(),
        )
    }

    @Test
    fun `an extension property rejects an initializer`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `val`("initial", INT, init = 0.lit, receiver = STRING) { ret(0.lit) }
            }
        }
        assertEquals(
            "`val`: 'initial' is an extension property, which has no backing field, so it cannot " +
                "have an initializer. Move the value into the getter.",
            failure.message,
        )
    }

    @Test
    fun `an extension property requires a getter`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `val`("initial", INT, receiver = STRING) }
        }
        assertEquals(
            "`val`: 'initial' is an extension property, which has no backing field, so it needs a " +
                "getter (or a delegate).",
            failure.message,
        )
    }

    /**
     * Two extension properties of the same name on *different* receivers are legal Kotlin, so D21's
     * duplicate-property rejection has to key on the receiver as well as the name. Two on the *same*
     * receiver are still a compile error and still rejected.
     */
    @Test
    fun `extension properties collide only on the same receiver`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.String

            public val String.size: Int
              get() = this.length

            public val Int.size: Int
              get() = 4

            """.trimIndent(),
            file("com.example", "A") {
                `val`("size", INT, receiver = STRING) { ret(expression("this").prop("length")) }
                `val`("size", INT, receiver = INT) { ret(4.lit) }
            }.toString(),
        )
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `val`("size", INT, receiver = STRING) { ret(0.lit) }
                `val`("size", INT, receiver = STRING) { ret(1.lit) }
            }
        }
        assertEquals(
            "A property named \"kotlin.String.size\" is already declared in this scope.",
            failure.message,
        )
    }

    /** D31/E1's deferred slot: a property's type parameter is legal once it has a receiver to use it. */
    @Test
    fun `a property takes type parameters when the receiver uses them`() {
        val t = typeVariable("T")
        assertEquals(
            """
            package com.example

            import kotlin.collections.List

            public val <T> List<T>.second: T
              get() = this.get(1)

            """.trimIndent(),
            file("com.example", "A") {
                `val`("second", t, receiver = LIST.of(t), typeVariables = listOf(t)) {
                    ret(expression("this").call("get", 1.lit))
                }
            }.toString(),
        )
    }

    @Test
    fun `a property type parameter unused by the receiver is rejected`() {
        val t = typeVariable("T")
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `val`("stray", INT, receiver = STRING, typeVariables = listOf(t)) { ret(0.lit) }
            }
        }
        assertEquals(
            "`val`: type parameter \"T\" of 'stray' is not used in the receiver type. Kotlin allows " +
                "a property's type parameter only where its receiver type uses it.",
            failure.message,
        )
        val noReceiver = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `val`("stray", t, typeVariables = listOf(t)) { ret(0.lit) } }
        }
        assertEquals(
            "`val`: 'stray' declares type parameters but has no receiver. Kotlin allows a " +
                "property's type parameter only where its receiver type uses it.",
            noReceiver.message,
        )
    }

    @Test
    fun `a local binding takes no accessors, receiver or type parameters`() {
        val failure = assertFailsWith<IllegalStateException> {
            renderBlock { `val`("x", INT, init = 0.lit) { ret(0.lit) } }
        }
        assertEquals(
            "A local binding ('x') cannot have accessors, an extension receiver or type " +
                "parameters; only a property can.",
            failure.message,
        )
    }

    /**
     * ADR 0008, inward: an accessor body is a nested scope reached through a *property* rather than
     * a function, which nothing before E2a exercised. A handle from an unrelated scope must be
     * rejected at splice time exactly as it is inside a `` `fun` `` body.
     */
    @Test
    fun `a foreign handle is rejected inside a getter body`() {
        lateinit var smuggled: Expr
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("A") {
                    `fun`("f") { smuggled = `val`("local", init = call("compute")) }
                    `val`("answer", INT) { ret(smuggled) }
                }
            }
        }
        assertEquals(
            "Handle from scope 'fun(f)' does not enclose the current scope 'get() of 'answer''.",
            failure.message,
        )
    }

    /** ADR 0008, outward: a handle declared *inside* an accessor body must not escape it either. */
    @Test
    fun `a getter local does not escape the accessor body`() {
        lateinit var escaped: Expr
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("A") {
                    `val`("answer", INT) {
                        escaped = `val`("inside", init = call("compute"))
                        ret(escaped)
                    }
                    `fun`("f") { +escaped }
                }
            }
        }
        assertEquals(
            "Handle from scope 'get() of 'answer'' does not enclose the current scope 'fun(f)'.",
            failure.message,
        )
    }

    /** The same both ways for a setter, whose body is reached through the same property. */
    @Test
    fun `a setter body is owned in both directions`() {
        lateinit var smuggled: Expr
        val inward = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("A") {
                    `fun`("f") { smuggled = `val`("local", init = call("compute")) }
                    `var`("count", INT, init = 0.lit, setter = { +smuggled })
                }
            }
        }
        assertEquals(
            "Handle from scope 'fun(f)' does not enclose the current scope 'set() of 'count''.",
            inward.message,
        )

        lateinit var escaped: Expr
        val outward = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("A") {
                    `var`("count", INT, init = 0.lit, setter = { escaped = `val`("inside", init = call("f")) })
                    `fun`("g") { +escaped }
                }
            }
        }
        assertEquals(
            "Handle from scope 'set() of 'count'' does not enclose the current scope 'fun(g)'.",
            outward.message,
        )
    }

    /**
     * The property's own scope is the *member* level, so a getter sees the type's properties — and
     * not a plain primary-constructor parameter, which D30 measured as unresolvable outside an
     * initializer.
     */
    @Test
    fun `a getter sees the type's properties but not a plain constructor parameter`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public class A(
              public val id: Int,
            ) {
              public val doubled: Int
                get() = id * 2
            }

            """.trimIndent(),
            file("com.example", "A") {
                `class`("A", param(ParamKind.VAL, "id", INT)) { id ->
                    `val`("doubled", INT) { ret(id * 2.lit) }
                }
            }.toString(),
        )

        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("A", param(null, "seed", INT)) { seed ->
                    `val`("doubled", INT) { ret(seed) }
                }
            }
        }
        assertEquals(
            "Handle from scope 'the primary constructor's plain parameters (use param(VAL, …) to " +
                "reach it from a member body)' does not enclose the current scope " +
                "'get() of 'doubled''.",
            failure.message,
        )
    }
}
