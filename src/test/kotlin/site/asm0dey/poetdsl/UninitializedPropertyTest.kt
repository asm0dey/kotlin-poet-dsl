package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.EXPECT
import com.squareup.kotlinpoet.KModifier.LATEINIT
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * E2b item 5. `` `val`("x", INT) `` with no initializer, delegate or getter rendered
 * `public val x: Int`, which kotlinc answers with `Property must be initialized.` — and had done
 * since Task 12.
 *
 * A sound check needs what E2a's slot guards did not have: **modifier and container awareness**.
 * Every exempt case below was measured with kctfork rather than taken from a list.
 */
class UninitializedPropertyTest {
    private val valMessage =
        "`val`: 'x' has no initializer, no delegate and no getter, so it renders `val x: T` — " +
            "\"Property must be initialized.\" Pass init = …, by = … or a getter, or declare it " +
            "ABSTRACT, LATEINIT or EXPECT. A property in an interface body needs none of these, " +
            "and this check does not fire there."

    @Test
    fun `a file-level property with no value is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `val`("x", INT) }
        }
        assertEquals(valMessage, e.message)
    }

    @Test
    fun `a class member with no value is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `class`("C") { `var`("x", INT) } }
        }
        assertEquals(valMessage.replace("`val`", "`var`").replace("`val x", "`var x"), e.message)
    }

    @Test
    fun `an object and a companion object are rejected too`() {
        listOf<FileScope.() -> Unit>(
            { `object`("O") { `val`("x", INT) } },
            { `class`("C") { companionObject { `val`("x", INT) } } },
            { `class`("E") { `class`("N") { `val`("x", INT) } } },
        ).forEachIndexed { index, position ->
            val e = assertFailsWith<IllegalStateException>("position $index") {
                file("com.example", "A", body = position)
            }
            assertEquals(valMessage, e.message, "position $index")
        }
    }

    /** The container exemption, and the only one: measured OK for both `val` and `var`. */
    @Test
    fun `an interface member needs no value`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public interface I {
              public val x: Int

              public var y: Int
            }

            """.trimIndent(),
            file("com.example", "A") {
                `interface`("I") {
                    `val`("x", INT)
                    `var`("y", INT)
                }
            }.toString(),
        )
    }

    /** The three modifier exemptions. */
    @Test
    fun `abstract, lateinit and expect need no value`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int
            import kotlin.String

            public expect val top: Int

            public expect class E {
              public val e: Int
            }

            public abstract class C {
              public abstract val a: Int

              public lateinit var b: String
            }

            """.trimIndent(),
            file("com.example", "A") {
                // Two different exemptions that both say `expect`, and they are not the same one:
                // the members of an `expect class` do not each carry the modifier, so the container
                // has to answer for them — while a top-level `expect val` is a `FileScope`, where
                // only the property's own modifier can.
                `val`(EXPECT, "top", INT)
                `class`(EXPECT, "E") { `val`("e", INT) }
                `class`(ABSTRACT, "C") {
                    `val`(ABSTRACT, "a", INT)
                    `var`(LATEINIT, "b", STRING)
                }
            }.toString(),
        )
    }

    /** The other side of the boundary: everything that already carried a value still renders. */
    @Test
    fun `a property with an initializer, a delegate or a getter is untouched`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public val a: Int = 1

            public val b: Int by lazy()

            public val c: Int
              get() = 3

            """.trimIndent(),
            file("com.example", "A") {
                `val`("a", INT, init = 1.lit)
                `val`("b", INT, by = expression("lazy()"))
                `val`("c", INT) { ret(3.lit) }
            }.toString(),
        )
    }

    /**
     * The detached builder cannot see its container, so it does not run this check — the one place
     * the shape still renders. Pinned rather than left implicit, because it is the difference
     * between "we forgot" and "we cannot know".
     */
    @Test
    fun `propertySpec still renders a bare property, since it cannot see its container`() {
        assertEquals("val x: kotlin.Int\n", propertySpec(name = "x", type = INT).toString())
        assertEquals(
            "abstract val x: kotlin.Int\n",
            propertySpec(ABSTRACT.toModifiers(), name = "x", type = INT).toString(),
        )
    }

    /** A local binding is definite-assignment territory and keeps its own, older rule. */
    @Test
    fun `a local val with a type but no value is still allowed`() {
        assertEquals(
            "val x: kotlin.Int\n",
            renderBlock { `val`("x", INT) },
        )
    }
}
