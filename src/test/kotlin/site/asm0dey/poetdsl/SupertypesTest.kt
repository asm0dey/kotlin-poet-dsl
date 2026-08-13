package site.asm0dey.poetdsl

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier.ABSTRACT
import com.squareup.kotlinpoet.KModifier.OVERRIDE
import com.squareup.kotlinpoet.LONG
import site.asm0dey.poetdsl.ParamKind.VAL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deviation D26: `superclass` and `superinterface`.
 *
 * The two places superclass constructor arguments can legally go — the class header when the class
 * has a primary constructor, a secondary constructor's `: super(…)` when it does not — are the whole
 * subject here. The second is D25's `` `super` ``; what this file pins down is the first, and the
 * rejection of the combination that would render Kotlin the compiler refuses.
 */
class SupertypesTest {
    private val base = ClassName("com.example", "Base")
    private val runnable = ClassName("java.lang", "Runnable")
    private val closeable = ClassName("java.io", "Closeable")

    @Test
    fun `a class extends a superclass and implements interfaces`() {
        assertEquals(
            """
            package com.example

            import java.io.Closeable
            import java.lang.Runnable

            public class Impl : Base(), Runnable, Closeable

            """.trimIndent(),
            file("com.example", "Impl") {
                `class`("Impl") {
                    superclass(base)
                    superinterface(runnable)
                    superinterface(closeable)
                }
            }.toString(),
        )
    }

    /** The header-argument case: the arguments name the class's own primary-constructor parameters. */
    @Test
    fun `superclass arguments may name the primary constructor parameters`() {
        assertEquals(
            """
            package com.example

            import kotlin.Long

            public class User(
              public val id: Long,
            ) : Base(id, 1)

            """.trimIndent(),
            file("com.example", "User") {
                `class`("User", param(VAL, "id", LONG)) { id -> superclass(base, id, 1.lit) }
            }.toString(),
        )
    }

    /** The in-body `constructorParam` reaches the same place, written in either order. */
    @Test
    fun `superclass arguments work with an in body primary constructor in both orders`() {
        val paramFirst = file("com.example", "A") {
            `class`("User") {
                val id = constructorParam(VAL, "id", LONG)
                superclass(base, id)
            }
        }.toString()
        assertTrue("public class User(\n  public val id: Long,\n) : Base(id)" in paramFirst, paramFirst)
    }

    /** An `object` has no primary constructor of its own, and its header call is still legal Kotlin. */
    @Test
    fun `an object can extend a class with arguments`() {
        val out = file("com.example", "A") { `object`("Registry") { superclass(base, 1.lit) } }.toString()
        assertTrue("public object Registry : Base(1)" in out, out)
    }

    /** An interface extends interfaces — `superinterface`, in an interface body. */
    @Test
    fun `an interface extends another interface`() {
        val out = file("com.example", "A") {
            `interface`("Service") { superinterface(runnable) }
        }.toString()
        assertTrue("public interface Service : Runnable" in out, out)
    }

    @Test
    fun `an interface has no superclass`() {
        val thrown = kotlin.runCatching {
            file("com.example", "A") { `interface`("Service") { superclass(base) } }
        }.exceptionOrNull()
        assertEquals(
            "superclass: an interface has no superclass. Use superinterface to extend another interface.",
            (thrown as IllegalStateException).message,
        )
    }

    @Test
    fun `a class extends only one class`() {
        val thrown = kotlin.runCatching {
            file("com.example", "A") {
                `class`("C") {
                    superclass(base)
                    superclass(runnable)
                }
            }
        }.exceptionOrNull()
        assertEquals(
            "superclass: a class can only extend one class, and this one already does.",
            (thrown as IllegalStateException).message,
        )
    }

    /** KotlinPoet's map of superinterfaces would swallow the second one; this says so instead. */
    @Test
    fun `the same interface cannot be implemented twice`() {
        val thrown = kotlin.runCatching {
            file("com.example", "A") {
                `class`("C") {
                    superinterface(runnable)
                    superinterface(runnable)
                }
            }
        }.exceptionOrNull()
        assertEquals(
            "superinterface: this class already implements java.lang.Runnable.",
            (thrown as IllegalStateException).message,
        )
    }

    /**
     * The invalid combination, in both writing orders: header arguments make the header a primary
     * constructor, and a secondary constructor would then have to delegate to it with `: this(…)`.
     * KotlinPoet rejects the same pair, but only at `build()` and without naming a construct.
     */
    @Test
    fun `superclass arguments and a secondary constructor are rejected in both orders`() {
        val argsFirst = kotlin.runCatching {
            file("com.example", "A") {
                `class`("C") {
                    superclass(base, 1.lit)
                    `constructor`(param("x", INT)) { _ -> }
                }
            }
        }.exceptionOrNull()
        val ctorFirst = kotlin.runCatching {
            file("com.example", "A") {
                `class`("C") {
                    `constructor`(param("x", INT)) { _ -> }
                    superclass(base, 1.lit)
                }
            }
        }.exceptionOrNull()
        for (thrown in listOf(argsFirst, ctorFirst)) {
            assertTrue(thrown is IllegalStateException, "$thrown")
            assertEquals(SUPERCLASS_ARGS_PLUS_SECONDARY, thrown.message)
        }
    }

    /**
     * Without arguments there is no header call to delegate to, so a secondary constructor is fine
     * and KotlinPoet writes the supertype bare — which is exactly the shape D25's `` `super` `` will
     * fill in.
     */
    @Test
    fun `a superclass with no arguments coexists with a secondary constructor`() {
        val out = file("com.example", "A") {
            `class`("C") {
                superclass(base)
                `constructor`(param("x", INT)) { _ -> }
            }
        }.toString()
        assertTrue("public class C : Base {" in out, out)
        assertTrue("public constructor(x: Int)" in out, out)
    }

    /** The rendered Kotlin is what matters, so `kotlinc` gets the last word on the whole shape. */
    @Test
    fun `a generated class with supertypes compiles`() {
        assertCompiles(
            file("com.example", "Supertypes") {
                `class`(ABSTRACT, "Base", param(VAL, "id", LONG)) { _ -> }
                `interface`("Greeter") { `fun`(ABSTRACT, "greet") { } }
                `class`("Impl", param(VAL, "tag", LONG)) { tag ->
                    superclass(ClassName("com.example", "Base"), tag)
                    superinterface(ClassName("com.example", "Greeter"))
                    `fun`(OVERRIDE, "greet") { +call("println", tag) }
                }
            }.toString(),
        )
    }
}
