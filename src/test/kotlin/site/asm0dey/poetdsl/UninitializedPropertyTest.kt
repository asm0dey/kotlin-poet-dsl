package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.ENUM
import com.squareup.kotlinpoet.KModifier.EXPECT
import com.squareup.kotlinpoet.KModifier.LATEINIT
import com.squareup.kotlinpoet.KModifier.SEALED
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * E2b item 5. `` `val`("x", INT) `` with no initializer, delegate or getter rendered
 * `public val x: Int`, which kotlinc answers with `Property must be initialized.` — and had done
 * since Task 12.
 *
 * A sound check needs what E2a's slot guards did not have: **modifier and container awareness**.
 * Every exempt case below was measured with kctfork rather than taken from a list.
 */
class UninitializedPropertyTest {
    /**
     * The half of the message that never varies. What follows it is the remedy list, which is built
     * from what is legal in *this* container — see
     * `the remedy names only the modifiers that work in this container`.
     */
    private fun valMessage(keyword: String = "val", modifiers: String = "") =
        "`$keyword`: 'x' has no initializer, no delegate and no getter, so it renders " +
            "`$keyword x: T` — \"Property must be initialized.\" Pass init = …, by = … or a getter" +
            (if (modifiers.isEmpty()) "" else ", or declare it $modifiers") +
            ". A property in an interface body needs none of these, and this check does not fire " +
            "there."

