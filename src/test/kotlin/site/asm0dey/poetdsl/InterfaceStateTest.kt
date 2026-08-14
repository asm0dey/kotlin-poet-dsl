package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.LATEINIT
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * An interface holds no state, so a property in an interface body has no backing field. The DSL read
 * `kindName == "interface"` at exactly one site — `PropertyContainer.needsValue`, to know that a
 * property there *may* have no value — and nowhere asked the other half, so it rendered every shape
 * that needs one:
 *
 * ```
 * interface I { val a: Int = 1 }          all three: property initializers in interfaces are prohibited.
 * interface I { var a: Int = 1 }
 * interface I { val a: Int by lazy { 1 } } all three: delegated properties in interfaces are prohibited.
 * interface I { lateinit var a: String }   all three: 'lateinit' modifier is not allowed on abstract
 *                                                     properties.
 * ```
 *
 * This is the same failure shape as the round's other two, on a third container fact: `kindName` was
 * consulted where it decides whether a value is *required* and not where it decides whether one is
 * *possible*.
 *
 * The boundary is the interface **body** and stops there — an interface's companion object is a
 * different container and all four shapes compile inside it (measured, all three frontends), which
 * is why the field reads `kindName` and nothing inherited.
 */
@OptIn(ExperimentalCompilerApi::class)
class InterfaceStateTest {
    private val prefix = "is declared in an interface body, and an interface holds no state, so its " +
        "properties have no backing field: "

    private fun initMessage(keyword: String = "val") =
        "`$keyword`: 'x' $prefix\"property initializers in interfaces are prohibited\" on all three " +
            "frontends. Move the value into a getter, or declare it in the interface's " +
            "companion object."

    private fun byMessage(keyword: String = "val") =
        "`$keyword`: 'x' $prefix\"delegated properties in interfaces are prohibited\" on all three " +
            "frontends. Move the delegate into a getter, or declare it in the " +
            "interface's companion object."

    private fun lateinitMessage(keyword: String = "var") =
        "`$keyword`: 'x' $prefix\"'lateinit' modifier is not allowed on abstract properties\" on all " +
            "three frontends. Drop LATEINIT; an interface property is abstract, and the implementing " +
            "class carries the storage."

    @Test
    fun `an interface property cannot carry state`() {
        listOf<Pair<String, FileScope.() -> Unit>>(
            initMessage() to { `interface`("I") { `val`("x", INT, init = 1.lit) } },
            initMessage(keyword = "var") to { `interface`("I") { `var`("x", INT, init = 1.lit) } },
            byMessage() to { `interface`("I") { `val`("x", INT, by = expression("lazy()")) } },
            lateinitMessage() to { `interface`("I") { `var`(LATEINIT, "x", STRING) } },
        ).forEachIndexed { index, (message, position) ->
            val e = assertFailsWith<IllegalStateException>("position $index") {
                file("com.example", "A", body = position)
            }
            assertEquals(message, e.message, "position $index")
        }
    }

    /**
     * The renders the guard now refuses, handed to kotlinc — the same shape
     * `every shape the guard rejects is one kotlinc rejects` takes for the missing-value check.
     * These are the exact strings this DSL produced for each of them before this round.
     */
    @Test
    fun `every interface shape the guard rejects is one kotlinc rejects`() {
        listOf(
            "public interface I {\n  public val x: Int = 1\n}",
            "public interface I {\n  public var x: Int = 1\n}",
            "public interface I {\n  public val x: Int by lazy()\n}",
            "public interface I {\n  public lateinit var x: String\n}",
        ).forEach { source ->
            assertEquals(
                KotlinCompilation.ExitCode.COMPILATION_ERROR,
                compile("package com.example\n\nimport kotlin.Int\nimport kotlin.String\n\n$source\n").exitCode,
                source,
            )
        }
    }

    /**
     * The three things that still work in an interface body — a getter, a bare signature, and the
     * companion object, which is a container of its own and holds state exactly as any object does.
     * Measured: `interface I { val a: Int get() = 1 }`,
     * `interface I { companion object { val a: Int = 1 } }` and the `by lazy` form of the latter all
     * compile clean on all three frontends.
     */
    @Test
    fun `a getter, a signature and the companion object are untouched`() {
        assertEquals(
            """
            package com.example

            import kotlin.Int

            public interface I {
              public val x: Int
                get() = 1

              public val y: Int

              public companion object {
                public val z: Int = 2

                public val w: Int by lazy()
              }
            }

            """.trimIndent(),
            file("com.example", "A") {
                `interface`("I") {
                    `val`("x", INT) { ret(1.lit) }
                    `val`("y", INT)
                    companionObject {
                        `val`("z", INT, init = 2.lit)
                        `val`("w", INT, by = expression("lazy()"))
                    }
                }
            }.toString(),
        )
    }
}
