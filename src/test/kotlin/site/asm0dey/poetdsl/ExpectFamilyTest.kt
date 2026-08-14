package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.EXPECT
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
        ).forEach { (source, diagnostic) ->
            val messages = compileMultiplatform(
                "package com.example\n\nimport kotlin.Int\n\npublic open class Base(\n  q: Int,\n)\n\n$source\n",
            ).messages
            assertTrue(diagnostic in messages, "$source\n$messages")
        }
    }

    /**
     * The same frontend, on the signature side: the render the test above's controls produce draws
     * **none** of those four diagnostics. This is what makes the round a measurement of where the
     * boundary is rather than of the language alone — the source below is this DSL's own output, and
     * every line of it is a member kind the rule refuses in some other shape.
     */
    @Test
    fun `every signature the expect rule keeps is one kotlinc keeps`() {
        val rendered = file("com.example", "Signatures") {
            `class`("Base") { constructorParam(null, "q", INT) }
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
                companionObject { `fun`("of", returns = INT) { } }
            }
            `fun`(EXPECT, "top", returns = INT) { }
        }.toString()

        val messages = compileMultiplatform(rendered).messages
        listOf(
            "cannot have a property parameter",
            "cannot have a body",
            "cannot initialize supertypes",
            "delegation call",
            "must be initialized",
        ).forEach { diagnostic -> assertFalse(diagnostic in messages, "$diagnostic\n$rendered\n$messages") }
    }
}
