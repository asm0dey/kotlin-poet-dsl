package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.ACTUAL
import com.squareup.kotlinpoet.KModifier.ANNOTATION
import com.squareup.kotlinpoet.KModifier.COMPANION
import com.squareup.kotlinpoet.KModifier.CONST
import com.squareup.kotlinpoet.KModifier.DATA
import com.squareup.kotlinpoet.KModifier.ENUM
import com.squareup.kotlinpoet.KModifier.EXPECT
import com.squareup.kotlinpoet.KModifier.EXTERNAL
import com.squareup.kotlinpoet.KModifier.FINAL
import com.squareup.kotlinpoet.KModifier.FUN
import com.squareup.kotlinpoet.KModifier.INFIX
import com.squareup.kotlinpoet.KModifier.INLINE
import com.squareup.kotlinpoet.KModifier.INNER
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.KModifier.LATEINIT
import com.squareup.kotlinpoet.KModifier.OPEN
import com.squareup.kotlinpoet.KModifier.OPERATOR
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.KModifier.PRIVATE
import com.squareup.kotlinpoet.KModifier.PROTECTED
import com.squareup.kotlinpoet.KModifier.PUBLIC
import com.squareup.kotlinpoet.KModifier.SEALED
import com.squareup.kotlinpoet.KModifier.SUSPEND
import com.squareup.kotlinpoet.KModifier.TAILREC
import com.squareup.kotlinpoet.KModifier.VALUE
import com.squareup.kotlinpoet.KModifier.VARARG
import com.squareup.kotlinpoet.STRING
import site.asm0dey.poetdsl.ParamKind.VAL
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E2d — the modifier × declaration-form × position matrix (**D42**).
 *
 * One question: *may this declaration form carry this modifier at all?* It is container-independent
 * by construction, which is what lets every refusal below be checked at all four positions **and**
 * at the detached builder — the half of the axis a container-keyed rule cannot reach.
 *
 * Every refusal has its nearest valid neighbour in the same test, per the standing method, and the
 * three modifiers this rule deliberately does **not** carry — `override`, `infix` and `operator`,
 * whose validity is decided by the supertypes or the signature — have a control of their own.
 */
class ModifierApplicabilityTest {

    private fun render(body: FileScope.() -> Unit): String = file("com.example", "P", body = body).toString()

    private fun message(body: FileScope.() -> Unit): String =
        assertFailsWith<IllegalStateException> { render(body) }.message!!

    /** The refusal at all four positions a classifier can occupy, including the detached builder. */
    private fun everywhere(modifier: KModifier, expected: String) {
        listOf<Pair<String, FileScope.() -> Unit>>(
            "file" to { `class`(modifier, "M") { } },
            "class" to { `class`("Outer") { `class`(modifier, "M") { } } },
            "nested" to { `class`("Outer") { `class`("Inner") { `class`(modifier, "M") { } } } },
            "detached" to { +typeSpec(modifier.toModifiers(), name = "M") { } },
        ).forEach { (label, body) ->
            val m = message(body)
            assertTrue(expected in m, "$modifier at $label: $m")
        }
    }

    // --- a class ------------------------------------------------------------------------------

    @Test
    fun `a class refuses every modifier Kotlin does not apply to one`() {
        everywhere(SUSPEND, "modifier 'suspend' is not applicable to 'class'")
        everywhere(CONST, "modifier 'const' is not applicable to 'class'")
        everywhere(LATEINIT, "modifier 'lateinit' is not applicable to 'class'")
        everywhere(VARARG, "modifier 'vararg' is not applicable to 'class'")
        everywhere(TAILREC, "modifier 'tailrec' is not applicable to 'class'")
        everywhere(OVERRIDE, "modifier 'override' is not applicable to 'class'")
        everywhere(COMPANION, "modifier 'companion' is not applicable to 'class'")
        everywhere(KModifier.IN, "modifier 'in' is not applicable to 'class'")
        everywhere(KModifier.OUT, "modifier 'out' is not applicable to 'class'")
        everywhere(KModifier.REIFIED, "modifier 'reified' is not applicable to 'class'")
        everywhere(KModifier.NOINLINE, "modifier 'noinline' is not applicable to 'class'")
        everywhere(KModifier.CROSSINLINE, "modifier 'crossinline' is not applicable to 'class'")
        everywhere(INFIX, "modifier 'infix' is not applicable to 'class'")
        everywhere(OPERATOR, "modifier 'operator' is not applicable to 'class'")
    }

