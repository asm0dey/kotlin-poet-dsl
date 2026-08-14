package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.ANNOTATION
import com.squareup.kotlinpoet.KModifier.DATA
import com.squareup.kotlinpoet.KModifier.ENUM
import com.squareup.kotlinpoet.KModifier.EXPECT
import com.squareup.kotlinpoet.KModifier.EXTERNAL
import com.squareup.kotlinpoet.KModifier.INNER
import com.squareup.kotlinpoet.KModifier.OPEN
import com.squareup.kotlinpoet.KModifier.SEALED
import com.squareup.kotlinpoet.KModifier.VALUE
import com.squareup.kotlinpoet.UNIT
import site.asm0dey.poetdsl.ParamKind.VAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E2c — the classifier-kind × body-member matrix (**D41**).
 *
 * One question: *what does a classifier's own kind forbid in its own body?* Every refusal below has
 * its **nearest valid neighbour** in the same test, at every depth the guard can fire, because a
 * guard keyed on a kind inverts one level down exactly as a guard keyed on a container does — that
 * is the standing method, and the matrix that produced this file measured every control on
 * `kotlinc`, `kotlinc-js` and `kotlinc-wasm` 2.4.10.
 */
class KindBodyTest {

    private val base = ClassName("com.example", "Base")
    private val iface = ClassName("com.example", "Iface")

    private fun render(body: FileScope.() -> Unit): String = file("com.example", "P", body = body).toString()

    private fun message(body: FileScope.() -> Unit): String =
        assertFailsWith<IllegalStateException> { render(body) }.message!!

    // --- `inner` needs a class around it -------------------------------------------------------

    @Test
    fun `an inner class at file level is refused`() {
        val m = message { `class`(INNER, "I") { } }
        assertTrue("modifier 'inner' is not applicable inside 'file'" in m, m)
    }

    @Test
    fun `an inner class is refused in an object, an interface and a companion object`() {
        assertTrue(
            "'standalone object'" in message { `object`("O") { `class`(INNER, "I") { } } },
        )
        assertTrue(
            "'interface'" in message { `interface`("O") { `class`(INNER, "I") { } } },
        )
        assertTrue(
            "'companion object'" in message {
                `class`("O") { companionObject { `class`(INNER, "I") { } } }
            },
        )
    }

    @Test
    fun `an inner class is refused in an annotation class and in a value class`() {
        assertTrue(
            "'annotation class'" in message { `class`(ANNOTATION, "A") { `class`(INNER, "I") { } } },
        )
        assertTrue(
            "value class cannot have inner classes" in message {
                `class`(VALUE, "V", param(VAL, "a", INT)) { `class`(INNER, "I") { } }
            },
        )
    }

    /**
     * The control rows: every container that *does* accept an `inner` class, measured clean on all
     * three frontends, at the depth each of them occurs at.
     */
    @Test
    fun `an inner class still renders in every container that accepts one`() {
        assertTrue("inner class N" in render { `class`("O") { `class`(INNER, "N") { } } })
        assertTrue(
            "inner class N" in render { `class`(DATA, "O", param(VAL, "a", INT)) { `class`(INNER, "N") { } } },
        )
        assertTrue("inner class N" in render { `class`(SEALED, "O") { `class`(INNER, "N") { } } })
        assertTrue("inner class N" in render { `class`(ENUM, "O") { `class`(INNER, "N") { } } })
        // Depth: the immediate container is what decides, so a class nested in an object is fine.
        assertTrue(
            "inner class N" in render { `object`("O") { `class`("M") { `class`(INNER, "N") { } } } },
        )
        assertTrue(
            "inner class N" in render { `interface`("O") { `class`("M") { `class`(INNER, "N") { } } } },
        )
    }

    // --- an `inner class` holds no nested classifier ------------------------------------------

    @Test
    fun `a nested classifier inside an inner class is refused`() {
        assertTrue(
            "'Class' is prohibited here" in message {
                `class`("O") { `class`(INNER, "M") { `class`("N") { } } }
            },
        )
        assertTrue(
            "'Object' is prohibited here" in message {
                `class`("O") { `class`(INNER, "M") { `object`("N") { } } }
            },
        )
        assertTrue(
            "'Interface' is prohibited here" in message {
                `class`("O") { `class`(INNER, "M") { `interface`("N") { } } }
            },
        )
        assertTrue(
            "'Companion object' is prohibited here" in message {
                `class`("O") { `class`(INNER, "M") { companionObject { } } }
            },
        )
    }

