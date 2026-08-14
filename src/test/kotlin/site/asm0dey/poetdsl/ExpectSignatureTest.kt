package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.EXPECT
import com.squareup.kotlinpoet.KModifier.LATEINIT
import com.squareup.kotlinpoet.STRING
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An `expect` property is a **signature**: it carries no initializer, no delegate, no accessor body
 * and no `lateinit`, and Kotlin says so on all three frontends. The DSL knew half of that — `expect`
 * containers set `needsValue = false`, so a property there *may* have no value — and nothing asked
 * the other half, so it *had* to be allowed one.
 *
 * The brief for this round named one instance: `typeSpec(EXPECT.toModifiers(), …)` with an
 * initializer reached KotlinPoet's `TypeSpec.Builder.addProperty` and raised
 * `IllegalArgumentException: properties in expect classes can't have initializers`, which Global
 * Constraint 26 forbids and whose message names neither construct. Closing it only there would have
 * repeated the defect this round exists to remove, because KotlinPoet's own check covers exactly the
 * *direct* members of an `expect`-modified builder and nothing else. Everywhere else the DSL
 * rendered, and what it rendered compiles nowhere:
 *
 * ```
 * expect val a: Int = 1                              all three: expected property cannot have an
 * expect class E { class N { val a: Int = 1 } }                  initializer.
 * expect class E { companion object { val a: Int = 1 } }
 * expect class E { object N { val a: Int = 1 } }
 * expect val a: Int by lazy { 1 }                    all three: expected property cannot be
 * expect class E { companion object { val a: Int by lazy { 1 } } }        delegated.
 * expect val a: Int get() = 1                        all three: expected declaration cannot have a
 * expect class E { class N { val a: Int get() = 1 } }            body.
 * expect lateinit var a: String                      all three: expected property cannot be
 * expect class E { lateinit var a: String }                      'lateinit'.
 * ```
 *
 * The first of those is D36's own table, row 2 — measured a round ago as an error and rendered by
 * this DSL ever since.
 */
@OptIn(ExperimentalCompilerApi::class)
class ExpectSignatureTest {
    private val expected =
        "is an `expect` declaration — by its own EXPECT modifier, or by the `expect` type it is " +
            "declared in — and an expected property is a signature: "

    private fun initMessage(keyword: String = "val") =
        "`$keyword`: 'x' $expected\"expected property cannot have an initializer\" on the JVM, on " +
            "Kotlin/JS and on Kotlin/Wasm alike. Drop init = …; the value belongs on the `actual` " +
            "declaration."

    private fun byMessage(keyword: String = "val") =
        "`$keyword`: 'x' $expected\"expected property cannot be delegated\" on all three frontends. " +
            "Drop by = …; the delegate belongs on the `actual` declaration."

    private fun accessorMessage(keyword: String = "val") =
        "`$keyword`: 'x' $expected\"expected declaration cannot have a body\" on all three " +
            "frontends. Drop the accessor; it belongs on the `actual` declaration."

    private fun lateinitMessage(keyword: String = "var") =
        "`$keyword`: 'x' $expected\"expected property cannot be 'lateinit'\" on all three " +
            "frontends. Drop LATEINIT; it belongs on the `actual` declaration."

    /** The property's own `EXPECT`, at file level, where no container is involved at all. */
    @Test
    fun `an expect modifier forbids every kind of value`() {
        listOf<Pair<String, FileScope.() -> Unit>>(
            initMessage() to { `val`(EXPECT, "x", INT, init = 1.lit) },
            byMessage() to { `val`(EXPECT, "x", INT, by = expression("lazy()")) },
            accessorMessage() to { `val`(EXPECT, "x", INT) { ret(1.lit) } },
            lateinitMessage() to { `var`(EXPECT + LATEINIT, "x", STRING) },
        ).forEachIndexed { index, (message, position) ->
            val e = assertFailsWith<IllegalStateException>("position $index") {
                file("com.example", "A", body = position)
            }
            assertEquals(message, e.message, "position $index")
        }
    }

