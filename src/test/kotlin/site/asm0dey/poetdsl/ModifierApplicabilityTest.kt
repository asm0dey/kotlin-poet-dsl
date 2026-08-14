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
}
