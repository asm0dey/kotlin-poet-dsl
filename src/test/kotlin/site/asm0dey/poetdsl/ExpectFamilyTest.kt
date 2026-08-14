package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.KModifier.ANNOTATION
import com.squareup.kotlinpoet.KModifier.EXPECT
import com.squareup.kotlinpoet.KModifier.VALUE
import com.squareup.kotlinpoet.LONG
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **An `expect` container refuses every member that carries a body or a value.** One rule, one
 * message shape, and this test is the enumeration of the members it reaches.
 *
 * Three of these were filed as three unrelated open items — a `val`/`var` primary-constructor
 * parameter, a function body, an `init` block — and the reason the list kept growing is that they
 * are one family. Two more turned up while enumerating **every path that reaches
 * `builder.addProperty`, `addFunction`, `addInitializerBlock` or `primaryConstructor`**: a
 * secondary constructor's delegation call, and superclass constructor arguments, which KotlinPoet
 * **silently drops** on an `expect` type.
 *
 * Measured, kotlinc 2.4.10 (`kotlinc`, `kotlinc-js` and `kotlinc-wasm`, one file per row, all three
 * frontends identical):
 *
 * ```
 * expect class E(val x: Int)                        expected class constructor cannot have a
 * expect class E { class N(val x: Int) }            property parameter.
 * expect class E { companion object { class N(val x: Int) } }
 * expect class E { fun f(): Int = 1 }               expected declaration cannot have a body.
 * expect class E { class N { fun f(): Int = 1 } }
 * expect class E { companion object { fun f(): Int = 1 } }
 * expect object O { fun f(): Int = 1 }
 * expect interface I { fun f(): Int = 1 }
 * expect fun f(): Int = 1
 * expect class E { constructor(p: Int) { println(p) } }
 * expect class E { init { println(1) } }
 * expect class E { class N { init { } } }           — an *empty* one, too
 * expect object O { init { println(1) } }
 * expect class E : Base(1)                          expected classes cannot initialize supertypes.
 * expect class E(x: Int) : Base(x)
 * expect class E { class N : Base(1) }
 * expect class E : Base { constructor(p: Int) : super(p) }   explicit delegation call for
 * expect class E { constructor(p: Int) : this() }            constructor of expected class is
 * expect class E { class N : Base { constructor(p: Int) : super(p) } }        prohibited.
 * ```
 *
 * And the boundary, which is what keeps this from being over-broad — **a function in an `expect`
 * class is valid without a body; the refusal is of the body, not of the function.** Clean on all
 * three:
 *
 * ```
 * expect class E { fun f(): Int }            expect class E(x: Int)
 * expect class E { class N { fun f(): Int } } expect class E { class N(x: Int) }
 * expect class E { constructor(p: Int) }     expect class E : Base
 * expect fun f(): Int                        expect class E { fun f(x: Int = 1): Int }
 * ```
 */
@OptIn(ExperimentalCompilerApi::class)
class ExpectFamilyTest {
    private val base = ClassName("com.example", "Base")

    /** The one message shape the whole family raises, spelled here so a drift is a failure. */
    private fun refusal(construct: String, what: String, diagnostic: String, remedy: String) =
        "$construct: $what. An `expect` declaration is a signature — it carries no body and no " +
            "value — so this is \"$diagnostic\" on the JVM, on Kotlin/JS and on Kotlin/Wasm alike. " +
            "$remedy"

    private fun ctorParamMessage(keyword: String) = refusal(
        "constructorParam",
        "'x' declares a `$keyword` property on the primary constructor of an `expect` class",
        "expected class constructor cannot have a property parameter",
        "Declare it as a plain parameter — constructorParam(null, \"x\", …) or param(null, \"x\", …) " +
            "— and put the property on the `actual` class.",
    )