    /**
     * **The syntax error.** `` `class`(FUN, "M") `` rendered `public fun class M(...)`, which is not
     * a declaration Kotlin can parse — the sentence is not *not applicable* but *function
     * declaration must have a name*, because `fun` starts a function and the parser never reaches
     * the class.
     */
    @Test
    fun `fun on a class is a syntax error and says so`() {
        everywhere(FUN, "function declaration must have a name")
        // …and the one form that does take it.
        assertTrue("public fun interface F" in render { `interface`(FUN, "F") { `fun`(ABSTRACT, "g", returns = INT) { } } })
    }

    /** The control rows: every modifier a class does carry, at the depth each of them is valid at. */
    @Test
    fun `a class still carries every modifier Kotlin applies to one`() {
        val source = render {
            `class`(PUBLIC, "A") { }
            `class`(PRIVATE, "B") { }
            `class`(INTERNAL, "C") { }
            `class`(FINAL, "D") { }
            `class`(OPEN, "E") { }
            `class`(ABSTRACT, "F") { }
            `class`(SEALED, "G") { }
            `class`(ENUM, "H") { }
            `class`(ANNOTATION, "I") { }
            `class`(DATA, "J", param(VAL, "a", INT)) { _ -> }
            `class`("Holder") {
                `class`(INNER, "N") { }
                `class`(PROTECTED, "O") { }
            }
        }
        assertCompiles(source)
        // `expect`/`actual` and `external` are not in the compile above: the first needs a whole
        // multiplatform project and the second is refused by the JVM alone (D37).
        assertTrue("public expect class X" in render { `class`(EXPECT, "X") { } })
        assertTrue("public actual class Y" in render { `class`(ACTUAL, "Y") { } })
        assertCompilesEverywhereButJvm(render { `class`(EXTERNAL, "Z") { } })
        // `value` and `inline` are the JVM's *value classes without '@JvmInline' annotation are not
        // yet supported* — the caller's annotation to add, and not this DSL's business (D41) — so
        // their control is the two frontends that need no annotation.
        assertCompilesEverywhereButJvm(
            render {
                `class`(VALUE, "K", param(VAL, "a", INT)) { _ -> }
                `class`(INLINE, "L", param(VAL, "b", INT)) { _ -> }
            },
        )
    }

    // --- an interface -------------------------------------------------------------------------

    @Test
    fun `an interface refuses every modifier Kotlin does not apply to one`() {
        val rows = listOf(
            FINAL to "modifier 'final' is not applicable to 'interface'",
            DATA to "modifier 'data' is not applicable to 'interface'",
            ENUM to "modifier 'enum' is not applicable to 'interface'",
            ANNOTATION to "modifier 'annotation' is not applicable to 'interface'",
            VALUE to "modifier 'value' is not applicable to 'interface'",
            INLINE to "modifier 'inline' is not applicable to 'interface'",
            INNER to "modifier 'inner' is not applicable to 'interface'",
            SUSPEND to "modifier 'suspend' is not applicable to 'interface'",
            CONST to "modifier 'const' is not applicable to 'interface'",
        )
        rows.forEach { (modifier, expected) ->
            // At file level too, which is where the *container* rule used to answer for `inner`
            // with the wrong sentence — *not applicable inside 'file'* rather than the one Kotlin
            // prints, which names the interface.
            assertTrue(expected in message { `interface`(modifier, "I") { } }, "$modifier at file")
            assertTrue(
                expected in message { `class`("Outer") { `interface`(modifier, "I") { } } },
                "$modifier in a class",
            )
            assertTrue(
                expected in message {
                    `class`("Outer") { `class`("Inner") { `interface`(modifier, "I") { } } }
                },
                "$modifier nested",
            )
        }
    }