    /** The control rows: what an `inner class` *does* still hold. */
    @Test
    fun `an inner class still holds members and another inner class`() {
        val nested = render { `class`("O") { `class`(INNER, "M") { `class`(INNER, "N") { } } } }
        assertTrue("inner class N" in nested, nested)
        assertTrue("public val p: Int = 1" in render {
            `class`("O") { `class`(INNER, "M") { `val`("p", INT, init = 1.lit) } }
        })
        assertTrue("public fun f(): Int = 1" in render {
            `class`("O") { `class`(INNER, "M") { `fun`("f", returns = INT) { ret(1.lit) } } }
        })
        // …and a plain nested class one level further out is untouched: the rule reads the
        // immediate container only.
        assertTrue("public class N" in render {
            `class`("O") { `class`("M") { `class`("N") { } } }
        })
    }

    @Test
    fun `the inner rules go through the frontend`() {
        assertCompiles(
            render {
                `class`("Outer") {
                    `class`(INNER, "M") { `class`(INNER, "N") { `val`("p", INT, init = 1.lit) } }
                }
                `class`(ENUM, "E") { `class`(INNER, "N") { } }
                `class`(SEALED, "S") { `class`(INNER, "N") { } }
                `object`("O") { `class`("M") { `class`(INNER, "N") { } } }
            },
        )
    }

    // --- an `annotation class` has no members and no supertypes --------------------------------

    @Test
    fun `every member of an annotation class is refused`() {
        val shapes: List<Pair<String, TypeScope.() -> Unit>> = listOf(
            "val" to { `val`("p", INT, init = 1.lit) },
            "var" to { `var`("p", INT, init = 1.lit) },
            "val with a getter" to { `val`("p", INT, getter = { ret(1.lit) }) },
            "fun" to { `fun`("f", returns = INT) { ret(1.lit) } },
            "constructor" to { `constructor`(param("q", INT)) { } },
            "init" to { `init` { } },
        )
        shapes.forEach { (label, member) ->
            val m = message { `class`(ANNOTATION, "A") { member() } }
            assertTrue("members are prohibited in annotation classes" in m, "$label: $m")
        }
    }

    @Test
    fun `an annotation class takes neither a superclass nor a superinterface`() {
        assertTrue(
            "annotation class cannot have supertypes" in
                message { `class`(ANNOTATION, "A") { superinterface(iface) } },
        )
        assertTrue(
            "annotation class cannot have supertypes" in
                message { `class`(ANNOTATION, "A") { superclass(base) } },
        )
    }

    /** The control rows: an annotation class keeps its parameters, its nested types, its companion. */
    @Test
    fun `an annotation class still holds parameters, nested types and a companion object`() {
        val rendered = render {
            `class`(ANNOTATION, "A", param(VAL, "x", INT)) {
                `class`("Inner") { `val`("p", INT, init = 1.lit) }
                companionObject { `val`("q", INT, init = 2.lit) }
            }
        }
        assertTrue("public annotation class A(" in rendered, rendered)
        assertTrue("public val x: Int" in rendered, rendered)
        assertTrue("public class Inner" in rendered, rendered)
        assertTrue("public companion object" in rendered, rendered)
        assertCompiles(rendered)
    }

    /** …and the rule is the *kind's*, so it fires at every depth and nowhere else. */
    @Test
    fun `the annotation rule fires at every depth and leaves neighbouring kinds alone`() {
        assertTrue(
            "members are prohibited" in message {
                `class`("O") { `class`(ANNOTATION, "A") { `val`("p", INT, init = 1.lit) } }
            },
        )
        assertTrue(
            "members are prohibited" in message {
                `object`("O") { `class`(ANNOTATION, "A") { `val`("p", INT, init = 1.lit) } }
            },
        )
        // The neighbour one level in: a class nested inside an annotation class is an ordinary
        // class and holds whatever a class holds.
        assertCompiles(
            render {
                `class`(ANNOTATION, "A") {
                    `class`("M") { `fun`("f", returns = INT) { ret(1.lit) } }
                }
            },
        )
    }

    // --- an abstract function needs a container that can hold one -----------------------------

    @Test
    fun `an abstract function is refused where Kotlin has nothing to override it`() {
        val containers: List<Pair<String, FileScope.() -> Unit>> = listOf(
            "class" to { `class`("C") { `fun`(ABSTRACT, "f", returns = INT) { } } },
            "data class" to {
                `class`(DATA, "C", param(VAL, "a", INT)) { `fun`(ABSTRACT, "f", returns = INT) { } }
            },
            "open class" to { `class`(OPEN, "C") { `fun`(ABSTRACT, "f", returns = INT) { } } },
            "object" to { `object`("O") { `fun`(ABSTRACT, "f", returns = INT) { } } },
            "companion object" to {
                `class`("C") { companionObject { `fun`(ABSTRACT, "f", returns = INT) { } } }
            },
            "file" to { `fun`(ABSTRACT, "f", returns = INT) { } },
            "expect class" to {
                `class`(EXPECT, "E") { `fun`(ABSTRACT, "f", returns = INT) { } }
            },
        )
        containers.forEach { (label, body) ->
            val m = message(body)
            assertTrue("abstract function 'f' in non-abstract class" in m, "$label: $m")
        }
    }

