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

    /**
     * Minor 4: the shadow's message used to say `init` is only valid "inside a class or object
     * body", which is too narrow — a companion object (of a class, or of an interface, which has
     * no state of its own but whose companion is an object like any other) both compile. Both are
     * exercised here as the message's positive control.
     */
    @Test
    fun `an init block compiles inside a class's companion object and an interface's`() {
        val classCompanion = file("com.example", "A") {
            `class`("A") { companionObject { `init` { +call("println", "loaded".lit) } } }
        }.toString()
        assertTrue("init {" in classCompanion, classCompanion)
        assertCompiles(classCompanion)

        val interfaceCompanion = file("com.example", "B") {
            `interface`("B") { companionObject { `init` { +call("println", "loaded".lit) } } }
        }.toString()
        assertTrue("init {" in interfaceCompanion, interfaceCompanion)
        assertCompiles(interfaceCompanion)
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

    /**
     * Important 2: the companion's `ScopeId` used to chain to the enclosing type's, so `checkOwned`
     * wrongly *accepted* a handle to the enclosing type's own instance state (a `val`/`var`
     * constructor parameter or property) used inside a companion body — a companion object is a
     * nested object, and Kotlin does not let it see the enclosing type's instance members any more
     * than a nested type can. The DSL now refuses to render that instead of emitting Kotlin that
     * does not compile (Global Constraint 26).
     */
    @Test
    fun `an enclosing-instance handle is rejected inside a companion object`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "F") {
                `class`("F", param(VAL, "id", LONG)) { id ->
                    companionObject { `fun`("show") { +call("println", id) } }
                }
            }
        }
        assertTrue("does not enclose the current scope" in failure.message.orEmpty(), "${failure.message}")
    }

    /** kotlinc's word on the shape the guard above refuses to produce. */
    @Test
    fun `kotlinc refuses the enclosing-instance handle a companion object would have smuggled in`() {
        val result = compile(
            "class F(val id: Long) { companion object { fun show() { println(id) } } }",
        )
        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue("Unresolved reference" in result.messages, result.messages)
    }

    /**
     * The companion's own root `ScopeId` still accepts everything legitimate: a handle declared
     * inside the companion body is usable in that same companion's member functions, exactly as it
     * would be in any other type body.
     */
    @Test
    fun `a companion object's own property is usable inside its own member function`() {
        val rendered = file("com.example", "A") {
            `class`("A") {
                companionObject {
                    val max = `val`(CONST, "MAX", INT, init = 10.lit)
                    `fun`("show") { +call("println", max) }
                }
            }
        }.toString()
        assertTrue("println(MAX)" in rendered, rendered)
        assertCompiles(rendered)
    }

    /**
     * The companion's `ScopeId` used to be rooted *unconditionally* — `ScopeId(null, "companion
     * object")` — so **every** companion, at any depth including one directly inside a top-level
     * class, lost access to file-level handles: this exact shape threw "Handle from scope 'file'
     * does not enclose the current scope 'fun(f)'" while kotlinc compiles `val limit = 10` plus
     * `class Top { companion object { fun f() { println(limit) } } }` cleanly. [TypeScope.fileId]
     * closed it.
     */
    @Test
    fun `a file-level handle is accepted inside a companion object of a top-level class`() {
        var handle: Expr? = null
        val rendered = file("com.example", "Api") {
            handle = `val`("limit", INT, init = 10.lit)
            `class`("Top") { companionObject { `fun`("f") { +call("println", handle) } } }
        }.toString()
        assertTrue("println(limit)" in rendered, rendered)
        assertCompiles(rendered)
    }

    /** And through a nesting level as well: the file's id is threaded down unchanged. */
    @Test
    fun `a file-level handle is accepted inside a companion object of a nested class`() {
        var handle: Expr? = null
        val rendered = file("com.example", "Api") {
            handle = `val`("limit", INT, init = 10.lit)
            `class`("Outer") {
                `class`("Inner") { companionObject { `fun`("f") { +call("println", handle) } } }
            }
        }.toString()
        assertTrue("println(limit)" in rendered, rendered)
        assertCompiles(rendered)
    }

    /** kotlinc's word on both shapes above. */
    @Test
    fun `kotlinc accepts a top-level declaration read from inside a companion object`() {
        assertCompiles(
            """
            private val limit: Int = 10

            public class Top {
              public companion object {
                public fun f() { println(limit) }
              }
            }

            public class Outer {
              public class Inner {
                public companion object {
                  public fun f() { println(limit) }
                }
              }
            }
            """.trimIndent(),
        )
    }

    /**
     * The ownership half at depth: re-parenting the companion at the file must not let it see the
     * *nested* class it belongs to either. That class's id is a sibling under the same file id.
     */
    @Test
    fun `an enclosing-instance handle is rejected inside a nested class's companion object`() {
        val failure = assertFailsWith<IllegalStateException> {
            file("com.example", "F") {
                `class`("Outer") {
                    `class`("Inner", param(VAL, "id", LONG)) { id ->
                        companionObject { `fun`("show") { +call("println", id) } }
                    }
                }
            }
        }
        assertTrue("does not enclose the current scope" in failure.message.orEmpty(), "${failure.message}")
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