    /**
     * The render gap, which is *not* a member of the family — no frontend rejects the Kotlin, so
     * [refusal]'s "this is …on the JVM, on Kotlin/JS and on Kotlin/Wasm alike" clause would be a
     * false claim. A separate sentence, deliberately.
     */
    private fun renderGapMessage(name: String, keyword: String, kind: String) =
        "constructorParam: '$name' declares a `$keyword` property on the primary constructor of an " +
            "`expect $kind class`, and KotlinPoet 2.3.0 renders no such thing: a `val`/`var` " +
            "primary-constructor parameter is a property with a `%N` initializer, and " +
            "`TypeSpec.Builder.addProperty` rejects every property carrying an initializer when the " +
            "builder's own modifiers contain EXPECT. The Kotlin is valid on all three frontends — " +
            "${if (kind == "annotation") "an" else "a"} $kind class parameter must be `val`, so " +
            "the rule against a property parameter in an " +
            "`expect` class does not reach it — which makes this a backend gap and not a language " +
            "rule. Build the type with typeSpec(${kind.uppercase()}.toModifiers(), …) and add the " +
            "modifier afterwards with .toBuilder().addModifiers(EXPECT).build(), or declare it " +
            "inside the `expect` type, where Kotlin makes the keyword implicit and this DSL renders " +
            "the parameter."

    private fun bodyMessage(name: String) = refusal(
        "`fun`",
        "'$name' is `expect` — by its own EXPECT modifier, or by the `expect` type it is declared " +
            "in — and has a body",
        "expected declaration cannot have a body",
        "Drop the body; the implementation belongs on the `actual` declaration.",
    )

    private val constructorBodyMessage = refusal(
        "`constructor`",
        "this secondary constructor is declared in an `expect` type and has a body",
        "expected declaration cannot have a body",
        "Drop the body; the implementation belongs on the `actual` declaration.",
    )

    private fun delegationMessage(keyword: String) = refusal(
        "`constructor`",
        "this secondary constructor is declared in an `expect` type and delegates with " +
            "`$keyword`(…)",
        "explicit delegation call for constructor of expected class is prohibited",
        "Drop the delegation call; it belongs on the `actual` declaration.",
    )

    private val initMessage = refusal(
        "`init`",
        "this initializer block is declared in an `expect` type",
        "expected declaration cannot have a body",
        "Move the code to the `actual` declaration; an `expect` type initializes nothing.",
    )

    private val superclassMessage = refusal(
        "superclass",
        "com.example.Base is given constructor arguments in an `expect` type",
        "expected classes cannot initialize supertypes",
        "Drop the arguments — KotlinPoet renders `: com.example.Base` and drops them silently — and " +
            "initialize the supertype on the `actual` declaration.",
    )

    private fun nestedSupertypeMessage(kindName: String) = refusal(
        "superclass",
        "com.example.Base is extended by a $kindName declared in an `expect` type, and KotlinPoet " +
            "2.3.0 renders that as `: com.example.Base()` — it emits `%T(%L)` for a supertype and " +
            "drops the parentheses only when the type's own modifiers carry EXPECT, which Kotlin " +
            "forbids on a nested classifier, or when the type has secondary constructors and no " +
            "primary one (`TypeSpec.kt:238-239`)",
        "expected classes cannot initialize supertypes",
        "Use superinterface if com.example.Base is an interface; or give this $kindName a secondary " +
            "`constructor` and no constructorParam, which is the one shape KotlinPoet renders as " +
            "`: com.example.Base`; or drop the supertype and declare it on the `actual` declaration.",
    )

    private fun refused(message: String, body: FileScope.() -> Unit) {
        val e = assertFailsWith<IllegalStateException> { file("com.example", "A", body = body) }
        assertEquals(message, e.message)
    }