    /** The control rows: the four containers that do hold an abstract member. */
    @Test
    fun `an abstract function still renders in an interface, an abstract, a sealed and an enum class`() {
        assertCompiles(
            render {
                `interface`("I") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`(ABSTRACT, "A") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`(SEALED, "S") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`(ENUM, "E") { `fun`(ABSTRACT, "f", returns = INT) { } }
                // Depth: the immediate container decides, so an abstract class nested in an
                // object still holds one.
                `object`("O") { `class`(ABSTRACT, "N") { `fun`(ABSTRACT, "f", returns = INT) { } } }
            },
        )
        // `expect abstract class X { abstract fun f(): Int }` is clean on all three frontends and
        // is the control for the `expect class` row above — asserted on the render rather than
        // compiled here, since a single-file `expect` has no `actual` to pair with.
        val expected = render { `class`(EXPECT + ABSTRACT, "X") { `fun`(ABSTRACT, "f", returns = INT) { } } }
        assertTrue("public expect abstract class X" in expected, expected)
        assertTrue("public abstract fun f(): Int" in expected, expected)
    }

    // --- a function with a declared return type and an empty body -----------------------------

    @Test
    fun `a function with a return type and no body is refused in every container`() {
        val containers: List<Pair<String, FileScope.() -> Unit>> = listOf(
            "file" to { `fun`("f", returns = INT) { } },
            "class" to { `class`("C") { `fun`("f", returns = INT) { } } },
            "interface" to { `interface`("I") { `fun`("f", returns = INT) { } } },
            "object" to { `object`("O") { `fun`("f", returns = INT) { } } },
            "enum class" to { `class`(ENUM, "E") { `fun`("f", returns = INT) { } } },
            "abstract class" to { `class`(ABSTRACT, "A") { `fun`("f", returns = INT) { } } },
            "companion object" to { `class`("C") { companionObject { `fun`("f", returns = INT) { } } } },
            "nested" to { `class`("C") { `class`("N") { `fun`("f", returns = INT) { } } } },
        )
        containers.forEach { (label, body) ->
            val m = message(body)
            assertTrue("missing return statement" in m, "$label: $m")
        }
    }

    /**
     * The control rows: every shape whose body KotlinPoet **omits**, so that what renders is a
     * signature and not an empty block. Each measured clean on all three frontends (the two
     * `external` rows on Kotlin/JS and Kotlin/Wasm — D37's platform rule).
     */
    @Test
    fun `a signature still renders wherever KotlinPoet omits the body`() {
        assertCompiles(
            render {
                `fun`("unitExplicit", returns = UNIT) { }
                `fun`("unitInferred") { }
                `interface`("I") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`(ABSTRACT, "A") { `fun`(ABSTRACT, "f", returns = INT) { } }
                `class`("C") { `constructor`(param("q", INT)) { } }
            },
        )
        // `expect`, at the depth KotlinPoet suppresses it: directly, and one level down, where the
        // *container* is the only thing that says so.
        val expect = render {
            `class`(EXPECT, "E") {
                `fun`("f", returns = INT) { }
                `class`("N") { `fun`("g", returns = INT) { } }
            }
        }
        assertTrue("public fun f(): Int\n" in expect, expect)
        assertTrue("public fun g(): Int\n" in expect, expect)
        assertTrue("public expect fun h(): Int" in render { `fun`(EXPECT, "h", returns = INT) { } })
        // …and `external`, which is the same fact on the other keyword and which KotlinPoet threads
        // down to every depth (`modifiers + implicitModifiers`, TypeSpec.kt:348). All four are
        // clean on Kotlin/JS and Kotlin/Wasm; the JVM refuses `external` on a class at all.
        val external = render {
            `fun`(EXTERNAL, "top", returns = INT) { }
            `class`(EXTERNAL, "X") {
                `fun`("f", returns = INT) { }
                `class`("N") { `fun`("g", returns = INT) { } }
                `object`("O") { `fun`("h", returns = INT) { } }
            }
        }
        assertTrue("public external fun top(): Int" in external, external)
        assertTrue("public fun f(): Int\n" in external, external)
        assertTrue("public fun g(): Int\n" in external, external)
        assertTrue("public fun h(): Int\n" in external, external)
    }

    private companion object {
        @Suppress("unused")
        val unusedRefs: List<Any> = emptyList()
    }
}
