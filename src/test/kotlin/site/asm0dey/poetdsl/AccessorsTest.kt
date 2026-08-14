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

    /**
     * D33's dangerous case, pinned rather than argued. `receiver` and `returns` are both
     * `TypeName?`, so a `receiver` slot inserted *before* `returns` would have silently rebound the
     * one positional spelling E1 left resolving — the `STRING` below — from a return type to a
     * receiver type, turning `fun <T> f(): String` into `fun <T> String.f()`. Rendered output, both
     * valid Kotlin, no error anywhere. The slot is after `returns` instead, and this asserts the
     * render that proves it: measured identical at `80fd83b` and at head.
     */
    @Test
    fun `a positional argument after the type parameters is still the return type`() {
        assertEquals(
            """
            package com.example

            import kotlin.String

            public fun <T> f(): String {
            }

            """.trimIndent(),
            file("com.example", "A") { `fun`("f", listOf(typeVariable("T")), STRING) { } }.toString(),
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

    /**
     * A `var` that customises exactly one accessor gets the *default* other one, which reads or
     * writes the backing field — and a property with a backing field and no initializer does not
     * compile. E2a made this shape expressible in both directions and rendered it happily.
     *
     * Measured with kctfork before the guard existed: `var x: Int get() = 1` and
     * `var y: Int set(value) {}` are `Property must be initialized.` at file level, in a class
     * body, in an object, in a companion object and in an anonymous object; `Property in interface
     * cannot have a backing field.` in an interface; `Property with getter implementation cannot be
     * abstract.` under `abstract`; and `'lateinit' modifier is not allowed on properties with a
     * custom getter or setter.` under `lateinit`. `override` on an interface or abstract base
     * behaves like the plain class case. So no valid generator code is refused.
     */
    @Test
    fun `a var with exactly one custom accessor is rejected`() {
        val getterOnly = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `var`("x", INT) { ret(1.lit) } }
        }
        assertEquals(
            "`var`: 'x' has a getter but no setter, so Kotlin generates the setter, which needs a " +
                "backing field, which needs an initializer. Add an initializer, or write the " +
                "setter as well.",
            getterOnly.message,
        )

        val setterOnly = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `var`("y", INT, setter = { }) }
        }
        assertEquals(
            "`var`: 'y' has a setter but no getter, so Kotlin generates the getter, which needs a " +
                "backing field, which needs an initializer. Add an initializer, or write the " +
                "getter as well.",
            setterOnly.message,
        )
    }

    /**
     * The boundary the rejection above must not cross, asserted through the DSL rather than as raw
     * Kotlin: a guard that is too wide refuses valid generator code, and a compile test on
     * hand-written snippets cannot see that.
     *
     * Both accessors, one accessor plus an initializer (either accessor), a `val` with only a
     * getter, and a plain `var` with an initializer and no accessor at all — all still render.
     */
    @Test
    fun `the shapes on the other side of that boundary still render`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public var both: Int
              get() = 1
              set(`value`) {
              }

            public var getterAndInit: Int = 0
              get() = field

            public var setterAndInit: Int = 0
              set(`value`) {
                field = `value`
              }

            public val getterOnlyVal: Int
              get() = 1

            public var plain: Int = 0

            """.trimIndent(),
            file("com.example", "A") {
                `var`("both", INT, setter = { }) { ret(1.lit) }
                `var`("getterAndInit", INT, init = 0.lit) { ret(expression("field")) }
                `var`("setterAndInit", INT, init = 0.lit, setter = { v -> expression("field") assign v })
                `val`("getterOnlyVal", INT) { ret(1.lit) }
                `var`("plain", INT, init = 0.lit)
            }.toString(),
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

    /**
     * An extension property's handle renders as a bare name, and a bare name resolves nowhere the
     * receiver is not already in scope — `` `fun`("f") { +size } `` rendered `size`, which is
     * `Unresolved reference 'size'`. D30 answered exactly this shape (a handle legal in some
     * positions, unspellable in others) by giving it an owning [ScopeId] that encloses only the
     * legal positions, with the remedy folded into the label, and this follows that precedent:
     * nothing is ever nested inside this owner, so [checkOwned] — unchanged — refuses the handle
     * everywhere.
     *
     * The remedy is the second half of the test: reach the property through a receiver handle.
     */
    @Test
    fun `an extension property's handle is refused as a bare name`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                val size = `val`("size", INT, receiver = STRING) { ret(0.lit) }
                `fun`("f") { +size }
            }
        }
        assertEquals(
            "Handle from scope 'the extension property 'size' on kotlin.String (reach it through a " +
                "receiver handle — h.prop(\"size\") — or, inside an extension on kotlin.String, as " +
                "expression(\"size\"))' does not enclose the current scope 'fun(f)'.",
            failure.message,
        )

        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.String

            public val String.size: Int
              get() = 0

            public val plain: Int
              get() = 1

            public fun f(s: String): Int = s.size + plain

            public fun String.g(): Int = size

            """.trimIndent(),
            file("com.example", "A") {
                `val`("size", INT, receiver = STRING) { ret(0.lit) }
                val plain = `val`("plain", INT) { ret(1.lit) }
                `fun`("f", param("s", STRING), returns = INT) { s -> ret(s.prop("size") + plain) }
                `fun`("g", returns = INT, receiver = STRING) { ret(expression("size")) }
            }.toString(),
        )
    }

    /** The same refusal for an extension property declared in a type body, and for a `var`. */
    @Test
    fun `an extension property's handle is refused inside a type body too`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`("A") {
                    val head = `var`(
                        "head",
                        INT,
                        receiver = STRING,
                        setter = { },
                    ) { ret(0.lit) }
                    `fun`("f") { +head }
                }
            }
        }
        assertEquals(
            "Handle from scope 'the extension property 'head' on kotlin.String (reach it through a " +
                "receiver handle — h.prop(\"head\") — or, inside an extension on kotlin.String, as " +
                "expression(\"head\"))' does not enclose the current scope 'fun(f)'.",
            failure.message,
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

    @Test
    fun `a mutable extension property requires a setter as well`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `var`("head", INT, receiver = STRING) { ret(0.lit) } }
        }
        assertEquals(
            "`var`: 'head' is a mutable extension property, which has no backing field, so it needs " +
                "a setter as well as a getter (or a delegate).",
            failure.message,
        )
    }

    @Test
    fun `a delegated property takes no accessors`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `val`("cached", INT, by = call("lazy", detachedLambda { +1.lit })) { ret(1.lit) }
            }
        }
        assertEquals(
            "`val`: 'cached' is delegated with `by`, and a delegated property cannot have " +
                "accessors. Drop the delegate, or drop the accessors.",
            failure.message,
        )
    }

    /**
     * A property's type parameters go through the same [checkTypeVariables] a function's do, so
     * declaration-site variance and `reified` are rejected here too — neither is valid on a
     * property, and both render.
     */
    @Test
    fun `a property type parameter takes no variance`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                val out = typeVariable("T", variance = com.squareup.kotlinpoet.KModifier.OUT)
                `val`("second", out, receiver = LIST.of(out), typeVariables = listOf(out)) { ret(0.lit) }
            }
        }
        assertEquals(
            "`val`: type parameter \"T\" of 'second' declares `out` variance, which Kotlin allows " +
                "only on a class or interface. Drop the variance, or project the type argument at " +
                "the use site with out(…)/`in`(…).",
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
     * The detached builder tracks the attached construct, as it did for E1's `typeVariables` on
     * [funSpec] — and under the same rules, because the guards are one function and cannot drift.
     *
     * No `setter` slot, deliberately: [propertySpec] builds a `val` and has no `mutable` slot at
     * all (a gap that predates E2a), and `PropertySpec.build` is `require(mutable || setter ==
     * null)`, so a setter here could never be anything but an error.
     */
    @Test
    fun `propertySpec takes the same getter, receiver and type parameters`() {
        val t = typeVariable("T")
        assertEquals(
            """
            val <T> kotlin.collections.List<T>.second: T
              get() = this.get(1)

            """.trimIndent(),
            propertySpec(
                name = "second",
                type = t,
                receiver = LIST.of(t),
                typeVariables = listOf(t),
            ) { ret(expression("this").call("get", 1.lit)) }.toString(),
        )
        // Global Constraint 26: the message names the construct the caller wrote. `propertySpec`
        // used to hand `` `val` `` to the shared guard, so its own init/by check said
        // `propertySpec: …` while every guard one line below it said `` `val`: … ``.
        assertEquals(
            "propertySpec: 'stray' is an extension property, which has no backing field, so it " +
                "needs a getter (or a delegate).",
            assertFailsWith<IllegalStateException> {
                propertySpec(name = "stray", type = INT, receiver = STRING)
            }.message,
        )
        assertEquals(
            "propertySpec: type parameter \"T\" of 'stray' is not used in the receiver type. " +
                "Kotlin allows a property's type parameter only where its receiver type uses it.",
            assertFailsWith<IllegalStateException> {
                propertySpec(name = "stray", type = INT, receiver = STRING, typeVariables = listOf(t)) {
                    ret(0.lit)
                }
            }.message,
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