    /**
     * A `val`/`var` primary-constructor parameter **is** a property — this DSL builds it as a
     * separate `PropertySpec` with a `%N` initializer — so it is the same member kind the property
     * rule already covers, reached through a construction site the previous round's enumeration
     * declared safe.
     *
     * Before this fix the direct forms raised KotlinPoet's
     * `IllegalArgumentException: properties in expect classes can't have initializers` (Global
     * Constraint 26's forbidden type, naming neither construct) and the nested form **rendered**
     * `expect class E { class N(val x: Int) }`, which no frontend accepts.
     */
    @Test
    fun `an expect class refuses a val or var primary-constructor parameter`() {
        refused(ctorParamMessage("val")) { `class`(EXPECT, "E") { constructorParam(ParamKind.VAL, "x", INT) } }
        refused(ctorParamMessage("var")) { `class`(EXPECT, "E") { constructorParam(ParamKind.VAR, "x", INT) } }
        refused(ctorParamMessage("val")) { `class`(EXPECT, "E", param(ParamKind.VAL, "x", INT)) { } }
        refused(ctorParamMessage("val")) {
            `class`(EXPECT, "E") { `class`("N") { constructorParam(ParamKind.VAL, "x", INT) } }
        }
        refused(ctorParamMessage("val")) {
            `class`(EXPECT, "E") { `class`("N") { `class`("M") { constructorParam(ParamKind.VAL, "x", INT) } } }
        }
        assertEquals(
            ctorParamMessage("val"),
            assertFailsWith<IllegalStateException> {
                typeSpec(EXPECT.toModifiers(), name = "E") { constructorParam(ParamKind.VAL, "x", INT) }
            }.message,
        )
        // The exempt set is exactly `ANNOTATION` and `VALUE`, and these are what say so: every other
        // classifier modifier a nested type can carry is still *expected class constructor cannot
        // have a property parameter* on all three frontends, so widening the set by one row would be
        // a new render nothing accepts. Measured, not reasoned — the same discipline that kept
        // `LATEINIT` and `EXTERNAL` out of the accessor exemption one round ago.
        for (modifier in listOf(KModifier.DATA, KModifier.INNER, KModifier.SEALED, KModifier.OPEN)) {
            refused(ctorParamMessage("val")) {
                `class`(EXPECT, "E") { `class`(modifier, "N") { constructorParam(ParamKind.VAL, "x", INT) } }
            }
        }
    }

    /**
     * **The control row for the test above** — the nearest *valid* neighbour of the shape it
     * refuses, which is the only thing that can show a guard is not over-broad against the language.
     * Falsification shows a guard is load-bearing against the test set and nothing more, and this
     * guard was verified at the top level while the answer inverts one level down.
     *
     * Kotlin's *expected class constructor cannot have a property parameter* has two exceptions, and
     * they are the two kinds whose primary-constructor parameters Kotlin **requires** to be `val`.
     * Measured, one file per row, `kotlinc` / `kotlinc-js` / `kotlinc-wasm` 2.4.10, all three
     * identical:
     *
     * ```
     * expect class E { annotation class N(val x: Int) }   clean       — the exemption
     * expect class E { value class V(val y: Int) }        clean       — the exemption
     * expect class E { annotation class N(x: Int) }       'val' keyword is missing in annotation
     *                                                     parameter.
     * expect class E { value class V(y: Int) }            value class primary constructor must only
     *                                                     have final read-only ('val') property
     *                                                     parameters.
     * expect class E { data class D(val x: Int) }         expected class constructor cannot have a
     * expect class E { inner class N(val x: Int) }        property parameter.        (the controls
     * expect class E { sealed class S(val x: Int) }        that keep the exempt set to two: every
     * expect class E { abstract class A(val x: Int) }      other classifier modifier is refused)
     * expect class E { open class O(val x: Int) }
     * expect class E { enum class Z(val x: Int) { A(1) } }
     * ```
     *
     * So the DSL's guard made a nested `annotation class` with parameters **unspellable**: `val` was
     * refused here and a plain parameter is invalid Kotlin. Both shapes rendered at `fa12efe` and
     * were refused at `68df28f`.
     *
     * The exempt set is read off the **immediate** builder's own modifiers, which is what keeps the
     * top-level refusal below intact.
     */
    @Test
    fun `an expect container keeps a nested annotation or value class's property parameter`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public expect class E {
              public annotation class N(
                public val x: Int,
              )

              public value class V(
                public val y: Int,
              )

              public class Deep {
                public annotation class M(
                  public val z: Int,
                )
              }

              public companion object {
                public value class W(
                  public val w: Int,
                )
              }
            }