    @Test
    fun `an interface still carries every modifier Kotlin applies to one`() {
        assertCompiles(
            render {
                `interface`(PUBLIC, "A") { }
                `interface`(PRIVATE, "B") { }
                `interface`(INTERNAL, "C") { }
                `interface`(SEALED, "D") { }
                `interface`(OPEN, "E") { }
                `interface`(ABSTRACT, "F") { }
                `interface`(FUN, "G") { `fun`(ABSTRACT, "g", returns = INT) { } }
                `class`("Holder") { `interface`(PROTECTED, "H") { } }
            },
        )
        assertCompilesEverywhereButJvm(render { `interface`(EXTERNAL, "Z") { } })
    }

    // --- an object ----------------------------------------------------------------------------

    @Test
    fun `an object refuses every modifier Kotlin does not apply to one`() {
        val rows = listOf(
            ABSTRACT to "modifier 'abstract' is not applicable to 'standalone object'",
            OPEN to "modifier 'open' is not applicable to 'standalone object'",
            SEALED to "modifier 'sealed' is not applicable to 'standalone object'",
            VALUE to "modifier 'value' is not applicable to 'standalone object'",
            INLINE to "modifier 'inline' is not applicable to 'standalone object'",
            ENUM to "modifier 'enum' is not applicable to 'standalone object'",
            INNER to "modifier 'inner' is not applicable to 'standalone object'",
            SUSPEND to "modifier 'suspend' is not applicable to 'standalone object'",
            FUN to "function declaration must have a name",
        )
        rows.forEach { (modifier, expected) ->
            assertTrue(expected in message { `object`(modifier, "O") { } }, "$modifier at file")
            assertTrue(
                expected in message { `class`("Outer") { `object`(modifier, "O") { } } },
                "$modifier in a class",
            )
            assertTrue(
                expected in message {
                    `class`("Outer") { `class`("Inner") { `object`(modifier, "O") { } } }
                },
                "$modifier nested",
            )
        }
    }

    @Test
    fun `an object still carries every modifier Kotlin applies to one`() {
        assertCompiles(
            render {
                `object`(PUBLIC, "A") { }
                `object`(PRIVATE, "B") { }
                `object`(INTERNAL, "C") { }
                `object`(FINAL, "D") { }
                `object`(DATA, "E") { }
                `class`("Holder") {
                    `object`(PROTECTED, "F") { }
                    `object`(COMPANION, "G") { }
                }
            },
        )
    }

    // --- a function ---------------------------------------------------------------------------

    @Test
    fun `a function refuses every modifier Kotlin does not apply to one`() {
        val rows = listOf(
            DATA to "modifier 'data' is not applicable to",
            ENUM to "modifier 'enum' is not applicable to",
            ANNOTATION to "modifier 'annotation' is not applicable to",
            VALUE to "modifier 'value' is not applicable to",
            SEALED to "modifier 'sealed' is not applicable to",
            CONST to "modifier 'const' is not applicable to",
            LATEINIT to "modifier 'lateinit' is not applicable to",
            VARARG to "modifier 'vararg' is not applicable to",
            INNER to "modifier 'inner' is not applicable to",
            COMPANION to "modifier 'companion' is not applicable to",
            FUN to "function declaration must have a name",
        )
        rows.forEach { (modifier, expected) ->
            assertTrue(
                "$expected 'top level function'" in message { `fun`(modifier, "f") { } } ||
                    expected in message { `fun`(modifier, "f") { } },
                "$modifier at file",
            )
            val inClass = message { `class`("C") { `fun`(modifier, "f") { } } }
            assertTrue(expected in inClass, "$modifier in a class: $inClass")
            // The noun Kotlin prints changes with the container, and the message follows it.
            if (modifier != FUN) assertTrue("'member function'" in inClass, inClass)
            // …and the detached builder, which has no container and still answers.
            val detached = assertFailsWith<IllegalStateException> {
                funSpec(modifier.toModifiers(), name = "f") { }
            }.message!!
            assertTrue(expected in detached, "$modifier detached: $detached")
        }
    }

