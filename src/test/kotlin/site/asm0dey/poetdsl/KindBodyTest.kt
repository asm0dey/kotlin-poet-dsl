package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ANNOTATION
import com.squareup.kotlinpoet.KModifier.DATA
import com.squareup.kotlinpoet.KModifier.ENUM
import com.squareup.kotlinpoet.KModifier.INNER
import com.squareup.kotlinpoet.KModifier.SEALED
import com.squareup.kotlinpoet.KModifier.VALUE
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

    private companion object {
        @Suppress("unused")
        val unusedRefs: List<Any> = emptyList()
    }
}