            """.trimIndent(),
            file("com.example", "A") {
                `class`(EXPECT, "E") {
                    `class`(ANNOTATION, "N") { constructorParam(ParamKind.VAL, "x", INT) }
                    `class`(VALUE, "V") { constructorParam(ParamKind.VAL, "y", INT) }
                    // Two levels down and inside the companion object, because the exemption reads
                    // the immediate builder and the *refusal* it sits inside reads a fact inherited
                    // to every depth — so both depths are controls, not one.
                    `class`("Deep") { `class`(ANNOTATION, "M") { constructorParam(ParamKind.VAL, "z", INT) } }
                    companionObject { `class`(VALUE, "W") { constructorParam(ParamKind.VAL, "w", INT) } }
                }
            }.toString(),
        )
    }

    /**
     * The other side of that boundary, and the reason the exemption reads the **immediate** builder
     * rather than the nest. `expect annotation class A(val x: Int)` and `expect value class V(val
     * y: Int)` are clean on all three frontends too — and KotlinPoet 2.3.0 cannot render either by
     * any route: `TypeSpec.Builder.addProperty` (`TypeSpec.kt:725-733`) `require`s a null
     * `initializer` whenever the builder's own modifiers contain `EXPECT`, with no exemption of its
     * own, and a `val`/`var` primary-constructor parameter **is** a property with a `%N` initializer
     * (D19 — KotlinPoet models it no other way, and `constructorProperties` matches on exactly that
     * initializer).
     *
     * So this is a **render gap**, not a language rule, and the message says so rather than quoting
     * a frontend diagnostic that does not exist. At `fa12efe` the same shape raised KotlinPoet's own
     * `IllegalArgumentException: properties in expect classes can't have initializers` — Global
     * Constraint 26's forbidden type, naming neither construct — so the refusal is not new; only its
     * type and its message are.
     *
     * The escape hatch the message names is measured, not assumed: a `TypeSpec` built *without*
     * `EXPECT` passes `addProperty`, and `toBuilder().addModifiers(EXPECT)` afterwards never runs
     * that `require` again.
     */
    @Test
    fun `an expect annotation or value class cannot carry a property parameter KotlinPoet can render`() {
        refused(renderGapMessage("x", "val", "annotation")) {
            `class`(EXPECT + ANNOTATION, "A") { constructorParam(ParamKind.VAL, "x", INT) }
        }
        refused(renderGapMessage("y", "val", "value")) {
            `class`(EXPECT + VALUE, "V") { constructorParam(ParamKind.VAL, "y", INT) }
        }
        assertEquals(
            renderGapMessage("x", "var", "annotation"),
            assertFailsWith<IllegalStateException> {
                typeSpec((EXPECT + ANNOTATION), name = "A") { constructorParam(ParamKind.VAR, "x", INT) }
            }.message,
        )
        // The escape hatch, rendered: the spec is built without EXPECT and gains it afterwards.
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public expect annotation class A(
              public val x: Int,
            )

            """.trimIndent(),
            file("com.example", "A") {
                +typeSpec(ANNOTATION.toModifiers(), name = "A") { constructorParam(ParamKind.VAL, "x", INT) }
                    .toBuilder()
                    .addModifiers(EXPECT)
                    .build()
            }.toString(),
        )
    }

    /**
     * The function-side twin, at every depth and in every `expect` container — and the file-level
     * form, where the modifier is the property's own. `expect fun f(): Int = 1` raised KotlinPoet's
     * `IllegalArgumentException: abstract or expect function f cannot have code`; the direct member
     * raised `… functions in expect classes can't have bodies`; one level down it was an
     * `IllegalStateException: function f cannot have code`, thrown from `FunSpec.emit` — that is,
     * from `toString()`, outside any DSL call at all.
     */
    @Test
    fun `an expect container refuses a function body`() {
        refused(bodyMessage("f")) { `class`(EXPECT, "E") { `fun`("f", returns = INT) { ret(1.lit) } } }
        refused(bodyMessage("f")) {
            `class`(EXPECT, "E") { `class`("N") { `fun`("f", returns = INT) { ret(1.lit) } } }
        }
        refused(bodyMessage("f")) {
            `class`(EXPECT, "E") { companionObject { `fun`("f", returns = INT) { ret(1.lit) } } }
        }
        refused(bodyMessage("f")) { `object`(EXPECT, "O") { `fun`("f", returns = INT) { ret(1.lit) } } }
        refused(bodyMessage("f")) { `interface`(EXPECT, "I") { `fun`("f", returns = INT) { ret(1.lit) } } }
        refused(bodyMessage("f")) { `fun`(EXPECT, "f", returns = INT) { ret(1.lit) } }
        assertEquals(
            bodyMessage("f"),
            assertFailsWith<IllegalStateException> {
                typeSpec(EXPECT.toModifiers(), name = "E") { `fun`("f", returns = INT) { ret(1.lit) } }
            }.message,
        )
        assertEquals(
            bodyMessage("f"),
            assertFailsWith<IllegalStateException> {
                funSpec(EXPECT.toModifiers(), "f", returns = INT) { ret(1.lit) }
            }.message,
        )
    }

    /** A secondary constructor is a function with a body, and the same rule reaches it. */
    @Test
    fun `an expect class refuses a secondary constructor's body and its delegation call`() {
        refused(constructorBodyMessage) {
            `class`(EXPECT, "E") { `constructor`(param("p", INT)) { +call("println") } }
        }
        refused(delegationMessage("super")) {
            `class`(EXPECT, "E") { `constructor`(param("p", INT)) { p -> `super`(p) } }
        }
        refused(delegationMessage("this")) {
            `class`(EXPECT, "E") {
                constructorParam(null, "q", INT)
                `constructor`(param("p", INT)) { p -> `this`(p) }
            }
        }
    }

    /**
     * The third member of the family. KotlinPoet answers the *direct* case with
     * `IllegalStateException: expect CLASS can't have initializer blocks` — the right exception type
     * with a message naming neither construct — and renders every nested one, which no frontend
     * accepts even when the block is empty.
     */
    @Test
    fun `an expect container refuses an init block`() {
        refused(initMessage) { `class`(EXPECT, "E") { `init` { +call("println") } } }
        refused(initMessage) { `class`(EXPECT, "E") { `class`("N") { `init` { +call("println") } } } }
        refused(initMessage) { `class`(EXPECT, "E") { companionObject { `init` { +call("println") } } } }
        refused(initMessage) { `object`(EXPECT, "O") { `init` { +call("println") } } }
        refused(initMessage) { `class`(EXPECT, "E") { `class`("N") { `init` { } } } }
        assertEquals(
            initMessage,
            assertFailsWith<IllegalStateException> {
                typeSpec(EXPECT.toModifiers(), name = "E") { `init` { +call("println") } }
            }.message,
        )
    }

    /**
     * The member kind the enumeration turned up that nothing had filed: superclass constructor
     * arguments. `TypeSpec.emit` drops them when the type carries `EXPECT`
     * (`TypeSpec.kt:239` — `if (!areNestedExternal && !modifiers.contains(EXPECT))`), so
     * `` `class`(EXPECT, "E") { superclass(Base, 1.lit) } `` rendered `expect class E : Base` and
     * the argument reached no output at all — the *silently wrong output* Global Constraint 26
     * forbids. One level down nothing is dropped and `expect class E { class N : Base(1) }` renders,
     * which all three frontends answer with *expected classes cannot initialize supertypes*.
     */
    @Test
    fun `an expect container refuses superclass constructor arguments`() {
        refused(superclassMessage) { `class`(EXPECT, "E") { superclass(base, 1.lit) } }
        refused(superclassMessage) { `class`(EXPECT, "E") { `class`("N") { superclass(base, 1.lit) } } }
        refused(superclassMessage) { `class`(EXPECT, "E") { companionObject { superclass(base, 1.lit) } } }
    }

    /**
     * The sixth member of the family, and the one the previous round verified at the top level while
     * the answer inverts one level down. `applySuperclass` fires on `args.isNotEmpty()`, which is
     * the whole rule for a builder that carries `EXPECT` itself — KotlinPoet drops the parentheses
     * there. One level down it does not: `TypeSpec.emit` writes `%T(%L)` for a supertype and falls
     * back to a bare `%T` only when `areNestedExternal || EXPECT in modifiers`
     * (`TypeSpec.kt:239` — the *immediate* builder's own modifiers, which Kotlin forbids a nested
     * classifier to carry) or when the type has secondary constructors and no primary one
     * (`TypeSpec.kt:238`). So an **empty** argument list still produces `: Base()`. Measured, one
     * file per row, all three frontends identical:
     *
     * ```
     * expect class E { class N : Base() }              supertype initialization is impossible
     * expect class E { class N { class M : Base() } }  without a primary constructor.  +  expected
     * expect class E { companion object : Base() }     classes cannot initialize supertypes.
     * expect class E { class N(z: Int) : Base() }      expected classes cannot initialize supertypes.
     * ```
     *
     * And the control rows — the nearest *valid* neighbours, which is what says the refusal is not
     * over-broad. Every one of them is clean on all three:
     *
     * ```
     * expect class E { class N : Base }                            — the shape this cannot render
     * expect class E { class N(z: Int) : Base }
     * expect class E { class N : Iface }                           — superinterface, never parenthesized
     * expect class E { class N : Base { constructor(p: Int) } }    — the one shape it *can* render
     * expect class E : Base                                        — the direct case, unchanged
     * class Outer { class N : Base() }                             — outside `expect`, unchanged
     * ```
     *
     * The check lives in [TypeScope.finish] rather than in `applySuperclass` for the reason
     * [superclassArgsPlusSecondary] does: whether the parentheses are emitted depends on constructors
     * that may be written after the supertype, so an eager check would answer on writing order.
     */
    @Test
    fun `an expect container refuses a nested supertype it can only render with parentheses`() {
        refused(nestedSupertypeMessage("class")) { `class`(EXPECT, "E") { `class`("N") { superclass(base) } } }
        refused(nestedSupertypeMessage("class")) {
            `class`(EXPECT, "E") { `class`("N") { `class`("M") { superclass(base) } } }
        }
        refused(nestedSupertypeMessage("companion object")) {
            `class`(EXPECT, "E") { companionObject { superclass(base) } }
        }
        refused(nestedSupertypeMessage("class")) {
            `class`(EXPECT, "E") {
                `class`("N") {
                    constructorParam(null, "z", INT)
                    superclass(base)
                }
            }
        }
        // Writing order does not decide it: the constructor parameter can come after the supertype.
        refused(nestedSupertypeMessage("class")) {
            `class`(EXPECT, "E") {
                `class`("N") {
                    superclass(base)
                    constructorParam(null, "z", INT)
                }
            }
        }
        assertEquals(
            nestedSupertypeMessage("class"),
            assertFailsWith<IllegalStateException> {
                typeSpec(EXPECT.toModifiers(), name = "E") { `class`("N") { superclass(base) } }
            }.message,
        )
        // `TypeSpec.emit` also drops the parentheses for an `external` type (`areNestedExternal`,
        // same line), and this check deliberately does not read that: `external` inside `expect` is
        // *expected declaration cannot be external* on all three frontends, plus *external type
        // extends non-external type 'Base'* and *non-top-level 'external' declaration* on JS and
        // Wasm, so no target accepts the shape either way. The refusal is pinned here because the
        // message's "renders that as `: Base()`" is the one clause that is inexact for it.
        refused(nestedSupertypeMessage("class")) {
            `class`(EXPECT, "E") { `class`(KModifier.EXTERNAL, "N") { superclass(base) } }
        }
    }

    /** The control rows for the refusal above, rendered rather than described. */
    @Test
    fun `an expect container keeps every supertype it can render without parentheses`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public expect class E : Base {
              public class N : Iface

              public class P(
                z: Int,
              ) : Iface

              public class R : Base {
                public constructor(p: Int)
              }
            }

            public class Outer {
              public class N : Base()
            }

            """.trimIndent(),
            file("com.example", "A") {
                val iface = ClassName("com.example", "Iface")
                `class`(EXPECT, "E") {
                    superclass(base)
                    `class`("N") { superinterface(iface) }
                    `class`("P") {
                        constructorParam(null, "z", INT)
                        superinterface(iface)
                    }
                    `class`("R") {
                        superclass(base)
                        `constructor`(param("p", INT)) { }
                    }
                }
                `class`("Outer") { `class`("N") { superclass(base) } }
            }.toString(),
        )
    }

    /**
     * The boundary, in one render: a member of an `expect` type is a **signature**, and every
     * signature this round could have refused by accident still comes out. A bodiless function, a
     * bodiless secondary constructor, a plain primary-constructor parameter, a supertype with no
     * arguments, and the same shapes one level down.
     */
    @Test
    fun `an expect container still renders every signature`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public expect class E(
              x: Int,
            ) : Base {
              public val y: Int

              public constructor(p: Int)

              public fun f(): Int

              public fun g(p: Int = 1): Int

              public class N(
                z: Int,
              ) {
                public fun h(): Int
              }

              public companion object {
                public fun of(): Int
              }
            }

            public expect fun top(): Int

            """.trimIndent(),
            file("com.example", "A") {
                `class`(EXPECT, "E") {
                    superclass(base)
                    constructorParam(null, "x", INT)
                    `val`("y", INT)
                    `constructor`(param("p", INT)) { }
                    `fun`("f", returns = INT) { }
                    `fun`("g", param("p", INT, default = 1.lit), returns = INT) { }
                    `class`("N") {
                        constructorParam(null, "z", INT)
                        `fun`("h", returns = INT) { }
                    }
                    companionObject { `fun`("of", returns = INT) { } }
                }
                `fun`(EXPECT, "top", returns = INT) { }
            }.toString(),
        )
    }

    /**
     * The other control: a container that is **not** `expect` keeps every one of these members. All
     * five refusals above are container-dependent, and this is what says so.
     */
    @Test
    fun `an ordinary container keeps every member the expect rule refuses`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public class C(
              public val x: Int,
            ) : Base(1) {
              init {
                println()
              }

              public constructor(p: Int) : this(p)

              public fun f(): Int = 1
            }

            """.trimIndent(),
            file("com.example", "A") {
                `class`("C") {
                    superclass(base, 1.lit)
                    constructorParam(ParamKind.VAL, "x", INT)
                    `init` { +call("println") }
                    `constructor`(param("p", INT)) { p -> `this`(p) }
                    `fun`("f", returns = INT) { ret(1.lit) }
                }
            }.toString(),
        )
    }

    /**
     * D25's rule — every secondary constructor of a class that has a primary one delegates to it
     * with `: this(…)` — is **inverted** in an `expect` type, where the delegation call is
     * prohibited outright. `expect class E(x: Int) { constructor(p: Long) }` and the same with a
     * supertype are clean on all three frontends, while the ordinary `class E(x: Int) {
     * constructor(p: Long) }` is *primary constructor call expected* on all three.
     *
     * Both writing orders, because D25's guard is two `check`s in two places for exactly that
     * reason: the parameter can be written after the constructor. Without the exemption on **both**
     * the shape has no spelling at all — refused here for not delegating, refused in `buildFun` for
     * delegating.
     */
    @Test
    fun `an expect class takes an undelegated secondary constructor in either order`() {
        val expected =
            """
            package com.example

            import kotlin.Int
            import kotlin.Long

            public expect class E(
              x: Int,
            ) {
              public constructor(p: Long)
            }

            """.trimIndent()
        assertEquals(
            expected,
            file("com.example", "A") {
                `class`(EXPECT, "E") {
                    constructorParam(null, "x", INT)
                    `constructor`(param("p", LONG)) { }
                }
            }.toString(),
        )
        assertEquals(
            expected,
            file("com.example", "A") {
                `class`(EXPECT, "E") {
                    `constructor`(param("p", LONG)) { }
                    constructorParam(null, "x", INT)
                }
            }.toString(),
        )
    }

    /**
     * kotlinc's verdict on **the renders this round removed** — the exact text the DSL produced for
     * each of them, or would have produced had KotlinPoet not thrown first, handed to the frontend.
     *
     * The assertion is on **messages** rather than on the exit code, for D36's reason: a single
     * compilation unit has no platform source set to hold the `actual`, so an `expect` compilation
     * always ends in `COMPILATION_ERROR` on that ground alone. Each row asserts the diagnostic that
     * names the rule. Only the JVM frontend is reachable from kctfork; the `kotlinc-js` and
     * `kotlinc-wasm` runs are hand-run and recorded in [Expect] and in D40.
     */
    @Test
    fun `every shape the expect rule rejects is one kotlinc rejects`() {
        listOf(
            "public expect class E {\n  public class N(\n    public val x: Int,\n  )\n}" to
                "class constructor cannot have a property parameter",
            "public expect class E(\n  public val x: Int,\n)" to
                "class constructor cannot have a property parameter",
            "public expect class E {\n  public class N {\n    public fun f(): Int = 1\n  }\n}" to
                "declaration cannot have a body",
            "public expect class E {\n  public fun f(): Int = 1\n}" to "declaration cannot have a body",
            "public expect fun f(): Int = 1" to "declaration cannot have a body",
            "public expect class E {\n  public class N {\n    init {\n      println()\n    }\n  }\n}" to
                "declaration cannot have a body",
            "public expect class E {\n  init {\n    println()\n  }\n}" to "declaration cannot have a body",
            "public expect class E : Base(1)" to "cannot initialize supertypes",
            "public expect class E {\n  public class N : Base(1)\n}" to "cannot initialize supertypes",
            "public expect class E : Base {\n  public constructor(p: Int) : super(p)\n}" to
                "delegation call for constructor of expected class is prohibited",
            // The render this round removed, with an **empty** argument list — the shape the
            // `args.isNotEmpty()` guard was verified against at the top level and misses at depth.
            "public expect class E {\n  public class N : Base()\n}" to "cannot initialize supertypes",
            "public expect class E {\n  public class N(\n    z: Int,\n  ) : Base()\n}" to
                "cannot initialize supertypes",
            "public expect class E {\n  public companion object : Base()\n}" to
                "cannot initialize supertypes",
        ).forEach { (source, diagnostic) ->
            val messages = compileMultiplatform(
                "package com.example\n\nimport kotlin.Int\n\npublic open class Base(\n  q: Int,\n)\n\n$source\n",
            ).messages
            assertTrue(diagnostic in messages, "$source\n$messages")
        }
    }

    /**
     * The same frontend, on the signature side: the render the test above's controls produce draws
     * **none** of those diagnostics. This is what makes the round a measurement of where the
     * boundary is rather than of the language alone — the source below is this DSL's own output, and
     * every line of it is a member kind the rule refuses in some other shape.
     *
     * **Every control row this round added is in here**, rendered by the DSL rather than typed by
     * hand: the nested `annotation class` and `value class` with `val` parameters, a nested
     * superinterface with and without a primary constructor, and the one supertype shape KotlinPoet
     * renders without parentheses (secondary constructors, no primary). Only the JVM frontend is
     * reachable from kctfork; the `kotlinc-js` and `kotlinc-wasm` runs of the same rows are hand-run
     * and recorded in [Expect], in [nestedSupertypeRenderGap] and in D40.
     */
    @Test
    fun `every signature the expect rule keeps is one kotlinc keeps`() {
        val rendered = file("com.example", "Signatures") {
            val iface = ClassName("com.example", "Iface")
            `class`("Base") { constructorParam(null, "q", INT) }
            `interface`("Iface") { }
            `class`(EXPECT, "E") {
                superclass(ClassName("com.example", "Base"))
                constructorParam(null, "x", INT)
                `val`("y", INT)
                `constructor`(param("p", INT)) { }
                `fun`("f", returns = INT) { }
                `class`("N") {
                    constructorParam(null, "z", INT)
                    `fun`("h", returns = INT) { }
                }
                `class`(ANNOTATION, "Ann") { constructorParam(ParamKind.VAL, "a", INT) }
                `class`(VALUE, "V") { constructorParam(ParamKind.VAL, "b", INT) }
                `class`("Impl") { superinterface(iface) }
                `class`("ImplWithCtor") {
                    constructorParam(null, "c", INT)
                    superinterface(iface)
                }
                `class`("Sub") {
                    superclass(ClassName("com.example", "Base"))
                    `constructor`(param("d", INT)) { }
                }
                companionObject { `fun`("of", returns = INT) { } }
            }
            `fun`(EXPECT, "top", returns = INT) { }
        }.toString()

        val messages = compileMultiplatform(rendered).messages
        listOf(
            "cannot have a property parameter",
            "cannot have a body",
            "cannot initialize supertypes",
            "supertype initialization is impossible",
            "delegation call",
            "must be initialized",
            "'val' keyword is missing",
            "final read-only",
        ).forEach { diagnostic -> assertFalse(diagnostic in messages, "$diagnostic\n$rendered\n$messages") }
    }
}