    @Test
    fun `a function still carries every modifier Kotlin applies to one`() {
        assertCompiles(
            render {
                `fun`(PUBLIC, "a") { }
                `fun`(PRIVATE, "b") { }
                `fun`(INTERNAL, "c") { }
                `fun`(SUSPEND, "d") { }
                `fun`(TAILREC, "e") { }
                `fun`(INLINE, "g") { }
                `class`(OPEN, "C") {
                    `fun`(OPEN, "h") { }
                    `fun`(FINAL, "i") { }
                    `fun`(PROTECTED, "j") { }
                }
                `class`(ABSTRACT, "A") { `fun`(ABSTRACT, "k", returns = INT) { } }
            },
        )
        assertTrue("public expect fun m()" in render { `fun`(EXPECT, "m") { } })
        assertCompilesEverywhereButJvm(render { `fun`(EXTERNAL, "n", returns = INT) { } })
    }

    /**
     * **The three modifiers this rule deliberately does not carry.** `override`, `infix` and
     * `operator` all produce invalid renders in D42's matrix, and none of them is decidable from the
     * modifier and the form: whether a function overrides anything is the supertypes' answer, and
     * `infix`/`operator` are the signature's. A guard on any of the three would refuse the Kotlin
     * below, which compiles.
     */
    @Test
    fun `override, infix and operator are left to the frontend`() {
        assertCompiles(
            render {
                `interface`("Base") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`("Impl") {
                    superinterface(ClassName("com.example", "Base"))
                    `fun`(OVERRIDE, "f", returns = INT) { ret(1.lit) }
                    `fun`(INFIX, "with", param("other", INT), returns = INT) { ret(1.lit) }
                    `fun`(OPERATOR, "plus", param("other", INT), returns = INT) { ret(1.lit) }
                }
            },
        )
    }

    // --- a property ---------------------------------------------------------------------------

    /**
     * Nineteen of the thirty-two modifiers reached **KotlinPoet's own `IllegalArgumentException`**
     * here — Global Constraint 26's forbidden type, naming neither construct nor property. 152 cells
     * of the matrix, all from one row.
     */
    @Test
    fun `a property refuses every modifier Kotlin does not apply to one`() {
        val rows = listOf(SUSPEND, SEALED, ENUM, ANNOTATION, VALUE, DATA, INNER, COMPANION, VARARG, TAILREC)
        rows.forEach { modifier ->
            val word = modifier.name.lowercase()
            listOf<Pair<String, FileScope.() -> Unit>>(
                "file" to { `val`(modifier, "p", INT, 1.lit) },
                "class" to { `class`("C") { `val`(modifier, "p", INT, 1.lit) } },
                "var" to { `var`(modifier, "p", INT, 1.lit) },
            ).forEach { (label, body) ->
                val m = message(body)
                assertTrue("modifier '$word' is not applicable to" in m, "$modifier at $label: $m")
            }
            // …and the detached builder.
            val detached = assertFailsWith<IllegalStateException> {
                propertySpec(modifier.toModifiers(), name = "p", type = INT, init = 1.lit)
            }.message!!
            assertTrue("modifier '$word' is not applicable to" in detached, detached)
        }
    }

    /**
     * `inline` on a property is the one row that is a **render gap** rather than a language rule:
     * `inline val p: Int get() = 1` compiles, and KotlinPoet refuses the modifier outright with a
     * message of its own. The refusal says which it is.
     */
    @Test
    fun `inline on a property names the render gap`() {
        val m = message { `val`(INLINE, "p", INT, getter = { ret(1.lit) }) }
        assertTrue("KotlinPoet doesn't allow setting the inline modifier on properties" in m, m)
        assertTrue("valid Kotlin" in m, m)
        // The neighbour: the same property without the modifier renders and compiles.
        assertCompiles(render { `val`("p", INT, getter = { ret(1.lit) }) })
    }

    /** `const` is a `val`'s modifier, in every container. */
    @Test
    fun `const on a var is refused and on a val still renders`() {
        val m = message { `var`(CONST, "p", INT, 1.lit) }
        assertTrue("modifier 'const' is not applicable to 'vars'" in m, m)
        assertCompiles(render { `val`(CONST, "p", INT, 1.lit) })
    }

    @Test
    fun `a property still carries every modifier Kotlin applies to one`() {
        assertCompiles(
            render {
                `val`(PUBLIC, "a", INT, 1.lit)
                `val`(PRIVATE, "b", INT, 1.lit)
                `val`(INTERNAL, "c", INT, 1.lit)
                `val`(CONST, "d", INT, 1.lit)
                `class`(OPEN, "C") {
                    `val`(OPEN, "e", INT, 1.lit)
                    `val`(FINAL, "f", INT, 1.lit)
                    `val`(PROTECTED, "g", INT, 1.lit)
                    `var`(LATEINIT, "h", STRING)
                }
                `class`(ABSTRACT, "A") { `val`(ABSTRACT, "i", INT) }
            },
        )
        assertCompilesEverywhereButJvm(render { `val`(EXTERNAL, "j", INT) })
    }

    // --- a secondary constructor ----------------------------------------------------------------

    /**
     * The narrowest form on this axis: a visibility and `actual`, and nothing else. Every other
     * modifier rendered, and two of them — `abstract` — reached KotlinPoet's own
     * `IllegalArgumentException` instead.
     */
    @Test
    fun `a secondary constructor refuses every modifier but a visibility and actual`() {
        val rows = listOf(
            OPEN to "modifier 'open' is not applicable to 'constructor'",
            FINAL to "modifier 'final' is not applicable to 'constructor'",
            ABSTRACT to "modifier 'abstract' is not applicable to 'constructor'",
            SEALED to "modifier 'sealed' is not applicable to 'constructor'",
            EXPECT to "modifier 'expect' is not applicable to 'constructor'",
            EXTERNAL to "modifier 'external' is not applicable to 'constructor'",
            SUSPEND to "modifier 'suspend' is not applicable to 'constructor'",
            INLINE to "modifier 'inline' is not applicable to 'constructor'",
            DATA to "modifier 'data' is not applicable to 'constructor'",
            ENUM to "syntax error: 'class' keyword is expected after 'enum'",
            FUN to "function 'constructor' without a body must be abstract",
        )
        rows.forEach { (modifier, expected) ->
            val inClass = message { `class`("C") { `constructor`(modifier, param("q", INT)) { } } }
            assertTrue(expected in inClass, "$modifier in a class: $inClass")
            assertTrue(
                expected in message {
                    `class`("O") { `class`("C") { `constructor`(modifier, param("q", INT)) { } } }
                },
                "$modifier nested",
            )
        }
    }

    /**
     * **The one row whose sentence depended on a shape the matrix never built.** KotlinPoet omits a
     * constructor's *empty* body (`canBodyBeOmitted`), so the probe — which used empty bodies — only
     * ever saw `public fun constructor(q: Int)`, which is *function 'constructor' without a body must
     * be abstract*. Give the same construct a body and it is a different shape entirely. Measured,
     * one file per row, all three frontends agreeing:
     *
     *     class C { fun constructor(q: Int) { println(q) } }   CLEAN
     *     class C { fun constructor(q: Int) }                  function 'constructor' without a body
     *                                                          must be abstract.
     *     class C(val a: Int) {                                function 'constructor' without a body
     *       fun constructor(q: Int) : this(1) { println(q) }   must be abstract. + six syntax errors
     *     }
     *
     * The refusal stands — `` `constructor` `` means a secondary constructor, and D25's delegation
     * call is the third row above — but it no longer asserts one sentence of every shape. It says
     * which shape draws which, and names the spelling that works.
     */
    @Test
    fun `fun on a constructor declines the spelling and names the one that works`() {
        val m = message { `class`("C") { `constructor`(FUN, param("q", INT)) { ret() } } }
        assertTrue("not a constructor at all but a function named `constructor`" in m, m)
        // The diagnostic is attributed to the shape that draws it, not asserted of the shape at hand.
        assertTrue("with an empty body" in m, m)
        assertTrue("function 'constructor' without a body must be abstract" in m, m)
        assertTrue("delegation call" in m, m)
        // …and the remedy names a spelling this DSL really has. The nearest valid neighbour: the
        // function the caller would get, written as one.
        assertTrue("`fun`(\"constructor\"" in m, m)
        assertCompiles(render { `class`("C") { `fun`("constructor", param("q", INT)) { _ -> ret() } } })
    }

    @Test
    fun `a secondary constructor still carries a visibility`() {
        assertCompiles(
            render {
                `class`("A") { `constructor`(PUBLIC, param("q", INT)) { } }
                `class`("B") { `constructor`(PRIVATE, param("q", INT)) { } }
                `class`("C") { `constructor`(INTERNAL, param("q", INT)) { } }
                `class`(OPEN, "D") { `constructor`(PROTECTED, param("q", INT)) { } }
            },
        )
        assertTrue(
            "public actual constructor" in render {
                `class`(ACTUAL, "E") { `constructor`(ACTUAL, param("q", INT)) { } }
            },
        )
    }

    /**
     * The remedy names the forms the modifier *is* applicable to, and it is built from the same
     * table the refusal is, so the two cannot drift apart.
     */
    @Test
    fun `the remedy names the forms that do take the modifier`() {
        val m = message { `class`(SUSPEND, "M") { } }
        assertTrue("Drop SUSPEND, which Kotlin accepts on a top level function." in m, m)
        val none = message { `class`(VARARG, "M") { } }
        assertTrue("Drop VARARG." in none, none)
    }

    // --- the container half ---------------------------------------------------------------------

    /**
     * `protected` names a visibility to subclasses, so it needs a container that can have one.
     * Measured, all three frontends identical: a file, a standalone object, a companion object and
     * an interface all refuse it, and a class body — including `enum`, `sealed`, `abstract`, `data`
     * and `value` — takes it.
     */
    @Test
    fun `protected needs a class around it`() {
        listOf<Pair<String, FileScope.() -> Unit>>(
            "a class at file level" to { `class`(PROTECTED, "M") { } },
            "an interface at file level" to { `interface`(PROTECTED, "I") { } },
            "an object at file level" to { `object`(PROTECTED, "O") { } },
            "a class in an object" to { `object`("O") { `class`(PROTECTED, "M") { } } },
            "a class in an interface" to { `interface`("I") { `class`(PROTECTED, "M") { } } },
            "a class in a companion object" to {
                `class`("C") { companionObject { `class`(PROTECTED, "M") { } } }
            },
            "a function at file level" to { `fun`(PROTECTED, "f") { } },
            "a function in an object" to { `object`("O") { `fun`(PROTECTED, "f") { } } },
            "a property at file level" to { `val`(PROTECTED, "p", INT, 1.lit) },
            "a property in an interface" to { `interface`("I") { `val`(PROTECTED, "p", INT) } },
            "a property in an object" to { `object`("O") { `val`(PROTECTED, "p", INT, 1.lit) } },
        ).forEach { (label, body) ->
            val m = message(body)
            assertTrue("modifier 'protected' is not applicable" in m, "$label: $m")
        }
    }

    /** The control rows: every container that does take a `protected` member, and both depths. */
    @Test
    fun `protected still renders in a class body`() {
        assertCompiles(
            render {
                `class`(OPEN, "C") {
                    `class`(PROTECTED, "M") { }
                    `interface`(PROTECTED, "I") { }
                    `object`(PROTECTED, "O") { }
                    `val`(PROTECTED, "p", INT, 1.lit)
                    `fun`(PROTECTED, "f") { }
                    `constructor`(PROTECTED, param("q", INT)) { }
                }
                `class`(ABSTRACT, "A") { `val`(PROTECTED, "p", INT, 1.lit) }
                `class`(SEALED, "S") { `fun`(PROTECTED, "f") { } }
                `class`(ENUM, "E") { `fun`(PROTECTED, "f") { } }
                `class`("Outer") { `class`(OPEN, "Inner") { `val`(PROTECTED, "p", INT, 1.lit) } }
            },
        )
        // …and the detached builders, which have no container and must not answer for one.
        assertTrue("protected" in typeSpec(PROTECTED.toModifiers(), name = "M") { }.toString())
        assertTrue("protected" in funSpec(PROTECTED.toModifiers(), name = "f") { }.toString())
        assertTrue(
            "protected" in propertySpec(PROTECTED.toModifiers(), name = "p", type = INT, init = 1.lit)
                .toString(),
        )
    }

    /**
     * `final`, `open` and `override` are a **member's** modifiers and a top-level declaration is in
     * no hierarchy. Keyed on the file rather than on "a class", because `object O { final val x = 1 }`
     * is clean — the reading `protected` deliberately does not take.
     */
    @Test
    fun `final, open and override need a type around them`() {
        listOf(FINAL, OPEN, OVERRIDE).forEach { modifier ->
            val word = modifier.name.lowercase()
            val fn = message { `fun`(modifier, "f") { } }
            assertTrue("modifier '$word' is not applicable to 'top level function'" in fn, fn)
            val prop = message { `val`(modifier, "p", INT, 1.lit) }
            assertTrue(
                "modifier '$word' is not applicable to 'top level property with backing field'" in prop,
                prop,
            )
        }
        // …and a classifier is untouched: `final class M` and `open class M` are ordinary Kotlin.
        assertCompiles(render { `class`(FINAL, "M") { }; `class`(OPEN, "N") { } })
        // …as is any type body, including an object's.
        assertCompiles(
            render {
                `object`("O") { `val`(FINAL, "x", INT, 1.lit) }
                `class`(OPEN, "C") { `fun`(OPEN, "f") { }; `val`(FINAL, "y", INT, 1.lit) }
            },
        )
        // …and the detached builders answer for no container.
        assertTrue("final" in funSpec(FINAL.toModifiers(), name = "f") { }.toString())
    }

    /** A `const val` belongs to the file or to an object, never to an instance. */
    @Test
    fun `const needs a file or an object around it`() {
        val m = message { `class`("C") { `val`(CONST, "x", INT, 1.lit) } }
        assertTrue("const 'val' is only allowed on top level, in named objects" in m, m)
        assertCompiles(
            render {
                `val`(CONST, "a", INT, 1.lit)
                `object`("O") { `val`(CONST, "b", INT, 1.lit) }
                `class`("C") { companionObject { `val`(CONST, "c", INT, 1.lit) } }
            },
        )
        assertTrue(
            "const" in propertySpec(CONST.toModifiers(), name = "d", type = INT, init = 1.lit).toString(),
        )
    }

    /** `lateinit` promises the value arrives later, so a property that has one already is a contradiction. */
    @Test
    fun `lateinit with a value is refused`() {
        val m = message { `class`("C") { `var`(LATEINIT, "p", STRING, "x".lit) } }
        assertTrue("'lateinit' modifier is not allowed on properties with initializer" in m, m)
        assertCompiles(render { `class`("C") { `var`(LATEINIT, "p", STRING) } })
    }
}