    @Test
    fun `a file-level property with no value is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `val`("x", INT) }
        }
        assertEquals(valMessage(modifiers = "EXPECT"), e.message)
    }

    @Test
    fun `a class member with no value is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `class`("C") { `var`("x", INT) } }
        }
        assertEquals(valMessage(keyword = "var", modifiers = "LATEINIT"), e.message)
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
            assertEquals(valMessage(), e.message, "position $index")
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

    /**
     * The container exemption reaches every classifier *inside* an `expect class`, not just the one
     * whose builder carries the modifier. A nested class, a companion object and a class nested two
     * levels down are all implicitly `expect` and none of them may carry an initializer — measured
     * in `UninitializedPropertyCompileTest`, not argued. There is no workaround either: writing
     * `expect` on the member is itself rejected.
     */
    @Test
    fun `a class nested in an expect class needs no value, at any depth`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public expect class E {
              public val direct: Int

              public class N {
                public val nested: Int

                public class M {
                  public val deep: Int
                }
              }
            }

            """.trimIndent(),
            file("com.example", "A") {
                `class`(EXPECT, "E") {
                    `val`("direct", INT)
                    `class`("N") {
                        `val`("nested", INT)
                        `class`("M") { `val`("deep", INT) }
                    }
                }
            }.toString(),
        )
    }

    /** The companion object is the second half, and reaches the flag by its own route. */
    @Test
    fun `a companion object of an expect class needs no value`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public expect class E {
              public companion object {
                public val shared: Int
              }
            }

            """.trimIndent(),
            file("com.example", "A") {
                `class`(EXPECT, "E") { companionObject { `val`("shared", INT) } }
            }.toString(),
        )
    }

    /** …and stops at the `expect class`: a sibling declared after it is judged on its own. */
    @Test
    fun `the expect exemption does not leak to a sibling`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") {
                `class`(EXPECT, "E") { `class`("N") { `val`("x", INT) } }
                `class`("Plain") { `class`("N") { `val`("x", INT) } }
            }
        }
        assertEquals(valMessage(), e.message)
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

    /**
     * The remedy sentence used to name `ABSTRACT, LATEINIT or EXPECT` in every container, and two
     * of the three are wrong for a file-level `val` of type `Int`: `abstract val a: Int` at file
     * level is *modifier 'abstract' is not applicable to 'top level property without backing field
     * or delegate'*, and `lateinit val` is *'lateinit' modifier is allowed only on mutable
     * properties* (both measured, kotlinc 2.4.10). Advice that produces a broken build is worse
     * than no advice, so the list is built from what is legal *here*.
     */
    @Test
    fun `the remedy names only the modifiers that work in this container`() {
        fun messageOf(body: FileScope.() -> Unit): String =
            assertFailsWith<IllegalStateException> { file("com.example", "A", body = body) }.message!!

        // A file-level `val`: neither ABSTRACT nor LATEINIT, and EXPECT is the only modifier left.
        assertEquals(
            "`val`: 'x' has no initializer, no delegate and no getter, so it renders `val x: T` — " +
                "\"Property must be initialized.\" Pass init = …, by = … or a getter, or declare " +
                "it EXPECT. A property in an interface body needs none of these, and this check " +
                "does not fire there.",
            messageOf { `val`("x", INT) },
        )
        // A file-level `var` adds LATEINIT, which needs a mutable property.
        assertEquals(
            "`var`: 'x' has no initializer, no delegate and no getter, so it renders `var x: T` — " +
                "\"Property must be initialized.\" Pass init = …, by = … or a getter, or declare " +
                "it LATEINIT or EXPECT. A property in an interface body needs none of these, and " +
                "this check does not fire there.",
            messageOf { `var`("x", STRING) },
        )
        // A member of a concrete class: EXPECT is not applicable to a member property (measured:
        // `class C { expect val a: Int }` is *modifier 'expect' is not applicable to 'member
        // property without backing field or delegate'*), and the class is not abstract.
        assertEquals(
            "`val`: 'x' has no initializer, no delegate and no getter, so it renders `val x: T` — " +
                "\"Property must be initialized.\" Pass init = …, by = … or a getter. A property " +
                "in an interface body needs none of these, and this check does not fire there.",
            messageOf { `class`("C") { `val`("x", INT) } },
        )
        // An abstract class is where ABSTRACT is the answer.
        assertEquals(
            "`val`: 'x' has no initializer, no delegate and no getter, so it renders `val x: T` — " +
                "\"Property must be initialized.\" Pass init = …, by = … or a getter, or declare " +
                "it ABSTRACT. A property in an interface body needs none of these, and this check " +
                "does not fire there.",
            messageOf { `class`(ABSTRACT, "C") { `val`("x", INT) } },
        )
    }

    /**
     * `` `val`(LATEINIT, "x", STRING) `` rendered `public lateinit val x: String` with no complaint,
     * and kotlinc answers *'lateinit' modifier is allowed only on mutable properties.* — the exact
     * shape the old remedy sentence recommended.
     */
    @Test
    fun `lateinit on a val is rejected`() {
        val e = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `val`(LATEINIT, "x", STRING) }
        }
        assertEquals(
            "`val`: 'x' is a `val` and cannot be LATEINIT; Kotlin allows the modifier only on a " +
                "mutable property (\"'lateinit' modifier is allowed only on mutable properties.\"). " +
                "Declare it with `var`, or drop LATEINIT.",
            e.message,
        )
    }

    /** The modifier is untouched on a `var`, initializer or not. */
    @Test
    fun `lateinit on a var still renders`() {
        assertEquals(
            "public lateinit var x: String\n",
            file("com.example", "A") { `var`(LATEINIT, "x", STRING) }.toString()
                .substringAfter("import kotlin.String\n\n"),
        )
    }

    /**
     * Following the *other* half of the old advice produced KotlinPoet's own
     * `IllegalArgumentException: non-abstract type C cannot declare abstract property x` — an
     * exception type Global Constraint 26 forbids, with a message naming neither construct. For an
     * anonymous companion object it read `non-abstract type null cannot declare abstract property x`.
     */
    @Test
    fun `abstract in a container that cannot hold it is rejected`() {
        val message =
            "`val`: 'x' is ABSTRACT, which Kotlin allows only in an interface or in an ABSTRACT, " +
                "SEALED or ENUM class — not at file level, not in an object or a companion object, " +
                "and not in a class that is none of those. Declare the container ABSTRACT, or give " +
                "'x' a value."
        listOf<FileScope.() -> Unit>(
            { `val`(ABSTRACT, "x", INT) },
            { `class`("C") { `val`(ABSTRACT, "x", INT) } },
            // With an initializer too: KotlinPoet's `require` never looked at the value either.
            { `class`("C") { `val`(ABSTRACT, "x", INT, init = 1.lit) } },
            { `object`("O") { `val`(ABSTRACT, "x", INT) } },
            { `class`("C") { companionObject { `val`(ABSTRACT, "x", INT) } } },
            // An `expect class` is not an abstract one: `expect class E { abstract val a: Int }` is
            // *abstract property 'a' in non-abstract class 'E'* (measured, -Xmulti-platform).
            { `class`(EXPECT, "E") { `val`(ABSTRACT, "x", INT) } },
        ).forEachIndexed { index, position ->
            val e = assertFailsWith<IllegalStateException>("position $index") {
                file("com.example", "A", body = position)
            }
            assertEquals(message, e.message, "position $index")
        }
    }

    /** The four containers that *can* hold one, none of which may start throwing. */
    @Test
    fun `abstract is allowed in an interface and in an abstract, sealed or enum class`() {
        val rendered = file("com.example", "A") {
            `interface`("I") { `val`(ABSTRACT, "a", INT) }
            `class`(ABSTRACT, "C") { `val`(ABSTRACT, "b", INT) }
            `class`(SEALED, "S") { `val`(ABSTRACT, "c", INT) }
            `class`(ENUM, "E") { `val`(ABSTRACT, "d", INT) }
        }.toString()
        // KotlinPoet drops the redundant `abstract` inside an interface, where it is implicit.
        assertTrue("public interface I {\n  public val a: Int\n}" in rendered, rendered)
        assertTrue("public abstract val b: Int" in rendered, rendered)
        assertTrue("public abstract val c: Int" in rendered, rendered)
        assertTrue("public abstract val d: Int" in rendered, rendered)
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
