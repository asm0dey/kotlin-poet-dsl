package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.CONST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.STRING
import com.tschuchort.compiletesting.KotlinCompilation
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import site.asm0dey.poetdsl.ParamKind.VAL
import site.asm0dey.poetdsl.ParamKind.VAR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Deviation D29: `init { }` blocks and companion objects, the two body-level constructs a generated
 * type could not express.
 */
@OptIn(ExperimentalCompilerApi::class)
class TypeBodyTest {
    // --- init -----------------------------------------------------------------------------------

    @Test
    fun `an init block renders inside the class and compiles`() {
        val rendered = file("com.example", "User") {
            `class`("User", param(VAL, "id", LONG)) { id ->
                `init` { +call("require", id gt 0L.lit) }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Long

            public class User(
              public val id: Long,
            ) {
              init {
                require(id > 0L)
              }
            }

            """.trimIndent(),
            rendered,
        )
        assertCompiles(rendered)
    }

    /**
     * The whole reason D29 came before D30: a **plain** primary-constructor parameter has no
     * property and is unresolvable in a member body, but it *is* resolvable in an `init` block. The
     * handle has to work there, and the output has to compile.
     */
    @Test
    fun `an init block sees both a property parameter and a plain one`() {
        val rendered = file("com.example", "User") {
            `class`("User", param(VAL, "id", LONG), param(null, "seed", INT)) { id, seed ->
                `init` {
                    +call("require", id gt 0L.lit)
                    +call("println", seed)
                }
            }
        }.toString()
        assertTrue("require(id > 0L)" in rendered, rendered)
        assertTrue("println(seed)" in rendered, rendered)
        assertCompiles(rendered)
    }

    /** A plain parameter is equally visible in a property initializer — the other initializer half. */
    @Test
    fun `a plain parameter reaches a property initializer`() {
        val rendered = file("com.example", "User") {
            `class`("User", param(null, "seed", INT)) { seed ->
                `val`("cache", INT, init = seed + 1.lit)
            }
        }.toString()
        assertTrue("public val cache: Int = seed + 1" in rendered, rendered)
        assertCompiles(rendered)
    }

    /** Several init blocks are legal and keep declaration order. */
    @Test
    fun `several init blocks render in order`() {
        val rendered = file("com.example", "A") {
            `class`("A") {
                `init` { +call("println", 1.lit) }
                `init` { +call("println", 2.lit) }
            }
        }.toString()
        assertTrue(rendered.indexOf("println(1)") < rendered.indexOf("println(2)"), rendered)
        assertCompiles(rendered)
    }

    /** An object has state to initialize, so it takes an init block too. */
    @Test
    fun `an object takes an init block`() {
        val rendered = file("com.example", "A") {
            `object`("Registry") { `init` { +call("println", "loaded".lit) } }
        }.toString()
        assertTrue("init {" in rendered, rendered)
        assertCompiles(rendered)
    }

    /** A local declared in an init block renames away from the parameters it would shadow. */
    @Test
    fun `an init block local renames against both parameter levels`() {
        val rendered = file("com.example", "A") {
            `class`("A", param(VAL, "id", LONG), param(null, "seed", INT)) { _, _ ->
                `init` {
                    `val`("id", INT, init = 1.lit)
                    `val`("seed", INT, init = 2.lit)
                }
            }
        }.toString()
        assertTrue("val id2: Int = 1" in rendered, rendered)
        assertTrue("val seed2: Int = 2" in rendered, rendered)
        assertCompiles(rendered)
    }

    @Test
    fun `an interface cannot have an initializer block`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `interface`("I") { `init` { +call("println") } } }
        }
        assertEquals(
            "`init`: an interface cannot have an initializer block; it has no state to " +
                "initialize. Move the code to a property initializer or a function.",
            failure.message,
        )
    }

    @Test
    fun `a returning init block is rejected`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `class`("A") { `init` { ret(1.lit) } } }
        }
        assertEquals(
            "`init`: an initializer block cannot return a value; Kotlin allows no `return` there. " +
                "Move the code to a function, or make it a property initializer.",
            failure.message,
        )
    }

    /** What that guard rests on: kotlinc's own verdict on the shape the DSL refuses to produce. */
    @Test
    fun `kotlinc refuses a return in an initializer block`() {
        val result = compile("class A { init { return } }")
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("'return' is prohibited here" in result.messages, result.messages)
    }

    /** ADR 0008 is unweakened: an `init` block rejects a foreign handle like any attached body. */
    @Test
    fun `a foreign handle in an init block is rejected`() {
        var escaped: Expr? = null
        file("com.example", "A") { `fun`("f", param("x", INT)) { x -> escaped = x; +call("println", x) } }
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "B") { `class`("Box") { `init` { +escaped!! } } }
        }
        assertTrue("does not enclose the current scope" in failure.message.orEmpty(), "${failure.message}")
    }

    // --- companion object -----------------------------------------------------------------------

    @Test
    fun `an anonymous companion object holds a factory function`() {
        val rendered = file("com.example", "User") {
            `class`("User", param(VAL, "id", LONG)) { _ ->
                companionObject {
                    `fun`("of", param("raw", STRING), returns = LONG) { raw ->
                        ret(raw.call("toLong"))
                    }
                }
            }
        }.toString()
        assertEquals(
            """
            package com.example

            import kotlin.Long
            import kotlin.String

            public class User(
              public val id: Long,
            ) {
              public companion object {
                public fun of(raw: String): Long = raw.toLong()
              }
            }

            """.trimIndent(),
            rendered,
        )
        assertCompiles(rendered)
    }

    @Test
    fun `a named companion object renders its name`() {
        val rendered = file("com.example", "User") {
            `class`("User") {
                companionObject("Factory") { `val`(CONST, "MAX", INT, init = 10.lit) }
            }
        }.toString()
        assertTrue("public companion object Factory {" in rendered, rendered)
        assertTrue("public const val MAX: Int = 10" in rendered, rendered)
        assertCompiles(rendered)
    }

    /** An interface may have one too. */
    @Test
    fun `an interface takes a companion object`() {
        val rendered = file("com.example", "A") {
            `interface`("Codec") { companionObject { `val`(CONST, "VERSION", INT, init = 1.lit) } }
        }.toString()
        assertTrue("public companion object {" in rendered, rendered)
        assertCompiles(rendered)
    }

    @Test
    fun `an object cannot declare a companion object`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `object`("O") { companionObject { } } }
        }
        assertEquals(
            "companionObject: a named object cannot declare a companion object; only a class or " +
                "an interface can.",
            failure.message,
        )
    }

    /** Nor may a companion object contain another one; the kind check answers both. */
    @Test
    fun `a companion object cannot declare a companion object`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `class`("A") { companionObject { companionObject { } } } }
        }
        assertEquals(
            "companionObject: a companion object cannot declare a companion object; only a class " +
                "or an interface can.",
            failure.message,
        )
    }

    @Test
    fun `a second companion object is rejected`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `class`("A") { companionObject { }; companionObject("B") { } } }
        }
        assertEquals(
            "companionObject: this class already declares a companion object, and Kotlin allows one.",
            failure.message,
        )
    }

    /** The anonymous form is declared under `Companion`, so it collides with a nested type of that name. */
    @Test
    fun `a companion object collides with a nested type of the same name`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `class`("A") { `class`("Companion") { }; companionObject { } } }
        }
        assertEquals("A type named \"Companion\" is already declared in this scope.", failure.message)
    }

    /**
     * A companion object is a nested object: it cannot see the enclosing type's members, so its own
     * are uniquified independently rather than renamed against names they could never shadow — the
     * same rooting [declareType] gives a nested type.
     */
    @Test
    fun `a companion object's members do not rename against the enclosing type`() {
        val rendered = file("com.example", "A") {
            `class`("A", param(VAL, "id", LONG)) { _ ->
                companionObject { `fun`("of", param("id", LONG)) { _ -> } }
            }
        }.toString()
        assertTrue("public fun of(id: Long)" in rendered, rendered)
        assertCompiles(rendered)
    }

    /** An object has no constructors, so the message names the companion as the kind. */
    @Test
    fun `a constructor inside a companion object is rejected`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "A") { `class`("A") { companionObject { `constructor` { } } } }
        }
        assertEquals(
            "constructor: a companion object cannot declare a constructor; only a class can.",
            failure.message,
        )
    }

    /** Both constructs, in one type, with a `var` parameter for the third property kind. */
    @Test
    fun `init and companionObject coexist`() {
        val rendered = file("com.example", "User") {
            `class`("User", param(VAL, "id", LONG), param(VAR, "label", STRING)) { id, _ ->
                `init` { +call("require", id gt 0L.lit) }
                companionObject("Factory") {
                    `fun`("of", param("id", LONG), returns = reference<Any>()) { p ->
                        ret(call("User", p, "anonymous".lit))
                    }
                }
            }
        }.toString()
        assertTrue("init {" in rendered, rendered)
        assertTrue("public companion object Factory {" in rendered, rendered)
        assertCompiles(rendered)
    }
}
