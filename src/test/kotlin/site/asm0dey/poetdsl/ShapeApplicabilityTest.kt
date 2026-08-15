package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.INFIX
import com.squareup.kotlinpoet.KModifier.OPERATOR
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.KModifier.VARARG
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E2e — the **fourth axis**, and the two thirds of its `override`/`infix`/`operator` corner that are
 * decidable after all.
 *
 * D42 filed all three under *"the signature or the supertypes decide, therefore not decidable from
 * what this DSL is given"*. That is true of `override` and false of the other two:
 *
 * - **`infix`** is decidable from the **parameter list**, which `buildFun` is handed: Kotlin wants
 *   exactly one value parameter and no `vararg`, plus a member or an extension receiver.
 * - **`operator`** is decidable from the **name**, which is a language constant. The check is a
 *   *necessary* condition only — `operator fun plus(o: Int)` may still be wrong for its arity or its
 *   return type, and that half stays with the frontend — so it can refuse nothing valid.
 * - **`override`** stays unguarded: *'f' overrides nothing* is the supertypes' answer and this DSL
 *   never sees them.
 *
 * Every refusal below is measured on `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10, and every one
 * has its nearest valid neighbour compiled in the same test.
 */
class ShapeApplicabilityTest {

    private fun render(body: FileScope.() -> Unit): String = file("com.example", "P", body = body).toString()

    private fun message(body: FileScope.() -> Unit): String =
        assertFailsWith<IllegalStateException> { render(body) }.message!!

    // --- infix, from the parameter list ---------------------------------------------------------

    /**
     *     class C { infix fun f() { } }                  'infix' modifier is inapplicable to this
     *     class C { infix fun f(x: Int, y: Int) { } }    function.
     *     class C { infix fun f(vararg x: Int) { } }
     *
     * and the neighbour, clean on all three: `class C { infix fun f(x: Int) { } }`.
     */
    @Test
    fun `infix needs exactly one value parameter`() {
        val none = message { `class`("C") { `fun`(INFIX, "f") { } } }
        assertTrue("'infix' modifier is inapplicable to this function" in none, none)
        assertTrue("no value parameter" in none, none)
        val two = message {
            `class`("C") { `fun`(INFIX, "f", param("x", INT), param("y", INT)) { _, _ -> } }
        }
        assertTrue("'infix' modifier is inapplicable to this function" in two, two)
        val varargs = message {
            `class`("C") { `fun`(INFIX, "f", param("x", INT, modifiers = VARARG)) { _ -> } }
        }
        assertTrue("VARARG" in varargs, varargs)
        assertTrue("'infix' modifier is inapplicable to this function" in varargs, varargs)
        // …at every depth and in every container a function can be written in, including a block.
        listOf<Pair<String, FileScope.() -> Unit>>(
            "at file level" to { `fun`(INFIX, "f", receiver = INT) { } },
            "in an interface" to { `interface`("I") { `fun`(INFIX + ABSTRACT, "f", returns = INT) { } } },
            "in an object" to { `object`("O") { `fun`(INFIX, "f") { } } },
            "in a companion object" to { `class`("C") { companionObject { `fun`(INFIX, "f") { } } } },
            "one level down" to { `class`("O") { `class`("N") { `fun`(INFIX, "f") { } } } },
            // A **block**, the container the previous round's own new code missed. Nothing renders
            // there — a local function is unrenderable in this DSL (KotlinPoet emits an implicit
            // `public` on a spliced `FunSpec`) — but this rule is asked before the render is
            // attempted, so the sentence a caller gets is still this one.
            "in a block" to { `fun`("outer") { `fun`(INFIX, "h") { } } },
        ).forEach { (label, body) ->
            val m = message(body)
            assertTrue("'infix' modifier is inapplicable to this function" in m, "$label: $m")
        }
        // …and the detached builder, which has no container and still knows its own parameter list.
        val detached = assertFailsWith<IllegalStateException> {
            funSpec(INFIX.toModifiers(), name = "f") { }
        }.message!!
        assertTrue("'infix' modifier is inapplicable to this function" in detached, detached)
    }

    /**
     * The control rows. **A default value is not part of the rule** — `infix fun f(x: Int = 1)` is
     * clean on all three frontends on 2.4.10, whatever the documentation says, so the guard does not
     * mention one.
     */
    @Test
    fun `infix still renders on a function shaped for it`() {
        assertCompiles(
            render {
                `class`("C") {
                    `fun`(INFIX, "f", param("x", INT)) { _ -> }
                    `fun`(INFIX, "withDefault", param("x", INT, default = 1.lit)) { _ -> }
                    `fun`(INFIX, "onAReceiver", param("x", INT), receiver = STRING) { _ -> }
                }
                `interface`("I") { `fun`(INFIX + ABSTRACT, "g", param("x", INT), returns = INT) { _ -> } }
                `object`("O") { `fun`(INFIX, "h", param("x", INT)) { _ -> } }
                `class`("D") { companionObject { `fun`(INFIX, "i", param("x", INT)) { _ -> } } }
                // an extension, which is the other half of *a member or an extension*.
                `fun`(INFIX, "j", param("x", INT), receiver = INT) { _ -> }
            },
        )
        // …and the detached builder still renders the one shape that is right everywhere.
        assertTrue("infix" in funSpec(INFIX.toModifiers(), name = "f", p1 = param("x", INT)) { }.toString())
        // **The nearest valid neighbour in a block**, which no control row can compile: a local
        // extension `infix fun Int.h(x: Int)` is clean on all three frontends (measured) and this
        // DSL cannot render *any* local function, so what a caller gets is the render gap's own
        // message and not one of this round's. That is the guard not firing, which is the claim.
        val local = assertFailsWith<IllegalStateException> {
            render { `fun`("outer") { `fun`(INFIX, "k", param("x", INT), receiver = INT) { _ -> } } }
        }.message!!
        assertTrue("A local function cannot be rendered" in local, local)
    }

    /**
     *     infix fun f(x: Int) { }                       'infix' modifier is inapplicable to this
     *     fun outer() { infix fun h(x: Int) { } }       function.
     *
     * and the neighbours, clean on all three: the same two with an extension receiver, and any
     * member. Measured one file per row.
     */
    @Test
    fun `infix needs a member or an extension`() {
        val topLevel = message { `fun`(INFIX, "f", param("x", INT)) { _ -> } }
        assertTrue("'infix' modifier is inapplicable to this function" in topLevel, topLevel)
        assertTrue("extension receiver" in topLevel, topLevel)
        val local = message { `fun`("outer") { `fun`(INFIX, "h", param("x", INT)) { _ -> } } }
        assertTrue("'infix' modifier is inapplicable to this function" in local, local)
        // …and the detached builder answers for no container: spliced into a class it is valid.
        assertTrue(
            "infix" in funSpec(INFIX.toModifiers(), name = "f", p1 = param("x", INT)) { }.toString(),
        )
    }

    // --- operator, from the name ----------------------------------------------------------------

    /**
     * Kotlin's operator names are a closed set, so a function named anything else cannot carry
     * `operator` in any container, at any arity, with or without a receiver — which is what makes a
     * name-only check a **necessary** condition and unable to over-refuse.
     *
     *     class C { operator fun f(x: Int) { } }    'operator' modifier is not applicable to
     *     operator fun Int.f(x: Int) { }            function: illegal function name.
     *     interface I { operator fun f(x: Int) }
     */
    @Test
    fun `operator needs one of Kotlin's operator names`() {
        listOf<Pair<String, FileScope.() -> Unit>>(
            "in a class" to { `class`("C") { `fun`(OPERATOR, "f", param("x", INT)) { _ -> } } },
            "at file level" to { `fun`(OPERATOR, "f", param("x", INT), receiver = INT) { _ -> } },
            "in an interface" to {
                `interface`("I") { `fun`(OPERATOR + ABSTRACT, "f", param("x", INT), returns = INT) { _ -> } }
            },
            "in an object" to { `object`("O") { `fun`(OPERATOR, "f", param("x", INT)) { _ -> } } },
            "in a companion object" to {
                `class`("C") { companionObject { `fun`(OPERATOR, "f", param("x", INT)) { _ -> } } }
            },
            "one level down" to {
                `class`("O") { `class`("N") { `fun`(OPERATOR, "f", param("x", INT)) { _ -> } } }
            },
            "in a block" to { `fun`("outer") { `fun`(OPERATOR, "f", param("x", INT), receiver = INT) { _ -> } } },
            "in a block, with no receiver" to { `fun`("outer") { `fun`(OPERATOR, "f", param("x", INT)) { _ -> } } },
        ).forEach { (label, body) ->
            val m = message(body)
            assertTrue("illegal function name" in m, "$label: $m")
        }
        // `mod` and `modAssign` were operator names once and are not on 2.4.10 — measured, all three
        // frontends, *illegal function name* like any other.
        assertTrue("illegal function name" in message { `class`("C") { `fun`(OPERATOR, "mod", param("x", INT)) { _ -> } } })
        // …and the detached builder, since the name travels with the spec wherever it is spliced.
        val detached = assertFailsWith<IllegalStateException> {
            funSpec(OPERATOR.toModifiers(), name = "f") { }
        }.message!!
        assertTrue("illegal function name" in detached, detached)
    }

    /** The control rows: one of every shape of operator name Kotlin has, compiled. */
    @Test
    fun `operator still renders on an operator name`() {
        assertCompiles(
            render {
                `class`("C") {
                    `fun`(OPERATOR, "plus", param("o", INT), returns = INT) { _ -> ret(1.lit) }
                    `fun`(OPERATOR, "get", param("i", INT), returns = INT) { _ -> ret(1.lit) }
                    `fun`(OPERATOR, "invoke", returns = INT) { ret(1.lit) }
                    `fun`(OPERATOR, "component1", returns = INT) { ret(1.lit) }
                    `fun`(OPERATOR, "component10", returns = INT) { ret(1.lit) }
                    `fun`(OPERATOR, "contains", param("o", INT), returns = BOOLEAN) { _ -> ret(true.lit) }
                    `fun`(OPERATOR, "compareTo", param("o", INT), returns = INT) { _ -> ret(1.lit) }
                    `fun`(OPERATOR, "rangeTo", param("o", INT), returns = INT) { _ -> ret(1.lit) }
                    `fun`(OPERATOR, "rangeUntil", param("o", INT), returns = INT) { _ -> ret(1.lit) }
                    `fun`(OPERATOR, "unaryMinus", returns = INT) { ret(1.lit) }
                    `fun`(OPERATOR, "not", returns = BOOLEAN) { ret(true.lit) }
                    `fun`(OPERATOR, "plusAssign", param("o", INT)) { _ -> }
                }
                // an extension operator at file level
                `fun`(OPERATOR, "times", param("o", STRING), returns = INT, receiver = INT) { _ -> ret(1.lit) }
            },
        )
        // …and in a block, where the neighbour cannot be compiled because no local function renders
        // at all: what comes back is the render gap's message, which is this guard not firing.
        val local = assertFailsWith<IllegalStateException> {
            render {
                `fun`("outer") {
                    `fun`(OPERATOR, "div", param("o", STRING), returns = INT, receiver = INT) { _ -> ret(1.lit) }
                }
            }
        }.message!!
        assertTrue("A local function cannot be rendered" in local, local)
        assertTrue("operator" in funSpec(OPERATOR.toModifiers(), name = "plus", p1 = param("o", INT)) { }.toString())
    }

    /**
     *     operator fun plus(x: Int) { }                    'operator' modifier is not applicable to
     *     fun outer() { operator fun plus(x: Int) { } }    function: must be a member or an
     *                                                      extension function.
     *
     * The same table as `infix`'s, measured separately because the sentence differs.
     */
    @Test
    fun `operator needs a member or an extension`() {
        val topLevel = message { `fun`(OPERATOR, "plus", param("x", INT)) { _ -> } }
        assertTrue("must be a member or an extension function" in topLevel, topLevel)
        val local = message { `fun`("outer") { `fun`(OPERATOR, "plus", param("x", INT)) { _ -> } } }
        assertTrue("must be a member or an extension function" in local, local)
    }

    // --- override, the third of the three, which stays unguarded ---------------------------------

    /**
     * **The one that really is undecidable**, and the whole reason the other two needed separating
     * from it: whether `override fun f()` is *'f' overrides nothing* depends on the supertypes, and
     * this DSL is handed a `ClassName` for a supertype and never its members. Nine cells of D42's
     * matrix, deliberately open.
     */
    @Test
    fun `override is left to the frontend`() {
        assertCompiles(
            render {
                `interface`("Base") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`("Impl") {
                    superinterface(ClassName("com.example", "Base"))
                    `fun`(OVERRIDE, "f", returns = INT) { ret(1.lit) }
                }
            },
        )
        // …and it still renders where no supertype declares it, because nothing here can tell.
        assertTrue(
            "override" in render {
                `class`("C") { `fun`(OVERRIDE, "g") { } }
            },
        )
    }
}