    /**
     * The container's `expect`, at every depth — which is the half KotlinPoet does not check and the
     * DSL rendered. The first row is the one the brief asked for; the rest are the same rule at the
     * sites its own check cannot see.
     */
    @Test
    fun `an expect container forbids every kind of value`() {
        listOf<Pair<String, FileScope.() -> Unit>>(
            initMessage() to { `class`(EXPECT, "E") { `val`("x", INT, init = 1.lit) } },
            byMessage() to { `class`(EXPECT, "E") { `val`("x", INT, by = expression("lazy()")) } },
            accessorMessage() to { `class`(EXPECT, "E") { `val`("x", INT) { ret(1.lit) } } },
            lateinitMessage() to { `class`(EXPECT, "E") { `var`(LATEINIT, "x", STRING) } },
            initMessage() to { `class`(EXPECT, "E") { `class`("N") { `val`("x", INT, init = 1.lit) } } },
            initMessage() to { `class`(EXPECT, "E") { companionObject { `val`("x", INT, init = 1.lit) } } },
            initMessage() to { `class`(EXPECT, "E") { `object`("N") { `val`("x", INT, init = 1.lit) } } },
            accessorMessage() to { `class`(EXPECT, "E") { `class`("N") { `val`("x", INT) { ret(1.lit) } } } },
            initMessage() to { `object`(EXPECT, "O") { `val`("x", INT, init = 1.lit) } },
            initMessage() to { `interface`(EXPECT, "I") { `val`("x", INT, init = 1.lit) } },
        ).forEachIndexed { index, (message, position) ->
            val e = assertFailsWith<IllegalStateException>("position $index") {
                file("com.example", "A", body = position)
            }
            assertEquals(message, e.message, "position $index")
        }
    }

    /**
     * The exact shape the brief reported, with the exception type Global Constraint 26 requires. At
     * base and at `b46e58e` this raised KotlinPoet's `IllegalArgumentException: properties in expect
     * classes can't have initializers`, from `TypeSpec.Builder.addProperty`, naming neither
     * construct — and its sibling `… can't have getters and setters` for the accessor form.
     */
    @Test
    fun `the detached expect builder answers with an IllegalStateException`() {
        assertEquals(
            initMessage(),
            assertFailsWith<IllegalStateException> {
                typeSpec(EXPECT.toModifiers(), name = "E") { `val`("x", INT, init = 1.lit) }
            }.message,
        )
        assertEquals(
            accessorMessage(),
            assertFailsWith<IllegalStateException> {
                typeSpec(EXPECT.toModifiers(), name = "E") { `val`("x", INT) { ret(1.lit) } }
            }.message,
        )
    }

    /**
     * `propertySpec` runs no *container* rule, and this is not one: a property carrying `EXPECT` of
     * its own is a signature wherever it is spliced, so the modifier answers for itself here as it
     * does at file level. Without the modifier the detached builder is as permissive as it ever was.
     */
    @Test
    fun `a detached property answers for its own expect modifier`() {
        assertEquals(
            "propertySpec: 'x' $expected\"expected property cannot have an initializer\" on the JVM, " +
                "on Kotlin/JS and on Kotlin/Wasm alike. Drop init = …; the value belongs on the " +
                "`actual` declaration.",
            assertFailsWith<IllegalStateException> {
                propertySpec(EXPECT.toModifiers(), "x", INT, init = 1.lit)
            }.message,
        )
        assertEquals("val x: kotlin.Int = 1\n", propertySpec(name = "x", type = INT, init = 1.lit).toString())
    }

    /**
     * The renders the guard now refuses, handed to kotlinc — the exact strings this DSL produced for
     * each of them before this round.
     *
     * The assertion is on **messages** and not on the exit code, because an `expect` compilation in a
     * single module always ends in `COMPILATION_ERROR` on the missing `actual` alone (D36), so an
     * exit code would prove nothing here. Each row asserts the diagnostic that names the rule.
     */
    @Test
    fun `every expect shape the guard rejects is one kotlinc rejects`() {
        listOf(
            "public expect val x: Int = 1" to "property cannot have an initializer",
            "public expect class E {\n  public class N {\n    public val x: Int = 1\n  }\n}" to
                "property cannot have an initializer",
            "public expect class E {\n  public companion object {\n    public val x: Int = 1\n  }\n}" to
                "property cannot have an initializer",
            "public expect val x: Int by lazy()" to "property cannot be delegated",
            "public expect val x: Int\n  get() = 1" to "declaration cannot have a body",
            "public expect lateinit var x: String" to "property cannot be 'lateinit'",
            "public expect class E {\n  public lateinit var x: String\n}" to "property cannot be 'lateinit'",
        ).forEach { (source, diagnostic) ->
            val messages =
                compile("package com.example\n\nimport kotlin.Int\nimport kotlin.String\n\n$source\n").messages
            assertTrue(diagnostic in messages, "$source\n$messages")
        }
    }

    /**
     * The other side of the boundary. An `expect` body still takes a property with no value at all,
     * which is the whole point of `needsValue` being false there, and a non-`expect` container is
     * untouched.
     */
    @Test
    fun `an expect signature with no value still renders`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public expect class E {
              public val x: Int

              public class N {
                public val y: Int
              }

              public companion object {
                public val z: Int
              }
            }

            public expect val top: Int

            """.trimIndent(),
            file("com.example", "A") {
                `class`(EXPECT, "E") {
                    `val`("x", INT)
                    `class`("N") { `val`("y", INT) }
                    companionObject { `val`("z", INT) }
                }
                `val`(EXPECT, "top", INT)
            }.toString(),
        )
    }
}
